import Foundation
import Combine
import os

/// Менеджер dnstt-client subprocess — аналог DnsttBridge.kt из Android.
/// Запускает бинарник `dnstt-client` (лежит внутри Bundle/Resources/), парсит
/// stderr на `begin stream / end stream` чтобы держать счётчик активных стримов
/// и uptime.
///
/// dnstt-client сам по себе не экспортирует bytes-counters — поэтому
/// статистика байт на macOS-клиенте недоступна (в отличие от Android, где
/// мы поверх dnstt поднимаем sing-box и снимаем `uploadTotal/downloadTotal`
/// с clash-api). Для proxy-mode-only это приемлемо: количество активных
/// стримов и uptime даёт достаточный сигнал «работает / не работает».
@MainActor
final class DnsttManager: ObservableObject {

    enum State: Equatable {
        case stopped
        case starting
        case running
        case stopping
        case failed(String)
    }

    @Published private(set) var state: State = .stopped
    @Published private(set) var startedAt: Date?
    @Published private(set) var activeStreams: Int = 0
    @Published private(set) var totalStreams: Int = 0
    @Published private(set) var lastError: String = ""

    /// uptime в секундах, или 0 если не запущен
    var uptimeSec: Int {
        guard let s = startedAt, state == .running else { return 0 }
        return Int(Date().timeIntervalSince(s))
    }

    private var process: Process?
    private var stdoutPipe: Pipe?
    private var stderrPipe: Pipe?
    private var readingTask: Task<Void, Never>?

    private let log = Logger(subsystem: "net.jivenet.client", category: "dnstt")

    /// Старт. Если уже запущен — no-op.
    func start(config: TunnelConfig) async {
        switch state {
        case .stopped, .failed:
            break  // ok to (re)start
        case .starting, .running, .stopping:
            return  // уже что-то происходит
        }

        guard config.isComplete else {
            state = .failed("конфиг неполный")
            return
        }
        guard let binary = bundledBinaryURL() else {
            state = .failed("dnstt-client не найден внутри бандла")
            return
        }

        state = .starting
        lastError = ""
        activeStreams = 0
        totalStreams = 0

        let p = Process()
        p.executableURL = binary
        p.arguments = buildArgs(config: config)

        let stdout = Pipe()
        let stderr = Pipe()
        p.standardOutput = stdout
        p.standardError = stderr
        self.stdoutPipe = stdout
        self.stderrPipe = stderr

        do {
            try p.run()
        } catch {
            state = .failed("не удалось запустить: \(error.localizedDescription)")
            return
        }
        self.process = p
        self.startedAt = Date()
        self.state = .running
        log.info("dnstt-client запущен на 127.0.0.1:\(config.port, privacy: .public)")

        // Параллельно читаем stderr (dnstt-client пишет диагностику туда —
        // `begin session`, `begin/end stream`, ошибки).
        readingTask = Task { [weak self] in
            await self?.readStream(stderr.fileHandleForReading)
        }

        // Watcher: когда процесс умирает — обновляем state.
        p.terminationHandler = { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self else { return }
                let exitCode = self.process?.terminationStatus ?? -1
                if self.state != .stopping {
                    self.state = .failed("dnstt-client умер (exit \(exitCode))")
                    self.log.warning("dnstt-client unexpected exit \(exitCode)")
                } else {
                    self.state = .stopped
                }
                self.startedAt = nil
                self.activeStreams = 0
                self.process = nil
                self.readingTask?.cancel()
                self.readingTask = nil
            }
        }
    }

    /// Остановка. Если не запущен — no-op.
    func stop() {
        guard state == .running || state == .starting else { return }
        state = .stopping
        log.info("останавливаю dnstt-client")

        process?.terminate()
        // Ждём 2 сек на graceful, потом kill -9.
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            if process?.isRunning == true {
                log.warning("dnstt не завершился за 2с, kill -9")
                process?.interrupt()
                kill(process?.processIdentifier ?? 0, SIGKILL)
            }
        }
    }

    // MARK: - private

    private func buildArgs(config: TunnelConfig) -> [String] {
        // Совпадает с DnsttBridge.kt buildCommand():
        //   dnstt-client -doh URL -pubkey HEX DOMAIN LOCAL_ADDR
        //   dnstt-client -udp HOST:PORT ...
        //   dnstt-client -dot HOST:PORT ...
        let local = "127.0.0.1:\(config.port)"
        var args: [String] = []
        let doh = config.doh.trimmingCharacters(in: .whitespaces)
        if doh.hasPrefix("udp://") {
            args += ["-udp", String(doh.dropFirst("udp://".count))]
        } else if doh.hasPrefix("dot://") {
            args += ["-dot", String(doh.dropFirst("dot://".count))]
        } else {
            args += ["-doh", doh]
        }
        args += ["-pubkey", config.pubkey]
        args += [config.domain, local]
        return args
    }

    private func bundledBinaryURL() -> URL? {
        // 1) Production: лежит в Bundle.app/Contents/Resources/dnstt-client
        if let url = Bundle.main.url(forResource: "dnstt-client", withExtension: nil) {
            return url
        }
        // 2) Development: запуск через `swift run` — Bundle.main указывает на
        // .build/.../jivenet, ресурсов рядом нет. Ищем в репозитории.
        let exec = Bundle.main.executableURL ?? URL(fileURLWithPath: "/")
        for candidate in [
            // Когда запускаемся из .build/debug/jivenet — поднимаемся к macos/app/Resources/
            exec.deletingLastPathComponent().appendingPathComponent("../../../Resources/dnstt-client").standardizedFileURL,
            URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
                .appendingPathComponent("Resources/dnstt-client"),
        ] {
            if FileManager.default.isExecutableFile(atPath: candidate.path) {
                return candidate
            }
        }
        return nil
    }

    private func readStream(_ handle: FileHandle) async {
        // FileHandle.bytes.lines — async-итератор поверх pipe'а. Когда pipe
        // закрывается (dnstt-client умер), поток завершается естественно.
        do {
            for try await line in handle.bytes.lines {
                if Task.isCancelled { break }
                await processLine(line)
            }
        } catch {
            log.debug("read pipe закрыт: \(error.localizedDescription, privacy: .public)")
        }
    }

    private func processLine(_ line: String) async {
        log.debug("\(line, privacy: .public)")
        // dnstt-client пишет такое:
        //   2026/05/10 17:30:15 begin session 6f40a3a8
        //   2026/05/10 17:30:17 begin stream 6f40a3a8:3
        //   2026/05/10 17:30:25 end stream 6f40a3a8:3
        if line.contains("begin stream ") {
            activeStreams += 1
            totalStreams += 1
        } else if line.contains("end stream ") {
            activeStreams = max(0, activeStreams - 1)
        } else if line.localizedCaseInsensitiveContains("error") ||
                  line.localizedCaseInsensitiveContains("fatal") {
            lastError = line
        }
    }
}
