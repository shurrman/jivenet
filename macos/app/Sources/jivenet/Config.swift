import Foundation
import Combine

/// Конфиг туннеля — то же, что Android-овский TunnelConfig.kt:
///   domain  — твой домен dnstt-туннеля
///   pubkey  — 64 hex-символа, публичный ключ сервера
///   doh     — DoH/UDP/DoT URL (https://, udp://, dot://)
///   port    — локальный порт SOCKS5 (1024–65535)
///
/// Хранится в UserDefaults — встроенное persistence-API macOS, файлы летят в
/// `~/Library/Preferences/net.jivenet.client.plist`.
struct TunnelConfig: Codable, Equatable {
    var domain: String = ""
    var pubkey: String = ""
    var doh: String = "https://1.1.1.1/dns-query"
    var port: Int = 1080

    /// Готов ли конфиг к подключению (все поля валидны).
    var isComplete: Bool {
        domain.contains(".")
            && pubkey.count == 64
            && pubkey.allSatisfy { $0.isHexDigit }
            && (doh.hasPrefix("https://") || doh.hasPrefix("udp://") || doh.hasPrefix("dot://"))
            && (1024...65535).contains(port)
    }
}

private extension Character {
    var isHexDigit: Bool {
        isASCII && (isNumber || ("a"..."f" ~= self) || ("A"..."F" ~= self))
    }
}

/// ObservableObject-обёртка над TunnelConfig — UI подписывается через @StateObject /
/// @EnvironmentObject и автоматически обновляется при изменениях.
final class ConfigStore: ObservableObject {
    private let key = "net.jivenet.client.config.v1"
    private let defaults: UserDefaults

    @Published var config: TunnelConfig {
        didSet { save() }
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.config = ConfigStore.load(from: defaults, key: key) ?? TunnelConfig()
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(config) else { return }
        defaults.set(data, forKey: key)
    }

    private static func load(from defaults: UserDefaults, key: String) -> TunnelConfig? {
        guard let data = defaults.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(TunnelConfig.self, from: data)
    }

    /// Импорт из QR-JSON или из строки, скопированной с сервера.
    /// Формат тот же что в Android — `{"domain":"...","pubkey":"...","doh":"...","port":1080}`.
    func importJSON(_ text: String) -> Bool {
        guard let data = text.data(using: .utf8),
              let qr = try? JSONDecoder().decode(QRPayload.self, from: data)
        else { return false }
        var c = config
        if let d = qr.domain { c.domain = d }
        if let p = qr.pubkey { c.pubkey = p }
        if let d = qr.doh { c.doh = d }
        if let p = qr.port { c.port = p }
        self.config = c
        return true
    }

    private struct QRPayload: Codable {
        var domain: String?
        var pubkey: String?
        var doh: String?
        var port: Int?
        var mode: String?       // игнорируем — на macOS только proxy
        var localPort: Int?     // legacy android key
        // CodingKeys, чтобы поймать оба варианта имён:
        enum CodingKeys: String, CodingKey {
            case domain, pubkey, doh, port, mode, localPort
        }
    }
}
