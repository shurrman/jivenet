import SwiftUI
import AppKit

/// Settings-окно. Аналог SettingsActivity.kt из Android: форма доменa, ключа,
/// DoH (с пресетами) и порта, плюс кнопка «Импорт из JSON» (вместо QR-сканера —
/// в первой версии для macOS QR не реализовали).
struct SettingsView: View {
    @EnvironmentObject var configStore: ConfigStore
    @State private var importText: String = ""
    @State private var importError: String? = nil
    @State private var showScanner: Bool = false
    @State private var scanToast: String? = nil

    private let dohPresets: [(label: String, value: String)] = [
        ("Cloudflare", "https://1.1.1.1/dns-query"),
        ("Google", "https://8.8.8.8/dns-query"),
        ("Quad9", "https://9.9.9.9/dns-query"),
        ("OpenDNS", "https://208.67.222.222/dns-query"),
        ("AliDNS", "https://223.5.5.5/dns-query"),
    ]

    var body: some View {
        Form {
            Section("Туннель") {
                TextField("Домен", text: bind(\.domain), prompt: Text("t.example.com"))
                    .textFieldStyle(.roundedBorder)
                    .autocorrectionDisabled()

                TextField("Public key (64 hex)", text: bind(\.pubkey))
                    .textFieldStyle(.roundedBorder)
                    .font(.system(.body, design: .monospaced))
                    .autocorrectionDisabled()
                    .onChange(of: configStore.config.pubkey) { _, new in
                        let cleaned = new.lowercased()
                            .filter { $0.isHexDigit || $0.isASCII && ($0.isNumber) }
                        if cleaned != new {
                            configStore.config.pubkey = cleaned
                        }
                    }
            }

            Section("DoH резолвер") {
                TextField("https://...", text: bind(\.doh))
                    .textFieldStyle(.roundedBorder)
                    .autocorrectionDisabled()

                // Пресеты
                FlowLayout(spacing: 6) {
                    ForEach(dohPresets, id: \.value) { preset in
                        presetChip(preset.label, value: preset.value)
                    }
                }

                Text("https://<IP>/... для DoH или udp://<IP>:53 для UDP-режима")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section("Локальный порт SOCKS5") {
                TextField("Port", value: bind(\.port), formatter: portFormatter)
                    .textFieldStyle(.roundedBorder)
                    .frame(maxWidth: 120)
                Text("По умолчанию 1080. Браузер/система → SOCKS5 на 127.0.0.1:\(configStore.config.port)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section("Импорт конфига") {
                HStack {
                    Button {
                        showScanner = true
                    } label: {
                        Label("Сканировать QR", systemImage: "qrcode.viewfinder")
                    }
                    Spacer()
                    if let toast = scanToast {
                        Text(toast).font(.caption).foregroundStyle(.green)
                    }
                }

                Text("Или вставь JSON c сервера (`make qr --json-only`):")
                    .font(.caption)
                TextEditor(text: $importText)
                    .font(.system(.body, design: .monospaced))
                    .frame(height: 80)
                    .border(.secondary)
                HStack {
                    Button("Импортировать") {
                        if configStore.importJSON(importText) {
                            importText = ""
                            importError = nil
                        } else {
                            importError = "JSON некорректен"
                        }
                    }
                    .disabled(importText.isEmpty)

                    Button("Из буфера") {
                        if let s = NSPasteboard.general.string(forType: .string) {
                            importText = s
                        }
                    }

                    Spacer()
                    if let err = importError {
                        Text("⚠ \(err)").font(.caption).foregroundStyle(.red)
                    }
                }
            }

            Section {
                HStack(spacing: 4) {
                    if configStore.config.isComplete {
                        Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                        Text("Конфиг валиден").font(.caption)
                    } else {
                        Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                        Text("Заполните все поля").font(.caption)
                    }
                }
            }
        }
        .formStyle(.grouped)
        .padding()
        .sheet(isPresented: $showScanner) {
            QRScannerView(
                onScan: { text in
                    if configStore.importJSON(text) {
                        scanToast = "✓ Конфиг применён"
                        // Скрыть toast через 3 сек
                        Task {
                            try? await Task.sleep(nanoseconds: 3_000_000_000)
                            scanToast = nil
                        }
                    } else {
                        importError = "QR не содержит валидный JSON-конфиг"
                    }
                    showScanner = false
                },
                onCancel: { showScanner = false }
            )
        }
    }

    private var portFormatter: NumberFormatter {
        let f = NumberFormatter()
        f.minimum = 1024
        f.maximum = 65535
        f.allowsFloats = false
        f.numberStyle = .none
        return f
    }

    @ViewBuilder
    private func presetChip(_ label: String, value: String) -> some View {
        let selected = configStore.config.doh == value
        Button(label) { configStore.config.doh = value }
            .buttonStyle(.bordered)
            .controlSize(.small)
            .tint(selected ? Color.accentColor : Color.secondary)
    }

    private func bind<V>(_ keyPath: WritableKeyPath<TunnelConfig, V>) -> Binding<V> {
        Binding(
            get: { configStore.config[keyPath: keyPath] },
            set: { configStore.config[keyPath: keyPath] = $0 }
        )
    }
}

private extension Character {
    var isHexDigit: Bool {
        isASCII && (isNumber || ("a"..."f" ~= self) || ("A"..."F" ~= self))
    }
}

/// Простейший FlowLayout (доступен в SwiftUI с macOS 13.0). Используется для
/// чипов-пресетов DoH: автоматически переносит на новую строку.
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0

        for sub in subviews {
            let size = sub.sizeThatFits(.unspecified)
            if x + size.width > maxWidth {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
        return CGSize(width: maxWidth, height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let maxWidth = bounds.width
        var x: CGFloat = bounds.minX
        var y: CGFloat = bounds.minY
        var rowHeight: CGFloat = 0

        for sub in subviews {
            let size = sub.sizeThatFits(.unspecified)
            if x + size.width > bounds.maxX {
                x = bounds.minX
                y += rowHeight + spacing
                rowHeight = 0
            }
            sub.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
        _ = maxWidth // unused, but kept for symmetry
    }
}
