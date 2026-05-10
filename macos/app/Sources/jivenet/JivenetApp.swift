import SwiftUI

/// Entry-point — SwiftUI App с MenuBarExtra. Тру-меню-бар-приложение:
/// без иконки в Dock, без главного окна, всё через попап из меню-бара.
/// `LSUIElement = true` в Info.plist дополняет это (см. build-app.sh).
@main
struct JivenetApp: App {

    @StateObject private var configStore = ConfigStore()
    @StateObject private var dnstt = DnsttManager()

    var body: some Scene {
        MenuBarExtra {
            ContentView()
                .environmentObject(configStore)
                .environmentObject(dnstt)
                .frame(width: 320)
        } label: {
            // Иконка в меню-баре. Меняем в зависимости от состояния: точка
            // пустая когда отключено, заполненная когда подключено.
            // SF Symbols 5+ — `circle` / `circle.fill`.
            switch dnstt.state {
            case .running:
                Image(systemName: "circle.fill")
            case .starting, .stopping:
                Image(systemName: "circle.dotted")
            default:
                Image(systemName: "circle")
            }
        }
        .menuBarExtraStyle(.window)

        // Settings-окно — открывается через cmd+, или из ContentView.
        Settings {
            SettingsView()
                .environmentObject(configStore)
                .frame(width: 480, height: 460)
        }
    }
}
