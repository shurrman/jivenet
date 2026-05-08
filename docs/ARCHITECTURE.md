# Архитектура

## Общая идея

jivenet — это DNS-over-HTTPS туннель. Клиент упаковывает свой TCP-поток в base32-закодированные DNS-метки, отправляет их как обычные DNS-запросы (через публичный DoH-резолвер), сервер их декодирует и выпускает в интернет через локальный прокси.

Зачем это нужно: DNS-запросы разрешают почти везде, где «обычные» протоколы фильтруются. DoH делает их неотличимыми от легитимного DNS-трафика на транспортном уровне (HTTPS к публичному резолверу).

На сервере форвард-цель — `3proxy` в **auto-режиме**: на одном TCP-порту одновременно принимает HTTP/HTTPS-CONNECT и SOCKS4/5, детектит протокол по первому байту. Это важно для Android: системные настройки прокси в Wi-Fi — это **только HTTP**, SOCKS5 ОС на этом уровне не поддерживает. 3proxy auto-mode даёт один адрес для обоих — браузеры/Wi-Fi-настройки работают по HTTP, а SOCKS5-клиенты (Firefox с FoxyProxy, Telegram, etc.) — по SOCKS5.

## Поток данных (один запрос браузера)

```
┌─────────────────────┐ HTTP/SOCKS5 ┌─────────────────────┐
│ Chrome / Firefox /  ├────────────▶│ libdnstt_client.so  │ ← subprocess в APK
│ любое приложение    │  (TCP)      │ слушает 127.0.0.1   │
│ системные настройки │             │ :1080 на телефоне   │
│ Wi-Fi → Proxy HTTP  │             │                     │
└─────────────────────┘             └──────────┬──────────┘
                                               │ base32-метки в DNS-запросах
                                               │ внутри HTTPS к DoH-резолверу
                                               ▼
                                    ┌─────────────────────┐
                                    │ Cloudflare 1.1.1.1  │
                                    │ (публичный DoH)     │
                                    └──────────┬──────────┘
                                               │ DNS UDP :53
                                               ▼
                                    ┌─────────────────────┐
                                    │ Ваш VPS             │
                                    │ iptables REDIRECT   │
                                    │ :53 → :5300         │
                                    │          │          │
                                    │          ▼          │
                                    │ dnstt-server :5300  │
                                    │          │          │ TCP
                                    │          ▼          │
                                    │ 3proxy auto :3128   │
                                    │ HTTP + SOCKS5 на    │
                                    │ одном порту         │
                                    │          │          │
                                    │          ▼          │
                                    │ example.com:443 ────┼──▶ Интернет
                                    └─────────────────────┘
```

Обратный путь — та же цепочка в другую сторону, dnstt кладёт ответные байты в TXT-записи DNS-ответов.

## Стек протоколов внутри туннеля

Что видит пассивный наблюдатель между телефоном и резолвером: обычный HTTPS к `https://1.1.1.1/dns-query` с DNS-сообщениями внутри. Метаданные (размеры, частота, тайминги) — DNS-подобные.

Что на самом деле внутри:

```
┌──────────────────────────┐
│  ваш TCP-поток           │  HTTP CONNECT или SOCKS5 → HTTPS к сайту
├──────────────────────────┤
│  smux (мультиплексор)    │  много параллельных стримов в одном KCP
├──────────────────────────┤
│  Noise_NK                │  сквозное шифрование, аутентификация сервера
│  (X25519 + ChaCha20)     │  по 32-байтному публичному ключу
├──────────────────────────┤
│  KCP                     │  надёжная упорядоченная доставка поверх
│                          │  ненадёжной DNS-транспорта
├──────────────────────────┤
│  dnstt DNS-кодировка     │  байты → base32 → метки < 63 символов
├──────────────────────────┤
│  DoH (DNS-over-HTTPS)    │  HTTPS-запрос к публичному резолверу
├──────────────────────────┤
│  TLS 1.3                 │  uTLS fingerprint для маскировки под браузер
└──────────────────────────┘
```

Ключевое: **Noise_NK** даёт две вещи — клиент уверен, что говорит именно с вашим сервером (проверка по pubkey), и трафик зашифрован независимо от того, что делает DoH-резолвер. Резолвер видит только шифрованные байты внутри DNS-меток.

