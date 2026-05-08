# jivenet — Android-клиент

Клиентское приложение для `jivenet` DNS-over-HTTPS туннеля.

## Быстрый запуск (локальная разработка)

```bash
# 1. Клонировать dnstt-исходники
git clone --depth=1 https://github.com/net2share/dnstt.git dnstt-src

# 2. Собрать нативный бинарник под arm64
./scripts/build-binaries.sh
# → app/src/main/jniLibs/arm64-v8a/libdnstt_client.so

# 3. Поставить local.properties (разово)
cat > local.properties <<EOF
sdk.dir=$HOME/Library/Android/sdk
EOF

# 4. Собрать APK
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# 5. Поставить на телефон
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Подробнее

- Пользовательское руководство: [`../README.md`](../README.md).
- Архитектура (зачем subprocess, как работает DNS-туннель): [`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md).
- Сборка, другие ABI, VPN-режим, release-подпись: [`../docs/BUILDING.md`](../docs/BUILDING.md).

## Структура модуля

```
android/
├── app/                                           # :app Gradle-модуль
│   ├── build.gradle.kts                           # abiFilters=arm64-v8a, зависимости
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── jniLibs/arm64-v8a/                     # заполняется scripts/build-binaries.sh
│       │   └── libdnstt_client.so                 # нативный dnstt-client
│       ├── kotlin/net/jivenet/client/
│       │   ├── MainActivity.kt                    # главный экран + статус
│       │   ├── MainViewModel.kt                   # Flow конфига и статистики
│       │   ├── SettingsActivity.kt                # форма настроек + QR-сканер
│       │   ├── DnsttBridge.kt                     # ProcessBuilder-обёртка
│       │   ├── ProxyService.kt                    # foreground для Proxy-режима
│       │   ├── TunnelService.kt                   # заглушка VpnService (MVP)
│       │   ├── Notifications.kt
│       │   ├── config/TunnelConfig.kt             # DataStore + JSON-парсер
│       │   └── ui/{QrScanner,StatsCard}.kt
│       └── res/
├── dnstt-src/                                     # клон dnstt (в .gitignore)
├── scripts/
│   └── build-binaries.sh                          # кросс-компиляция Go
├── build.gradle.kts                               # root-проект, плагины
├── settings.gradle.kts
├── gradle.properties                              # JAVA_HOME pin
├── gradle/wrapper/                                # gradle-wrapper
├── gradlew
└── local.properties                               # sdk.dir (НЕ коммитить)
```

## Точки правок

| Что меняете | Где |
|---|---|
| Добавить новое поле в конфиг | `config/TunnelConfig.kt` → затем `DnsttBridge.buildCommand()` |
| Новый флаг `dnstt-client` | `DnsttBridge.kt` → метод `buildCommand()` |
| UI главного экрана | `MainActivity.kt` |
| UI настроек | `SettingsActivity.kt` |
| Формат JSON QR | `config/TunnelConfig.kt` → `fromJson()` и сервер `scripts/print-qr.sh` |
| Proxy vs VPN поведение | `ProxyService.kt` / `TunnelService.kt` |
| Версия dnstt | `dnstt-src/` → `git pull` → `./scripts/build-binaries.sh` |

## Ограничения MVP

- Только arm64-v8a. Для armeabi-v7a и x86_64 см. [`../docs/BUILDING.md`](../docs/BUILDING.md#добавить-abi-armeabi-v7a-и-x86_64).
- VPN-режим заглушен (см. план добавления в [`BUILDING.md`](../docs/BUILDING.md#добавить-vpn-режим-vpnservice)).
- Статистика байт/соединений не парсится из stderr dnstt-client.
- Debug-подпись APK (шаги под release — в `BUILDING.md`).
