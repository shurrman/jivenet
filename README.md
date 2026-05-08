# jivenet

Собственная сетевая инфраструктура из двух компонентов, связанных DNS-over-HTTPS туннелем:

- **`server/`** — серверное приложение для Debian 12/13 (Ubuntu тоже подойдёт).
  Принимает DNS-запросы с инкапсулированным трафиком, расшифровывает и выпускает в интернет через локальный прокси (3proxy в auto-режиме: HTTP + SOCKS5 на одном порту).
- **`android/`** — клиентское приложение для Android (APK).
  Два режима:
  - **VPN** (рекомендуется) — захватывает весь трафик устройства через системный `VpnService` + встроенный tun2socks. Один тап — Android спрашивает разрешение — все приложения идут через туннель. Работает на Wi-Fi и мобильной сети без adb и сторонних приложений.
  - **Proxy** — локальный HTTP+SOCKS5 на `127.0.0.1:1080`, для тех кому нужен per-app или Firefox+FoxyProxy. На мобильной сети требует SocksDroid или adb.

Движок туннеля — [dnstt](https://www.bamsoftware.com/software/dnstt/) (Noise_NK + KCP + smux поверх DoH). Серверная сторона использует его как бинарник, клиентская — как встроенный subprocess внутри APK.

```
Android ─▶ DoH resolver (Cloudflare/Google/…) ─▶ ваш authoritative NS ─▶ dnstt-server ─▶ 3proxy (HTTP+SOCKS5) ─▶ Интернет
```

Подробнее про архитектуру — [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).
Про пересборку из исходников — [`docs/BUILDING.md`](docs/BUILDING.md).

## Быстрый старт

### 1. Нужно подготовить

- VPS с **Debian 12/13** или **Ubuntu 22.04+**, публичным IPv4, sudo-доступом.
- Домен, у которого можно настраивать NS-записи (поддомен тоже годится).
- Android-телефон с **arm64-v8a** процессором (практически все устройства 2015+).

### 2. Развернуть сервер

```bash
git clone <this-repo> jivenet
cd jivenet/server
sudo ./install.sh <tunnel-domain> [public-ip]
```

Например:

```bash
sudo ./install.sh tn.example.com 203.0.113.7
```

Скрипт ставит Go+dnstt, Dante SOCKS5, systemd-юниты, iptables REDIRECT `:53→:5300` и в конце печатает готовые DNS-записи и QR-код с конфигом.

### 3. Настроить DNS у регистратора

Для домена `tn.example.com` → публичный IP `203.0.113.7`:

| Запись | Тип | Значение |
|---|---|---|
| `ns1.tn` | A | `203.0.113.7` |
| `ns2.tn` | A | `203.0.113.7` |
| `tn` | NS | `ns1.tn.example.com.` |
| `tn` | NS | `ns2.tn.example.com.` |

Подробнее — [`server/docs/DNS-SETUP.md`](server/docs/DNS-SETUP.md).

Проверить (через 5–30 минут):

```bash
dig +trace tn.example.com NS
dig TXT random123.tn.example.com @1.1.1.1
# В логах dnstt-server должна появиться запись:
# sudo journalctl -u dnstt-server -f
```

### 4. Раздача нового пользователю (одной командой)

```bash
./scripts/onboard.sh                         # локальный bundle в onboarding/
./scripts/onboard.sh --release v0.2.0        # + публикация в GitHub Release
```

Скрипт собирает `onboarding/`:

- **`jivenet.apk`** — APK для установки (33 МБ).
- **`jivenet-config.png`** — QR с конфигом (домен + pubkey + DoH), отсканировать в приложении в `Настройки → Сканировать QR`.
- **`jivenet-config.json`** — тот же конфиг текстом, fallback если QR не отсканивается.
- **`INSTALL.txt`** — пошаговая инструкция получателю (как поставить APK, как импортировать конфиг, что делать на мобильной сети).
- **`jivenet-onboarding.zip`** — всё одним архивом для отправки в мессенджере.

С `--release v0.2.0` (требует `gh auth login` разово) скрипт **загрузит** APK + QR + INSTALL в GitHub Release. Получателю достаточно одной ссылки `https://github.com/<вы>/jivenet/releases/tag/v0.2.0` — он скачает APK браузером без ограничений мессенджеров (Telegram режет вложения 2 ГБ, WhatsApp 100 МБ — нам хватит, но ссылка удобнее).

### 5. Установить APK на устройство

Готовый debug-APK: `/Users/aaa/projects/jivenet/jivenet-debug.apk` (25 МБ, только arm64-v8a).

Если надо пересобрать — см. [`docs/BUILDING.md`](docs/BUILDING.md).

```bash
adb install jivenet-debug.apk
# или: скинуть файл на телефон и открыть через проводник
# (в настройках Android включить «Установка из неизвестных источников»)
```

### 5. Настроить Android-клиент

**Вариант с QR-кодом:** на сервере запустить и отсканировать.

```bash
sudo bash /path/to/jivenet/server/scripts/print-qr.sh
# или через Makefile:
cd /path/to/jivenet/server && make qr
```

В приложении «jivenet» → `⚙` (настройки) → «Сканировать QR».

**Вручную:** ввести в настройках:

- Tunnel domain
- Public key (64-символьный hex)
- DoH resolver — по умолчанию `https://1.1.1.1/dns-query`

«Сохранить» → на главном экране «Подключить».

### 6. Использовать

После подключения приложение поднимает локальный **HTTP+SOCKS5** прокси на `127.0.0.1:1080` (один порт, оба протокола — 3proxy на сервере делает auto-detect по первому байту).

### Самый простой путь: VPN-режим в самом приложении

В настройках приложения — режим **VPN** (по умолчанию), нажмите «Подключить». Android спросит разрешение «Разрешить jivenet установить VPN-соединение» → согласиться. В статус-баре появится значок ключа, весь трафик идёт через туннель. Никаких WiFi-настроек, adb или SocksDroid не нужно. Работает и на Wi-Fi, и на мобильной сети.

### Альтернатива: Proxy-режим (per-app)

Если нужно туннелировать только часть приложений (Firefox/FoxyProxy, Telegram), — переключитесь на **Proxy** в настройках. Локальный HTTP+SOCKS5 на `127.0.0.1:1080`, направляйте отдельные приложения вручную.

Подробный гид по настройке прокси на разных прошивках (Samsung OneUI, MIUI, Huawei), adb-альтернатива, SocksDroid — в **[`docs/ANDROID-PROXY-SETUP.md`](docs/ANDROID-PROXY-SETUP.md)**.

### Проверка

Перейдите на `https://ifconfig.co` в браузере — должен вернуться IP вашего VPS.

### На мобильной сети (LTE/5G/EDGE)

Операторы (РФ) блокируют публичные DoH по IP. DoH по hostname dnstt-client на Android не резолвит. Рецепт:

1. В приложении → Настройки → чип **«Авто UDP»** (подставит UDP-резолвер вашего оператора).
2. Режим **VPN** — Сохранить → «Подключить» → дать разрешение Android.

В VPN-режиме никаких WiFi-настроек прокси, adb, SocksDroid делать не надо. Сервер уже настроен на `-mtu 512` для совместимости с операторскими DNS, которые обрезают EDNS0. Диагностика — [`server/docs/TROUBLESHOOTING.md`](server/docs/TROUBLESHOOTING.md) → «Мобильная сеть».

## Команды обслуживания

### На сервере

| Команда | Что делает |
|---|---|
| `make status` (в `server/`) | Показать статус systemd-юнитов, сокетов, NAT и последние логи |
| `make logs` | `journalctl -u dnstt-server -f` |
| `make qr` | Вывести JSON-конфиг и QR |
| `make qr ARGS="--png /tmp/q.png"` | Сохранить QR в PNG (для экспорта) |
| `make test-dns` | Проверить, что DNS делегирование работает |
| `make restart` | Перезапустить `dnstt-server` |
| `sudo bash scripts/print-qr.sh --json-only` | Только JSON-конфиг без QR |
| `sudo bash scripts/gen-keys.sh` | Перегенерировать ключи (ротация) |
| `sudo bash scripts/setup-iptables.sh` | Переустановить NAT-правило |

### Смена DoH resolver в конфиге для Android

DoH выбирается **только в Android-клиенте** (сервер слушает чистый DNS, для него DoH — деталь транспорта). Пресеты в UI:

- Cloudflare — `https://1.1.1.1/dns-query`
- Google — `https://dns.google/dns-query`
- Quad9 — `https://dns.quad9.net/dns-query`
- NextDNS — `https://dns.nextdns.io/`
- AdGuard — `https://dns.adguard.com/dns-query`
- + Custom (произвольный URL)

### Ротация ключей

```bash
ssh user@vps
sudo rm /etc/dnstt/server.key /etc/dnstt/server.pub
sudo bash /path/to/jivenet/server/scripts/gen-keys.sh
sudo systemctl restart dnstt-server
# Новый pubkey — заново запустить print-qr.sh, показать QR клиентам
```

### Смена домена туннеля

```bash
sudoedit /etc/dnstt/server.env   # правим TUNNEL_DOMAIN=
sudo systemctl restart dnstt-server
# Перенастроить NS-делегирование у регистратора, обновить конфиг в Android
```

### Удаление сервера

```bash
sudo bash /path/to/jivenet/server/uninstall.sh
```

## Известные ограничения MVP

- **Только Proxy-режим.** Полный VPN (`VpnService`) заглушен — для него нужен tun2socks-бинарник рядом с dnstt-client, он не успел в первый релиз. Браузер/приложения настраиваются на `127.0.0.1:1080` вручную. План добавления VPN-режима — в [`docs/BUILDING.md`](docs/BUILDING.md).
- **Только arm64-v8a.** Это 95%+ Android-устройств 2020+, но для armeabi-v7a и x86_64 нужен NDK и отдельная кросс-компиляция.
- **Debug-подпись APK.** Не подходит для Play Store. Как сделать release-подпись — в `docs/BUILDING.md`.
- **Статистика байт/соединений = 0.** dnstt-client не экспортирует эти метрики через stdout. `connected/uptime` работает по состоянию subprocess.
- **Переподключение при смене сети** (Wi-Fi ↔ LTE) не автоматизировано — надо нажать «Отключить» / «Подключить».
- **Throughput DNS-туннеля** — типично 100–400 КБ/с. Веб-сёрфинг комфортный, видео 4K не тянет (см. `docs/ARCHITECTURE.md`).

## Troubleshooting

- Сервер не отвечает → [`server/docs/TROUBLESHOOTING.md`](server/docs/TROUBLESHOOTING.md).
- DNS не резолвится → [`server/docs/DNS-SETUP.md`](server/docs/DNS-SETUP.md) → секция «Если делегирование не работает».
- APK не устанавливается → проверьте, что процессор arm64 (`adb shell getprop ro.product.cpu.abi`).
- Приложение «Подключено», но браузер не ходит → хост `127.0.0.1`, порт `1080` (по умолчанию), тип прокси HTTP **или** SOCKS5 — оба работают. Если включили Android Wi-Fi-прокси, проверьте что они точно применились именно к этой сети.
- На сервере всё живое, но клиент висит с `opening stream: timeout` → на сервере перезапускался dnstt-server (сессия клиента устарела). Выключите-включите туннель в приложении на телефоне.

## Структура репозитория

```
jivenet/
├── README.md                  # этот файл
├── docs/
│   ├── ARCHITECTURE.md        # как устроено
│   └── BUILDING.md            # как пересобрать с нуля
├── server/                    # всё для Debian-сервера
│   ├── install.sh
│   ├── uninstall.sh
│   ├── Makefile
│   ├── systemd/               # unit'ы
│   ├── etc/                   # danted.conf (шаблон)
│   ├── scripts/
│   │   ├── gen-keys.sh
│   │   ├── setup-iptables.sh
│   │   ├── status.sh
│   │   └── print-qr.sh
│   └── docs/
│       ├── DNS-SETUP.md
│       └── TROUBLESHOOTING.md
└── android/
    ├── app/                   # Gradle-модуль приложения
    │   └── src/main/
    │       ├── kotlin/net/jivenet/client/   # Kotlin-код
    │       ├── jniLibs/arm64-v8a/
    │       │   └── libdnstt_client.so       # бинарник dnstt-client
    │       └── res/                         # ресурсы
    ├── dnstt-src/             # клон dnstt (не коммитится, создаётся scripts/)
    ├── scripts/build-binaries.sh
    ├── build.gradle.kts
    ├── settings.gradle.kts
    ├── gradle/wrapper/
    └── gradlew
```

## Лицензия

dnstt — CC0 (David Fifield). Код этого проекта — MIT.
