import SwiftUI
import AppKit

/// Главный попап из меню-бара. Сверху — статус-индикатор и большая
/// «Подключить»/«Отключить»-кнопка, снизу — статистика, под ней — ссылка на
/// настройки и quit.
struct ContentView: View {

    @EnvironmentObject var configStore: ConfigStore
    @EnvironmentObject var dnstt: DnsttManager
    // Tick раз в секунду — обновляем uptime текстом
    @State private var tick = Date()
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header
            Divider()
            stats
            Divider()
            buttons
        }
        .padding(16)
        .onReceive(timer) { tick = $0 }
    }

    // MARK: - subviews

    private var header: some View {
        HStack(spacing: 10) {
            statusDot
            VStack(alignment: .leading, spacing: 2) {
                Text(statusText).font(.headline)
                Text("SOCKS5 прокси на 127.0.0.1:\(configStore.config.port)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
        }
    }

    private var statusDot: some View {
        let color: Color
        switch dnstt.state {
        case .running: color = .green
        case .starting, .stopping: color = .yellow
        case .failed: color = .red
        case .stopped: color = .gray
        }
        return Circle().fill(color).frame(width: 14, height: 14)
    }

    private var statusText: String {
        switch dnstt.state {
        case .running: return "Подключено"
        case .starting: return "Подключение…"
        case .stopping: return "Отключение…"
        case .stopped: return "Отключено"
        case .failed(let msg): return msg.isEmpty ? "Ошибка" : "Ошибка: \(msg)"
        }
    }

    private var stats: some View {
        // Завязываем UI на `tick`, чтобы uptime обновлялся каждую секунду
        let _ = tick
        return VStack(alignment: .leading, spacing: 4) {
            Label("Время: \(formatUptime(dnstt.uptimeSec))", systemImage: "clock")
            Label("Активных стримов: \(dnstt.activeStreams)", systemImage: "arrow.triangle.2.circlepath")
            Label("Всего стримов: \(dnstt.totalStreams)", systemImage: "sum")
            if !dnstt.lastError.isEmpty {
                Label(dnstt.lastError, systemImage: "exclamationmark.triangle")
                    .foregroundStyle(.orange)
                    .lineLimit(2)
            }
        }
        .font(.caption)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var buttons: some View {
        VStack(spacing: 8) {
            // Большая кнопка-переключатель
            Button {
                Task { await toggle() }
            } label: {
                Text(toggleLabel)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 4)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(toggleDisabled)
            .keyboardShortcut(.defaultAction)

            HStack {
                Button("Настройки…") {
                    openSettingsWindow()
                }
                Spacer()
                Button("Выйти") {
                    dnstt.stop()
                    // Дать времени на graceful exit dnstt-client
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                        NSApp.terminate(nil)
                    }
                }
            }
            .buttonStyle(.borderless)
            .font(.caption)
        }
    }

    // MARK: - actions

    private func toggle() async {
        switch dnstt.state {
        case .running:
            dnstt.stop()
        case .stopped, .failed:
            await dnstt.start(config: configStore.config)
        default:
            break
        }
    }

    private var toggleLabel: String {
        switch dnstt.state {
        case .running: return "Отключить"
        case .starting: return "Подключение…"
        case .stopping: return "Отключение…"
        default:
            return configStore.config.isComplete ? "Подключить" : "Заполните настройки"
        }
    }

    private var toggleDisabled: Bool {
        switch dnstt.state {
        case .starting, .stopping: return true
        case .stopped, .failed: return !configStore.config.isComplete
        default: return false
        }
    }

    /// Открыть Settings-окно через selector. macOS 14+ называет селектор
    /// `showSettingsWindow:`, macOS 13 — `showPreferencesWindow:`. Пробуем
    /// первый, если responder-chain его не знает — старый.
    private func openSettingsWindow() {
        NSApp.activate(ignoringOtherApps: true)
        let modern = NSSelectorFromString("showSettingsWindow:")
        let legacy = NSSelectorFromString("showPreferencesWindow:")
        if NSApp.sendAction(modern, to: nil, from: nil) { return }
        _ = NSApp.sendAction(legacy, to: nil, from: nil)
    }

    private func formatUptime(_ seconds: Int) -> String {
        guard seconds > 0 else { return "—" }
        let h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60
        if h > 0 {
            return String(format: "%d:%02d:%02d", h, m, s)
        } else {
            return String(format: "%d:%02d", m, s)
        }
    }
}
