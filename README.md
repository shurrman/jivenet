# jivenet

Свой DNS-over-HTTPS туннель: интернет идёт через DNS-запросы к собственному
authoritative-серверу. Помогает там, где обычный TCP/UDP подрезают, а DNS — нет.

Состоит из двух компонентов:

- **`server/`** — серверное приложение для Debian 12/13 (Ubuntu 22.04+).
  Принимает DNS-запросы, расшифровывает (Noise_NK + KCP + smux), выпускает
  трафик в интернет через локальный 3proxy (HTTP+SOCKS5 auto-detect на одном порту).
- **`android/`** — клиент для Android.
  Два режима, переключаются в Настройках:
  - **VPN** (по умолчанию) — захватывает весь трафик через `VpnService` +
    sing-box. Один тап → разрешение Android → все приложения идут через туннель.
  - **Proxy** — локальный HTTP+SOCKS5 на `127.0.0.1:1080` для per-app сценариев
    (Firefox+FoxyProxy, Telegram). Требует ручной настройки в приложениях
    или SocksDroid.

```
Android apps ─▶ TUN ─▶ sing-box (libbox) ─▶ SOCKS5 ─▶ dnstt-client ─▶ DoH/UDP DNS ─▶ ваш authoritative NS ─▶ dnstt-server ─▶ 3proxy ─▶ Интернет
                       │
                       ├─ DNS перехват (fakeip) ─ резолвинг внутри туннеля, нет DNS-leak
                       └─ NetworkCallback на смену Wi-Fi ↔ Cellular ─ auto-reconnect
```