## Серверная сторона

### Процессы

- **`dnstt-server`** (systemd-юнит) — слушает UDP/5300, принимает DNS-запросы, декодирует туннель, открывает TCP к `127.0.0.1:3128` и ретранслирует байты.
  - Запускается от пользователя `dnstt` (не root), hardening-директивы в unit-файле.
  - Приватный ключ: `/etc/dnstt/server.key` (`mode 0640`, владелец `root:dnstt`).
  - Конфигурация: `/etc/dnstt/server.env` (TUNNEL_DOMAIN, LISTEN_ADDR, FORWARD_ADDR).

- **`3proxy`** (auto-режим) — слушает `127.0.0.1:3128` только loopback. Принимает на одном порту HTTP/HTTPS-CONNECT, SOCKS4, SOCKS5 — протокол определяется по первому байту входящего соединения.
  - Без аутентификации: клиент уже аутентифицирован на уровне Noise внутри dnstt, а снаружи до порта не добраться физически (bind на 127.0.0.1).
  - Конфигурация: `/etc/3proxy/3proxy.cfg`.
  - Собирается из исходников (github.com/3proxy/3proxy), в Debian apt его нет.

### NAT

Для того чтобы `dnstt-server` не требовал root и не мешал потенциальному systemd-resolved, он слушает на непривилегированном порту 5300. Публичные резолверы всегда шлют запросы на :53 — поэтому iptables перенаправляет:

```
iptables -t nat -A PREROUTING -i eth0 -p udp --dport 53 -j REDIRECT --to-ports 5300
```

Правило сохраняется через `netfilter-persistent` (пакет `iptables-persistent`). Скрипт `setup-iptables.sh` делает и фоллбэк на `nft` (если `iptables` отсутствует).

### Почему прокси (HTTP/SOCKS5), а не настоящий NAT-шлюз

dnstt — это stream-туннель. Он передаёт TCP-байтстримы, не IP-пакеты. Можно было бы добавить TUN-bridge на сервере, но это:

- усложняет развёртывание (маршрутизация, firewall, NAT);
- вносит дополнительный буфер в горячий путь (IP → байт-стрим → DoH → байт-стрим → IP);
- не даёт выигрыша — пользователь получает тот же доступ в интернет.

Прокси как форвард-цель — нативное решение: dnstt выплёвывает TCP-байты, прокси-сервер говорит с ними на HTTP или SOCKS5, дальше сам подключается куда надо.

### Почему 3proxy (auto-mode), а не отдельный Dante/tinyproxy

Android **на уровне ОС** (настройки Wi-Fi → Proxy) поддерживает **только HTTP-прокси**. SOCKS5 там нет — его можно использовать только в приложениях, которые им сами владеют (Firefox, Telegram, ProxyDroid, …).

Если поставить только Dante (SOCKS5) — Chrome и системные Wi-Fi-настройки не работают. Если поставить только tinyproxy (HTTP) — Firefox с FoxyProxy-SOCKS5 и клиенты, говорящие SOCKS, не работают.

**3proxy auto-mode** слушает один TCP-порт и определяет протокол по первому байту:

| Первый байт | Протокол |
|---|---|
| `0x04` | SOCKS4 |
| `0x05` | SOCKS5 |
| `G`, `P`, `C`, `O`, `H`, `D`, `T` | HTTP/1.x (GET, POST, CONNECT, OPTIONS, HEAD, DELETE, TRACE) |

В результате `127.0.0.1:1080` внутри туннеля на Android работает и для HTTP-клиентов, и для SOCKS5-клиентов одновременно.

