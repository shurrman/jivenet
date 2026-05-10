# jivenet — macOS-клиент

SwiftUI menubar-приложение для `jivenet` DNS-over-HTTPS туннеля.
Первая версия — v0.9.5.

## Возможности первой версии

- **Proxy-режим** (только): локальный SOCKS5 на `127.0.0.1:1080` (порт
  настраивается). Браузер / системные настройки указываете на этот
  адрес — трафик идёт через ваш jivenet-сервер.
- **Menubar-only**: иконка-точка в строке меню, без иконки в Dock и без
  главного окна. Клик — попап со статусом, кнопкой Подключить/Отключить
  и базовой статистикой (uptime, активные стримы).
- **Settings**: домен, public key, DoH-резолвер (с пресетами
  Cloudflare/Google/Quad9/...), порт.
- **Импорт конфига**: вставить JSON из `make qr --json-only` на
  сервере, кнопка «Из буфера» считает из clipboard.
- Universal binary: arm64 (Apple Silicon) + x86_64 (Intel).

## Чего нет в первой версии

- **VPN-режим** (захват всего трафика через утун) — требует Apple
  Developer Program ($99/год) + NetworkExtension entitlement +
  notarization. Запланирован в v0.9.6+.
- **QR-сканер** через камеру — на macOS реже нужен (юзер всё равно за
  ноутом, проще скопировать JSON). На Android есть.
- **Watchdog с failover**ом между cellular и fallback DoH — на macOS
  обычно нет cellular-сети, и операторских DPI-блокировок нет; одного
  выбранного DoH хватает. На Android актуально из-за мобильных
  операторов.
- **Статистика байт**: dnstt-client не экспортирует bytes-counters
  через stdout, а sing-box+clash-api на macOS пока не используем
  (proxy-mode не требует TUN). Показываем только счётчики стримов и
  uptime — это даёт сигнал «работает / не работает».

## Установка

### Из релиза

1. Скачать `jivenet-0.9.5.dmg` со
   [страницы релиза](https://github.com/shurrman/jivenet/releases/latest).
2. Открыть `.dmg` → перетащить `jivenet.app` в `/Applications/`.
3. Первый запуск: правый клик на `jivenet.app` → **Open** →
   подтвердить (приложение не нотаризировано Apple, обычный двойной
   клик заблокирован Gatekeeper'ом). После первого Open Gatekeeper
   запоминает разрешение.

### Сборка из исходников

```bash
cd macos
./scripts/build-dnstt-darwin.sh   # cross-compile Go-бинарник (universal)
./scripts/build-app.sh release    # → build/jivenet.app
./scripts/build-dmg.sh            # → build/jivenet-X.Y.Z.dmg
```

Требуется:
- Xcode 15+ (Swift 5.9+)
- Go 1.21+ (`brew install go`)
- macOS 14+ (Sonoma) для запуска (минимум API)

## Использование

1. Запустить `jivenet.app` — в строке меню появится точка-иконка.
2. Клик по иконке → **Настройки…** → ввести **домен** и **public key**
   с твоего jivenet-сервера. DoH по умолчанию `https://1.1.1.1/dns-query` —
   подходит большинству.
3. **Подключить**. Иконка в меню-баре заполнится (зелёная точка).
4. Настроить системный SOCKS5 прокси:
   - **Системные настройки → Сеть → твоя сеть → Подробнее → Прокси →
     Прокси SOCKS** → `127.0.0.1:1080`. Применить.
   - Или per-app: в Firefox/Chrome через расширение FoxyProxy,
     Chrome `--proxy-server="socks5://127.0.0.1:1080"`,
     Telegram → Настройки → Дополнительно → Прокси.
5. Проверить: открыть https://ifconfig.co — должен показать IP сервера.

## Структура проекта

```
macos/
├── README.md
├── app/                          # Swift Package (executable)
│   ├── Package.swift             # macOS 14+, target `jivenet`
│   ├── Sources/jivenet/
│   │   ├── JivenetApp.swift      # @main, MenuBarExtra scene
│   │   ├── ContentView.swift     # попап из меню-бара
│   │   ├── SettingsView.swift    # форма настроек
│   │   ├── DnsttManager.swift    # subprocess, парсер stderr
│   │   └── Config.swift          # TunnelConfig + ConfigStore (UserDefaults)
│   └── Resources/
│       └── dnstt-client          # universal arm64+x86_64 (gitignored)
├── scripts/
│   ├── build-dnstt-darwin.sh     # cross-compile Go → Resources/
│   ├── build-app.sh              # swift build + bundle .app
│   └── build-dmg.sh              # упаковка в .dmg
└── build/                        # output (gitignored)
    ├── jivenet.app
    └── jivenet-X.Y.Z.dmg
```

## Лицензия

GPL-3.0 (наследует от sing-box и общих файлов проекта).
dnstt — CC0 (David Fifield), идёт внутри bundle'а как cross-compiled Go-бинарник.
