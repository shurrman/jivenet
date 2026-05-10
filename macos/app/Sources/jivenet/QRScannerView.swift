import SwiftUI
import AVFoundation
import AppKit
import Combine

/// QR-сканер через AVFoundation: AVCaptureSession + AVCaptureMetadataOutput
/// с типом `.qr`. Камера пишется в превью-слой (`AVCaptureVideoPreviewLayer`),
/// при детекте QR — вызывается onScan(text).
///
/// Аналог QrScanner.kt из Android (там через ML Kit + CameraX).
@MainActor
final class QRScannerSession: NSObject, ObservableObject {

    /// Состояние камеры — UI на это смотрит, чтобы показать превью или alert.
    enum Status {
        case requesting       // ждём ответа на запрос разрешения
        case noPermission     // юзер отказал — нужны NSCameraUsageDescription
                              // и Privacy & Security → Camera → jivenet.app
        case noDevice         // камеры не нашлось (бывает на старых iMac'ах
                              // без built-in камеры и без USB-вебкамеры)
        case running
        case error(String)
    }

    let session = AVCaptureSession()
    @Published var status: Status = .requesting
    @Published var scanned: String?

    private var configured = false
    private let queue = DispatchQueue(label: "net.jivenet.client.scanner",
                                      qos: .userInitiated)

    /// Запросить разрешение и стартовать сессию. Вызывается из onAppear sheet'а.
    func start() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configureAndRun()
        case .notDetermined:
            status = .requesting
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                Task { @MainActor in
                    guard let self else { return }
                    if granted {
                        self.configureAndRun()
                    } else {
                        self.status = .noPermission
                    }
                }
            }
        case .denied, .restricted:
            status = .noPermission
        @unknown default:
            status = .error("неизвестный статус разрешения камеры")
        }
    }

    /// Остановить сессию — onDisappear sheet'а или после успешного скана.
    func stop() {
        guard configured else { return }
        queue.async { [session] in
            if session.isRunning { session.stopRunning() }
        }
    }

    // MARK: - private

    private func configureAndRun() {
        if configured {
            queue.async { [session] in
                if !session.isRunning { session.startRunning() }
            }
            status = .running
            return
        }

        guard let device = AVCaptureDevice.default(for: .video) else {
            status = .noDevice
            return
        }
        do {
            let input = try AVCaptureDeviceInput(device: device)
            session.beginConfiguration()
            if session.canAddInput(input) { session.addInput(input) }

            // MetadataOutput — фильтруем только QR. Делегат вызывается в main
            // queue (ниже), чтобы можно было сразу обновить @Published.
            let metaOut = AVCaptureMetadataOutput()
            if session.canAddOutput(metaOut) {
                session.addOutput(metaOut)
                metaOut.setMetadataObjectsDelegate(self, queue: .main)
                if metaOut.availableMetadataObjectTypes.contains(.qr) {
                    metaOut.metadataObjectTypes = [.qr]
                } else {
                    status = .error(".qr не поддержан на этой камере")
                    session.commitConfiguration()
                    return
                }
            }
            session.commitConfiguration()
            configured = true

            queue.async { [session] in
                session.startRunning()
            }
            status = .running
        } catch {
            status = .error("input init: \(error.localizedDescription)")
        }
    }
}

extension QRScannerSession: AVCaptureMetadataOutputObjectsDelegate {
    nonisolated func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        for obj in metadataObjects {
            guard let qr = obj as? AVMetadataMachineReadableCodeObject,
                  qr.type == .qr,
                  let text = qr.stringValue
            else { continue }
            Task { @MainActor in
                // Сохраняем первый успешный скан и сразу же останавливаем
                // сессию (иначе будем дёргать делегат повторно для того же
                // QR в кадре).
                if scanned == nil {
                    scanned = text
                    stop()
                }
            }
            return
        }
    }
}

/// AVCaptureVideoPreviewLayer внутри NSView — для встраивания в SwiftUI через
/// NSViewRepresentable.
struct CameraPreview: NSViewRepresentable {
    let session: AVCaptureSession

    func makeNSView(context: Context) -> PreviewNSView {
        let v = PreviewNSView()
        v.attach(session: session)
        return v
    }
    func updateNSView(_ nsView: PreviewNSView, context: Context) {
        nsView.attach(session: session)
    }

    /// NSView с залитым `AVCaptureVideoPreviewLayer` как backing layer'ом.
    final class PreviewNSView: NSView {
        private var previewLayer: AVCaptureVideoPreviewLayer?

        override init(frame frameRect: NSRect) {
            super.init(frame: frameRect)
            wantsLayer = true
        }
        required init?(coder: NSCoder) {
            super.init(coder: coder)
            wantsLayer = true
        }

        func attach(session: AVCaptureSession) {
            if previewLayer?.session === session { return }
            let pl = AVCaptureVideoPreviewLayer(session: session)
            pl.videoGravity = .resizeAspectFill
            pl.frame = bounds
            self.layer = pl
            self.previewLayer = pl
        }

        override func layout() {
            super.layout()
            previewLayer?.frame = bounds
        }
    }
}

/// SwiftUI-обёртка: показывается как sheet, при успешном скане отдаёт
/// текст QR через `onScan` и закрывается.
struct QRScannerView: View {
    @StateObject private var scanner = QRScannerSession()
    let onScan: (String) -> Void
    let onCancel: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            HStack {
                Text("Сканер QR").font(.headline)
                Spacer()
                Button("Отмена", action: onCancel).keyboardShortcut(.cancelAction)
            }
            ZStack {
                switch scanner.status {
                case .running:
                    CameraPreview(session: scanner.session)
                        .frame(minWidth: 480, minHeight: 360)
                        .cornerRadius(8)
                        .overlay(
                            // Простая «рамка прицела» поверх превью
                            RoundedRectangle(cornerRadius: 8)
                                .strokeBorder(Color.white.opacity(0.4), lineWidth: 2)
                        )
                case .requesting:
                    Text("Запрашиваю доступ к камере…")
                case .noPermission:
                    VStack(spacing: 8) {
                        Image(systemName: "camera.fill").font(.largeTitle)
                            .foregroundStyle(.secondary)
                        Text("Доступ к камере запрещён")
                        Text("System Settings → Privacy & Security → Camera → разрешить jivenet")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                        Button("Открыть System Settings") {
                            if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Camera") {
                                NSWorkspace.shared.open(url)
                            }
                        }
                    }
                    .frame(minWidth: 480, minHeight: 360)
                case .noDevice:
                    Text("Камеру не нашли (нет встроенной и USB-вебкамеры)")
                        .frame(minWidth: 480, minHeight: 360)
                case .error(let msg):
                    Text("Ошибка: \(msg)")
                        .frame(minWidth: 480, minHeight: 360)
                }
            }
            Text("Наведите камеру на QR с конфигом jivenet")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(16)
        .onAppear { scanner.start() }
        .onDisappear { scanner.stop() }
        .onReceive(scanner.$scanned.compactMap { $0 }) { text in
            onScan(text)
        }
    }
}