Под капотом:
- туннель — [dnstt](https://www.bamsoftware.com/software/dnstt/)
  (Noise_NK + KCP + smux поверх DoH/UDP-DNS),
- userspace network stack клиента — [sing-box](https://sing-box.sagernet.org/)
  через `experimental/libbox` (gomobile bind),
- сервер — 3proxy в `auto`-режиме (HTTP/SOCKS5 на одном порту 1080).

Подробнее — [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).
Пересборка из исходников — [`docs/BUILDING.md`](docs/BUILDING.md).

## Что нового в v0.9.x

| Версия | Главное |
|---|---|
| **v0.9.4** | DoH-приоритеты + сторожевой таймер. Cellular DNS оператора подхватывается автоматически как primary, `https://1.1.1.1/dns-query` (или другой из настроек) — fallback. При 15с тишины на текущей DoH watchdog переключается на следующую в кольце. На мобильной сети больше не надо вручную тыкать «Авто UDP». |
| v0.9.3 | Стабилизированный auto-reconnect (фикс restart-loop'а на validation-cycles). |
| v0.9.2 | Реальная статистика трафика на главном экране (через clash-api sing-box). |
| v0.9.1 | Рабочая sing-box-конфигурация (новый формат DNS 1.14). |
| v0.9.0 | sing-box заменил tun2socks: фикс DNS-leak, FakeIP, auto-reconnect при смене сети. |
| v0.2.0 | Initial — proxy-режим, debug APK. |

Полный список — [`CHANGELOG.md`](CHANGELOG.md).

## Быстрый старт

### 1. Подготовить

- VPS с **Debian 12/13** или **Ubuntu 22.04+**, публичный IPv4, sudo.
- Домен с возможностью прописать NS-записи (поддомен тоже годится).
- Android-телефон **arm64-v8a** (≈все устройства 2015+), Android 8.0+.

### 2. Развернуть сервер

```bash
git clone https://github.com/shurrman/jivenet.git
cd jivenet/server
sudo ./install.sh <tunnel-domain> [public-ip]
```

Например:

```bash
sudo ./install.sh tn.example.com 203.0.113.7
```

Скрипт ставит Go+dnstt, 3proxy, systemd-юниты, iptables REDIRECT `:53→:5300`,
и в конце печатает готовые DNS-записи и QR-код с конфигом для Android.

### 3. Настроить DNS у регистратора

Для домена `tn.example.com` → публичный IP `203.0.113.7`:

| Запись | Тип | Значение |
|---|---|---|
| `ns1.tn` | A | `203.0.113.7` |
| `ns2.tn` | A | `203.0.113.7` |
| `tn` | NS | `ns1.tn.example.com.` |
| `tn` | NS | `ns2.tn.example.com.` |

Подробнее — [`server/docs/DNS-SETUP.md`](server/docs/DNS-SETUP.md).

Проверка через 5–30 минут:

```bash
dig +trace tn.example.com NS
dig TXT random123.tn.example.com @1.1.1.1
sudo journalctl -u dnstt-server -f   # смотреть как запросы доходят
```

### 4. Раздача нового пользователю — одной командой

```bash
./scripts/onboard.sh                         # локальный bundle в onboarding/
./scripts/onboard.sh --release v0.9.4        # + публикация в GitHub Release
```

Скрипт автоматически:

- собирает APK (`gradlew assembleDebug`, фиксированно из `build/outputs/`,
  с проверкой `versionName` против `build.gradle.kts`),
- запрашивает свежий конфиг с сервера по SSH,
- генерирует QR-код,
- кладёт `INSTALL.txt` с инструкцией,
- (с `--release`) загружает APK + QR + INSTALL в GitHub Release.

В `onboarding/` появятся:
- **`jivenet.apk`** (~41 МБ, debug-подпись, arm64-v8a),
- **`jivenet-config.png`** — QR с конфигом, сканируется в приложении
  «Настройки → QR»,
- **`jivenet-config.json`** — fallback если QR не сканируется,
- **`INSTALL.txt`** — пошаговая инструкция,
- **`jivenet-onboarding.zip`** — всё одним архивом для мессенджера.

С `--release` получателю достаточно одной ссылки на GitHub Release —
он скачает APK браузером без ограничений мессенджеров.

### 5. Установить на устройство

```bash
adb install onboarding/jivenet.apk
# или: переслать APK на телефон, открыть, разрешить «Установка из неизвестных источников»
```

### 6. Настроить и подключиться

В приложении «jivenet» → ⚙ (Настройки):
- **Вариант 1 — QR**: «Сканировать QR» → отсканировать
  `jivenet-config.png` (можно показать с экрана другого устройства).
- **Вариант 2 — вручную**: ввести `Tunnel domain`, `Public key` (64 hex),
  `DoH resolver` (по умолчанию `https://1.1.1.1/dns-query`).

«Сохранить» → главный экран → «Подключить» → Android спросит разрешение
«Разрешить VPN-соединение» → согласиться. В статус-баре появится 🔒 ключ.

Откройте https://ifconfig.co — должен показать IP вашего VPS.

## На мобильной сети (LTE/5G/EDGE)

Российские операторы блокируют публичные DoH по IP. Раньше требовалось
вручную выбирать в настройках чип «Авто UDP» (DNS оператора).

**С v0.9.4 этого не нужно** — приложение само определяет DNS оператора
активной SIM через `ConnectivityManager` и держит его в качестве primary
DoH. Когда телефон на мобильной — туннель идёт через operator-DNS
(оператор пропускает запросы к собственному резолверу через DPI).
Когда переходит на Wi-Fi — сторожевой таймер замечает что cellular DNS
больше не маршрутизируется и переключается на fallback (`https://1.1.1.1/dns-query`)
автоматически. Юзеру делать ничего не надо.

Если хочется вручную поменять fallback — Настройки → DoH resolver,
выбрать пресет (Cloudflare / Google / Quad9 / OpenDNS / ...) или вписать свой.

## Команды обслуживания

### На сервере (`server/`)

| Команда | Что делает |
|---|---|
| `make status` | Статус юнитов, сокетов, NAT, последние логи |
| `make logs` | `journalctl -u dnstt-server -f` |
| `make qr` | Вывести JSON-конфиг + QR для нового пользователя |
| `make qr ARGS="--png /tmp/q.png"` | Сохранить QR в PNG |
| `make test-dns` | Проверить что DNS-делегирование работает |
| `make restart` | Перезапустить `dnstt-server` |
| `sudo bash scripts/print-qr.sh --json-only` | Только JSON без QR |
| `sudo bash scripts/gen-keys.sh` | Перегенерировать ключи (ротация) |
| `sudo bash scripts/setup-iptables.sh` | Переустановить NAT-правило |

### Ротация ключей сервера

```bash
sudo rm /etc/dnstt/server.key /etc/dnstt/server.pub
sudo bash /path/to/jivenet/server/scripts/gen-keys.sh
sudo systemctl restart dnstt-server
# Раздать новый pubkey клиентам через onboard.sh --release
```

### Удаление сервера

```bash
sudo bash /path/to/jivenet/server/uninstall.sh
```

## Проверка и диагностика

- **Статистика трафика** прямо на главном экране приложения: uptime,
  bytes ↑/↓, активных/пиковых соединений (берётся из clash-api sing-box).
- **Логи через adb**:
  ```bash
  adb logcat -d | grep -aE 'TunnelService:|DohWatchdog:|DohChain:|SingboxBridge:|DnsttBridge: dnstt:'
  ```
  Watchdog пишет когда переключает DoH (`stall ...ms`, `60с без download`).
  dnstt-client логирует `begin/end stream` — видно реальные потоки.

## Известные ограничения

- **Только arm64-v8a.** Для armeabi-v7a и x86_64 нужен NDK + кросс-компиляция
  dnstt-client под нужный ABI и пересборка sing-box AAR. Решается, но не
  настроено в `scripts/`.
- **Debug-подпись APK.** Для Play Store нужна release-подпись и keystore.
  Как сделать — [`docs/BUILDING.md`](docs/BUILDING.md).
- **Throughput DNS-туннеля** — типично 100–400 КБ/с. Веб-сёрфинг
  комфортный, видео 4K не тянет (физика DoH-overhead, не наш баг).
  Подробности в [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).
- **Один пользователь на сервер** в текущей конфигурации (один pubkey).
  Multi-tenant не реализован — для нескольких юзеров проще поднять
  несколько серверов.

## Troubleshooting

- **Сервер не отвечает** → [`server/docs/TROUBLESHOOTING.md`](server/docs/TROUBLESHOOTING.md).
- **DNS не резолвится** → [`server/docs/DNS-SETUP.md`](server/docs/DNS-SETUP.md) → секция «Если делегирование не работает».
- **APK не устанавливается** → проверить процессор:
  `adb shell getprop ro.product.cpu.abi` должен дать `arm64-v8a`.
- **«Подключено», но трафик 0/0** на главном экране после ~30с —
  watchdog сейчас переключит DoH на fallback. Если и через минуту
  нули — проверить настройку DoH (Настройки → DoH resolver), убедиться
  что там НЕ внутренний IP оператора (`10.x.x.x`) который недоступен
  с Wi-Fi.
- **Подвис `opening stream: timeout` в логе dnstt** — на сервере
  перезапускался `dnstt-server`, сессия клиента устарела. Отключить-Подключить
  на телефоне.
- **Proxy-режим: браузер не ходит** — проверить хост `127.0.0.1`,
  порт `1080`, тип SOCKS5 **или** HTTP — оба работают (3proxy auto-detect).

## Структура репозитория

```
jivenet/
├── README.md                      # этот файл
├── CHANGELOG.md
├── CLAUDE.md                      # workflow / project memory для Claude Code
├── docs/
│   ├── ARCHITECTURE.md            # как устроено
│   ├── BUILDING.md                # как пересобрать с нуля
│   └── ANDROID-PROXY-SETUP.md     # ручная настройка прокси на разных прошивках
├── scripts/
│   └── onboard.sh                 # release-инструмент: APK + QR + INSTALL → GitHub Release
├── server/
│   ├── install.sh
│   ├── uninstall.sh
│   ├── Makefile
│   ├── systemd/                   # юниты
│   ├── etc/                       # 3proxy.cfg
│   ├── scripts/
│   │   ├── gen-keys.sh
│   │   ├── setup-iptables.sh
│   │   ├── status.sh
│   │   └── print-qr.sh
│   └── docs/
│       ├── DNS-SETUP.md
│       └── TROUBLESHOOTING.md
└── android/
    ├── app/
    │   ├── build.gradle.kts        # versionCode / versionName живут тут
    │   ├── libs/
    │   │   └── libbox.aar          # sing-box gomobile-bind (~14 МБ)
    │   └── src/main/
    │       ├── kotlin/net/jivenet/client/
    │       │   ├── MainActivity.kt
    │       │   ├── TunnelService.kt    # VpnService + watchdog wiring
    │       │   ├── DohWatchdog.kt      # primary↔fallback failover (v0.9.4)
    │       │   ├── DnsttBridge.kt      # subprocess wrapper для libdnstt_client.so
    │       │   ├── SingboxBridge.kt    # обёртка libbox
    │       │   ├── SingboxPlatform.kt  # PlatformInterface: TUN+protect+CA
    │       │   ├── SingboxConfig.kt    # генератор sing-box JSON
    │       │   ├── SingboxStats.kt     # clash-api поллер
    │       │   └── …
    │       ├── jniLibs/arm64-v8a/
    │       │   └── libdnstt_client.so  # cross-compiled Go
    │       └── res/
    └── scripts/
        ├── build-binaries.sh           # dnstt-client → jniLibs/
        ├── build-singbox-aar.sh        # sing-box → app/libs/libbox.aar
        └── build-aar.sh                # legacy (tun2socks)
```

## Лицензия

- dnstt — CC0 (David Fifield).
- sing-box — GPL-3.0.
- Код этого проекта — GPL-3.0 (наследует sing-box).