3proxy **не входит в apt** Debian/Ubuntu — собирается из исходников (https://github.com/3proxy/3proxy), `make -f Makefile.Linux`, 30 секунд. В проекте это автоматизировано в `install.sh`.

## Клиентская сторона (Android)

### Процессы

Android-приложение = один Java/Kotlin-процесс, внутри него запускается один **сабпроцесс** `libdnstt_client.so` через `ProcessBuilder`.

```
Android-приложение (Kotlin)
├── MainActivity / SettingsActivity (UI на Jetpack Compose)
│
├── ProxyService (foreground-сервис, Proxy-режим)
│     └── DnsttBridge (Kotlin object)
│             └── ProcessBuilder → libdnstt_client.so
│                                    ↓
│                                  TCP :1080 listener (SOCKS5/HTTP)
│
└── TunnelService (foreground VpnService, VPN-режим)
      ├── DnsttBridge — тот же ProcessBuilder → libdnstt_client.so
      │                                          ↓
      │                                        TCP :1080 listener
      ├── VpnService.Builder.establish() → ParcelFileDescriptor → fd
      └── Tun2socksBridge (gomobile .aar)
             └── tun2socksmobile.Start(fd, "socks5://127.0.0.1:1080", 1500)
                    ↓
                 in-process Go runtime: gVisor TCP/IP stack, читает TUN-fd,
                 форвардит TCP/UDP в наш SOCKS5 → dnstt-client → туннель
```

В VPN-режиме всё работает в **одном процессе APK**: и Kotlin-UI, и gomobile-биндинг tun2socks. dnstt-client — отдельный subprocess, общается с tun2socks через loopback на `127.0.0.1:1080`. Само приложение **исключается** из VPN через `Builder.addDisallowedApplication(packageName)` — иначе исходящие DNS-запросы dnstt-client (UDP к резолверу оператора) попадут обратно в TUN и зациклятся.

### Гибридная модель: dnstt subprocess + tun2socks gomobile

В клиенте сосуществуют два Go-компонента, выбранные по разным причинам:

- **dnstt-client** запускается как **subprocess** через `ProcessBuilder`. dnstt не является библиотекой (вся логика в `package main` его main.go), переписывать ~400 строк в экспортируемую форму — несколько часов работы и риск ошибок в Noise/KCP. Готовый бинарник используется as-is, обернутый стандартным трюком Android (`libdnstt_client.so` в `jniLibs/<abi>/`, исполняется через `nativeLibraryDir`).
- **tun2socks** ([xjasonlyu/tun2socks](https://github.com/xjasonlyu/tun2socks)) собирается через **`gomobile bind`** в `.aar`. У него нормальный engine-package с экспортированными `Insert/Start/Stop`, и нужен ему `int fd` от `VpnService.Builder.establish()`. JVM `ProcessBuilder` не передаёт произвольные fd дочернему процессу, поэтому subprocess-вариант для tun2socks не подходит — `.aar` в том же процессе APK получает fd как обычный long-параметр.

Обе модели стандартны (v2rayNG, NekoBox, Shadowsocks-Android делают то же самое: бинарник для основного движка + JNI/gomobile для tun2socks).

Минус subprocess для dnstt: из Kotlin нельзя достать байт-счётчики в реальном времени. Статистика упрощена до `connected/uptime`. dnstt пишет счётчики в stderr — можно парсить, но пока не сделано.

### Как Android позволяет exec бинарника

Android >= 10 запрещает `exec()` файлов из произвольных мест FS (например, из `/data/data/<pkg>/files/`). Единственная легальная дыра — `applicationInfo.nativeLibraryDir`: туда `PackageManager` при установке распаковывает содержимое `jniLibs/<abi>/`, но только файлы с **префиксом `lib` и суффиксом `.so`**.

Поэтому бинарник в APK называется `libdnstt_client.so` — это не shared library, это ELF executable с фейковым расширением. Android copy-ит его в `nativeLibraryDir`, выставляет `+x`, и `ProcessBuilder` его запускает.

Чтобы это гарантированно работало:

```kotlin
// app/build.gradle.kts
android.packaging {
    jniLibs.useLegacyPackaging = true   // извлечь .so из APK на диск при установке
}
```

```xml
<!-- AndroidManifest.xml -->
<application android:extractNativeLibs="true" ... >
```

(В нашей сборке `extractNativeLibs` выставлен неявно через `useLegacyPackaging`.)

### Жизненный цикл туннеля

1. Пользователь нажимает «Подключить» → `ProxyService.onStartCommand(ACTION_START)`.
2. Сервис читает `TunnelConfig` из DataStore, проверяет полноту.
3. Запускает foreground-уведомление с кнопкой «Остановить».
4. Вызывает `DnsttBridge.startProxy(context, cfg)`.
5. `DnsttBridge` строит ProcessBuilder с путём к `libdnstt_client.so` и аргументами:

```
libdnstt_client.so -doh <URL> -pubkey <HEX> <DOMAIN> 127.0.0.1:<PORT>
```

6. Запускает процесс. Stdout+stderr читаются в отдельной корутине и пишутся в logcat с тегом `DnsttBridge`.
7. При нажатии «Отключить» или onDestroy: `process.destroy()` + `waitFor(2s)` + `destroyForcibly()` если процесс не завершился.

## Безопасность

### Что защищено

- **Аутентификация сервера.** Клиент знает pubkey сервера (64 hex). Любой MITM не сможет подделать сервер — Noise_NK упадёт на handshake.
- **Шифрование.** Все данные шифруются ChaCha20-Poly1305 (Noise_NK) до DoH-слоя. Резолвер не видит содержимого.
- **Dante изолирован.** Слушает только loopback — никакой внешний коннект на него не пройдёт.
- **Hardening.** dnstt-server systemd-unit: `NoNewPrivileges`, `ProtectSystem=strict`, `ProtectHome`, `PrivateTmp`, read-only `/etc/dnstt`.

### Чего нет

- **Нет аутентификации клиентов.** Любой, у кого есть pubkey, может подключиться. В MVP этого достаточно — pubkey легко ротируется. В будущем — psk (pre-shared key) на уровне dnstt или списки разрешённых client-ID.
- **Нет rate-limiting.** Если кто-то нагрузит сервер запросами — Dante подключится куда угодно, повышая исходящий трафик VPS.
- **Metadata leaks.** Размер DNS-запросов, частота, пэттерн — могут быть заметны статистически. DoH маскирует транспорт, но не скрывает наличие туннеля от продвинутой аналитики.

### Приватный ключ

`/etc/dnstt/server.key` — секрет. Утечка = возможность поставить подставного сервера с тем же публичным ключом. Ротация — `gen-keys.sh`, потом перераздать новый pubkey клиентам.

## Производительность

### Throughput

| Условия | Типичный потолок |
|---|---|
| Оптимально (authoritative mode, Cloudflare DoH) | 300–750 КБ/с |
| Обычный web-сёрфинг через нашу установку | 100–400 КБ/с |
| Streaming video | не тянет 4K; 720p на пределе |
| Голос/мессенджеры | комфортно |

### Где теряем

1. **Размер DNS-меток.** В base32 на каждый байт payload уходит 1.6 байта запроса. При типичном лимите 63 символов на метку и 255 байт на TXT-ответ — утилизация низкая.
2. **Round-trip на каждый пакет.** Даже с smux-мультиплексированием каждый KCP-сегмент требует DNS round-trip через резолвер и наш сервер.
3. **Кэширование DoH-резолвера.** Некоторые резолверы (Google) агрессивно кэшируют, что вредит туннелю. dnstt это обходит через рандомные префиксы в именах.
4. **Rate-limiting.** NextDNS на бесплатном плане лимитирует ~300K запросов/день. Cloudflare — лояльнее.

### Latency

Туннель добавляет ~50–200 ms сверх прямого коннекта, зависит от:

- географии клиент → DoH resolver,
- географии resolver → ваш VPS,
- загрузки сервера.

## Что осталось за кадром MVP

- ~~**VPN-режим** (VpnService + tun2socks)~~ — реализован, см. `android/app/src/main/kotlin/net/jivenet/client/TunnelService.kt`.
- **Статистика** (байты/соединения) — парсить stderr dnstt-client, пробрасывать в UI.
- **Переподключение при смене сети** — listener на `ConnectivityManager.NetworkCallback`, рестарт subprocess при смене default network.
- **Другие ABI** (armeabi-v7a, x86_64) — требует NDK и кросс-компиляции с CGO.
- **Поддержка uTLS-fingerprint'ов** из UI (сейчас используется случайный дефолт dnstt).
- **Аутентификация клиентов** (pre-shared key или список client-ID).
