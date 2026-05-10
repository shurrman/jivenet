// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "jivenet",
    platforms: [
        // macOS 14 (Sonoma) — это разумный минимум:
        //   * `MenuBarExtra` появился в 13, но `onChange(of:initial:_:)`
        //     с двумя аргументами — только в 14.
        //   * macOS 13 даже Apple уже не поддерживает security-update'ами
        //     с октября 2025.
        // На pre-14 пользователю покажется alert «Поддерживаемая версия
        // macOS — 14+».
        .macOS(.v14),
    ],
    targets: [
        .executableTarget(
            name: "jivenet",
            path: "Sources/jivenet"
            // Resources подкладываются вручную скриптом build-app.sh при упаковке
            // .app — SwiftPM не умеет складывать произвольный исполняемый бинарник
            // (dnstt-client) внутрь bundle'а так, чтобы потом
            // `Bundle.main.url(forResource:withExtension:)` его нашёл.
        )
    ]
)
