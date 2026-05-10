# jivenet — Android-клиент

Клиентское приложение для `jivenet` DNS-over-HTTPS туннеля.

С v0.9.0 — sing-box userspace stack (libbox), полноценный VPN-режим
(`VpnService`), DNS-leak-fix через DoH-резолвер внутри туннеля.
С v0.9.2 — статистика трафика на главном экране через clash-api.
С v0.9.3 — стабильный auto-reconnect при смене сети (Wi-Fi ↔ Cellular).
С v0.9.4 — failover между cellular operator-DNS и пользовательской DoH.

## Быстрый запуск (локальная разработка)

```bash
# 1. Подготовить dnstt-исходники (если ещё не клонированы)
git clone --depth=1 https://github.com/net2share/dnstt.git dnstt-src

# 2. Собрать нативный бинарник dnstt-client под arm64
./scripts/build-binaries.sh
# → app/src/main/jniLibs/arm64-v8a/libdnstt_client.so

# 3. Собрать sing-box AAR через gomobile (один раз, ~1 мин)
./scripts/build-singbox-aar.sh
# → app/libs/libbox.aar (~14 МБ)

# 4. local.properties (разово)
cat > local.properties <<EOF
sdk.dir=$HOME/Library/Android/sdk
EOF

# 5. Собрать APK
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk (~41 МБ)

# 6. Поставить на телефон
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Для release-релиза с правильным workflow (CHANGELOG → bump version → tag →
GitHub Release с APK/QR/INSTALL) использовать `../scripts/onboard.sh --release vX.Y.Z`.
Подробности — [`../CLAUDE.md`](../CLAUDE.md).

## Подробнее

- Пользовательское руководство: [`../README.md`](../README.md).
- Архитектура: [`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md).
- Сборка, другие ABI, release-подпись: [`../docs/BUILDING.md`](../docs/BUILDING.md).

## Структура модуля

```
android/
├── app/                                           # :app Gradle-модуль
│   ├── build.gradle.kts                           # abiFilters=arm64-v8a, deps, версия
│   ├── proguard-rules.pro
│   ├── libs/
│   │   └── libbox.aar                             # sing-box gomobile-bind (build-singbox-aar.sh)
│   └── src/main/
│       ├── AndroidManifest.xml                    # BIND_VPN_SERVICE + INTERNET + FOREGROUND_SERVICE
│       ├── jniLibs/arm64-v8a/                     # build-binaries.sh
│       │   └── libdnstt_client.so                 # нативный dnstt-client (Go cross-compile)
│       ├── kotlin/net/jivenet/client/
│       │   ├── MainActivity.kt                    # главный экран, ON/OFF, статус
│       │   ├── MainViewModel.kt                   # Flow конфига + 1-сек polling статистики
│       │   ├── SettingsActivity.kt                # форма + QR-сканер
│       │   ├── TunnelService.kt                   # VpnService: mutex + watchdog + NetworkCallback
│       │   ├── ProxyService.kt                    # foreground для Proxy-режима (без VpnService)
│       │   ├── DnsttBridge.kt                     # ProcessBuilder-обёртка над libdnstt_client.so
│       │   ├── DohWatchdog.kt                     # primary↔fallback failover (v0.9.4)
│       │   ├── SingboxBridge.kt                   # обёртка io.nekohasekai.libbox
│       │   ├── SingboxPlatform.kt                 # PlatformInterface: openTun + protect + CA
│       │   ├── SingboxConfig.kt                   # генератор sing-box JSON (TUN/DNS/SOCKS5/clash-api)
│       │   ├── SingboxStats.kt                    # HTTP-поллер clash-api 127.0.0.1:9090
│       │   ├── SystemDns.kt                       # автодетект DNS оператора активной SIM
│       │   ├── Notifications.kt
│       │   ├── config/TunnelConfig.kt             # DataStore + JSON-парсер для QR
│       │   └── ui/{QrScanner,StatsCard}.kt
│       └── res/
├── dnstt-src/                                     # клон dnstt (gitignored)
├── sing-box-src/                                  # клон sing-box (gitignored)
├── scripts/
│   ├── build-binaries.sh                          # cross-compile Go → libdnstt_client.so
│   ├── build-singbox-aar.sh                       # gomobile bind sing-box → libbox.aar
│   └── build-aar.sh                               # legacy (tun2socks, не используется в v0.9.x)
├── build.gradle.kts                               # root project, плагины
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/
├── gradlew
└── local.properties                               # sdk.dir (НЕ коммитить)
```

## Поток данных в VPN-режиме

```
Android apps ─▶ TUN(172.19.0.1/30) ─▶ sing-box (libbox)
                                          │
                                          ├─ DNS UDP hijack
                                          │      ├─ A/AAAA → fakeip из 198.18.0.0/15
                                          │      └─ другие → DoH "doh-remote" через outbound proxy
                                          │
                                          └─ TCP/UDP → SOCKS5 outbound "proxy" → 127.0.0.1:cfg.localPort
                                                                                       │
                                                                                       ▼
                                                                              dnstt-client subprocess
                                                                              (excluded from VPN by package)
                                                                                       │
                                                                                       ▼ uTLS+TLS
                                                                              cfg.doh (https/udp/dot)
                                                                                       │
                                                                                       ▼
                                                                              ваш authoritative NS → dnstt-server → 3proxy → интернет
```

`exclude_package` в sing-box-config исключает наш собственный UID из TUN —
иначе dnstt-client (subprocess) и sing-box DoH-probe попадали бы в петлю
через свой же туннель.

## Точки правок

| Что меняете | Где |
|---|---|
| Новое поле в конфиге | `config/TunnelConfig.kt` → парсер JSON и форма Settings |
| Новый флаг `dnstt-client` CLI | `DnsttBridge.kt` → `buildCommand()` |
| UI главного экрана | `MainActivity.kt` |
| UI настроек | `SettingsActivity.kt` |
| Формат JSON QR | `config/TunnelConfig.kt → fromJson()` + сервер `scripts/print-qr.sh` |
| Proxy vs VPN поведение | `ProxyService.kt` / `TunnelService.kt` |
| sing-box config | `SingboxConfig.kt` (DNS/inbounds/outbounds/route) |
| Watchdog логика и пороги | `DohWatchdog.kt` (companion-константы наверху) |
| Версия dnstt | `dnstt-src/` → `git pull` → `./scripts/build-binaries.sh` |
| Версия sing-box | `sing-box-src/` → `git pull` → `./scripts/build-singbox-aar.sh` |

## Ключевые пороги (живут в `DohWatchdog.kt`)

| Константа | Значение | Зачем |
|---|---|---|
| `INITIAL_DEADLINE_MS` | 60 000 | Холодный KCP+TLS+smux handshake может занять до ~40с. 30с давало false-positive ping-pong. |
| `STALL_THRESHOLD_MS` | 15 000 | SLA «нет ответа 15 секунд» из ТЗ — после этого свитч на следующий DoH. |
| `POLL_INTERVAL_MS` | 5 000 | Частота опроса clash-api. |
| `PROBE_TIMEOUT_MS` | 5 000 | TCP-connect к Cloudflare на cold-start. 3с давало false-negative. |

## Ограничения

- **Только arm64-v8a.** Для armeabi-v7a и x86_64 см. [`../docs/BUILDING.md`](../docs/BUILDING.md#добавить-abi-armeabi-v7a-и-x86_64).
- **Debug-подпись APK.** Release-keystore — в [`../docs/BUILDING.md`](../docs/BUILDING.md).
- **Throughput** ~100–400 КБ/с (физика DoH-overhead).
