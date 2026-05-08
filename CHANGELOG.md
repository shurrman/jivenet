# Changelog

## 0.9.0 — 2026-05-09

Замена tun2socks на sing-box. Встроенный DoH-резолвер фиксит DNS-leak,
auto-reconnect при смене сети.

### Архитектура

В v0.2.0 трафик шёл `Android → TUN → tun2socks → SOCKS5 → dnstt-client`,
DNS — мимо туннеля через резолвер оператора (DNS-leak). В v0.9.0 связку
TUN+tun2socks заменяет sing-box с встроенным DoH-резолвером:

```
Android apps → TUN (sing-box gVisor)
                 ├─ DNS UDP/53  → hijack → DoH://1.1.1.1 → outbound[proxy]
                 └─ TCP/UDP             → outbound[proxy] (SOCKS5 → dnstt)
                                              │
                                              ▼
                                      dnstt-client subprocess
                                              │
                                              ▼
                                         сервер
```

dnstt-client всё так же запущен subprocess'ом (libdnstt_client.so из
jniLibs) — sing-box ходит к нему через SOCKS5 outbound. Сам dnstt
менять не было причин: он стабилен, а его архитектура (один stream-туннель)
идеально вписывается в один outbound sing-box.

### Добавлено

- **`android/sing-box-src/`** — vendored sing-box (`SagerNet/sing-box`,
  GPL-3, не коммитится). Клонируется отдельно перед сборкой:

      git clone --depth=1 https://github.com/SagerNet/sing-box.git \
          android/sing-box-src

- **`android/scripts/build-singbox-aar.sh`** — собирает `libbox.aar`
  (~14 МБ) через `gomobile bind` с тэгами `with_gvisor,with_quic,with_utls`.
  Использует sagernet-форк gomobile (требуется патч под sing-box).
  Кладёт результат в `android/app/libs/libbox.aar`.
- **`SingboxBridge.kt`** — Kotlin-фасад для `Libbox.NewCommandServer`
  + `StartOrReloadService(jsonConfig)`. Reflective `isAvailable` для
  graceful-degradation если libbox не собран.
- **`SingboxPlatform.kt`** — реализация `io.nekohasekai.libbox.PlatformInterface`:
  `openTun()` строит VpnService.Builder из `TunOptions`,
  `autoDetectInterfaceControl()` вызывает `protect(fd)` для исходящих
  соединений sing-box (DoH/SOCKS5 не должны loop'нуться обратно в TUN).
  Структурно следует SFA (sing-box-for-android), упрощено для нашего
  одного outbound.
- **`SingboxConfig.kt`** — генератор JSON-конфига:
  - `inbounds[type=tun]` с `auto_route=true`, `stack=gvisor`,
    `exclude_package=[наш bundle]`
  - `dns.servers` с DoH `https://1.1.1.1/dns-query` через outbound `proxy`
    + FakeIP `198.18.0.0/15`
  - `outbounds[type=socks]` → `127.0.0.1:1080` (наш dnstt-client)
  - `route.rules` — DNS hijack + private IP → direct
- **Auto-reconnect**: `ConnectivityManager.NetworkCallback` в `TunnelService`.
  При появлении новой default-сети после потери (Wi-Fi ↔ мобильная)
  перезапускает sing-box+dnstt с теми же настройками.

### Изменено

- **VPN-режим**: `tun2socks-mobile/` Go-модуль больше не используется
  (но vendored остался — на случай нужно собрать legacy-вариант).
- `TunnelService.kt` целиком переписан под sing-box. Убрана ручная сборка
  TUN через `Builder.addAddress/.addRoute` — теперь это делает
  `SingboxPlatform.openTun()` из `TunOptions`.
- APK подрос до ~40 МБ (`libbox.so` 42 МБ uncompressed). R8 в release-сборке
  должен срезать неиспользуемые транспорты sing-box, но в debug-сборке
  весь движок включён.

### Исправлено

- **DNS-leak**. Раньше `addDnsServer` не вызывался намеренно — 3proxy
  auto-mode не умеет SOCKS5 UDP-relay, поэтому tun2socks не мог
  пробросить DNS. Сейчас sing-box перехватывает DNS UDP внутри TUN
  и резолвит через DoH-сервер (1.1.1.1 по умолчанию), сам DoH-запрос
  идёт через `outbound[proxy]` → dnstt → сервер. Оператор видит только
  зашифрованный трафик dnstt, без DNS-leak'а.
- **Зависание при смене сети**. При переключении Wi-Fi ↔ мобильная
  туннель раньше требовал ручного «Отключить → Подключить» — теперь
  `NetworkCallback` делает это автоматически.

### Ограничения

- Этот релиз ещё не прошёл полное полевое тестирование, но клиент
  собирается и запускается. Если что-то сломалось — откатывайтесь на
  v0.2.0 (он зафиксирован, signed APK на странице релиза).
- Сервер не менялся (3proxy + dnstt-server), `server/install.sh` остался
  тем же. Конфиг (domain/pubkey) совместим.
- libbox содержит много транспортов (QUIC, uTLS, gVisor) — запас на
  будущее. Сейчас фактически используются только DNS/DoH-модуль и
  TUN-инбаунд + SOCKS5-outbound.

## 0.2.0 — 2026-05-08

Полноценный VPN-режим в Android-клиенте.

### Добавлено

- **VPN-режим** на базе `VpnService` + `tun2socks` через gomobile-биндинг (`android/tun2socks-mobile/`). Захватывает весь трафик устройства в TUN, форвардит TCP в локальный SOCKS5 dnstt-client. Работает на Wi-Fi и мобильной сети без adb, без SocksDroid, без root.
- `Tun2socksBridge.kt` — Kotlin-фасад с reflective-загрузкой класса `tun2socksmobile.Tun2socksmobile` (если AAR не собран — VPN-режим помечается недоступным, Proxy-режим продолжает работать).
- `scripts/build-aar.sh` — сборка `tun2socks.aar` через `gomobile bind` (требует Android NDK).
- `scripts/cloudns-glue.sh` — управление glue-A-записями NS-серверов через ClouDNS API (требует платный план).
- `.env.example` — шаблон для ClouDNS API credentials.
- Обновлённая документация по VPN-режиму в [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), [`docs/BUILDING.md`](docs/BUILDING.md), [`README.md`](README.md).

### Изменено

- **Сервер**: `dnstt-server -mtu 512` по умолчанию (для совместимости с DNS-резолверами мобильных операторов, которые обрезают EDNS0). Прописано в `server/install.sh` и `server/systemd/dnstt-server.service`.
- **Сервер**: `3proxy maxconn 4096` (Chrome через QUIC создаёт сотни SOCKS5 UDP ASSOCIATE, дефолт в 100 быстро забивается).
- **Клиент**: `TunnelMode.VPN` стал режимом по умолчанию для нового конфига.
- `TunnelService` больше не вызывает `Builder.addDnsServer` — 3proxy auto-mode не поддерживает SOCKS5 UDP-relay, поэтому DNS теперь идёт через резолвер underlying network (DNS-leak есть, но имена резолвятся).
- `AndroidManifest.xml`: `foregroundServiceType="systemExempted"` для `TunnelService` (Android 14+).

### Исправлено

- Локальный форк `tun2socks-src` с экспортированными `engine.StartE/StopE` — возвращают `error` вместо `log.Fatalf` → `os.Exit(1)`, который раньше валил весь APK при любой ошибке tun2socks.
- `DnsttBridge` ловит `IOException` при stdout-readers (раньше падал с `InterruptedIOException` при `Stop()` после `process.destroy()`).
- Settings UI: `FlowRow` для DoH-пресетов (помещаются все 8 чипов), `BasicTextField` с `monospace` для pubkey (видны все 64 hex), `verticalScroll`, валидация URL по регэкспу.

### Архитектура

Клиент использует **гибридную модель**:

- **dnstt-client** — subprocess через `ProcessBuilder` (запускается из `jniLibs/<abi>/libdnstt_client.so`). dnstt не является Go-библиотекой (вся логика в `package main`), поэтому subprocess проще, чем форк-в-библиотеку.
- **tun2socks** — gomobile-биндинг в том же процессе APK. У него нормальный engine-package с экспортируемым API, и он принимает `int fd` от `VpnService.Builder.establish()` — JVM-`ProcessBuilder` не умеет передавать произвольные fd, поэтому subprocess не подходил.

Подробнее — `docs/ARCHITECTURE.md` → секция «Гибридная модель: dnstt subprocess + tun2socks gomobile».

## 0.1.0 — 2026-04-17

Первый релиз. Только Proxy-режим.

### Добавлено

- Сервер: `dnstt-server` + `3proxy auto-mode` на Debian 12. `install.sh` ставит всё одной командой.
- Android-клиент (Jetpack Compose): Proxy-режим с локальным HTTP+SOCKS5 на `127.0.0.1:1080`, QR-сканер для импорта конфига.
- Документация: `README.md`, `docs/ARCHITECTURE.md`, `docs/BUILDING.md`, `docs/ANDROID-PROXY-SETUP.md`, `server/docs/{DNS-SETUP,TROUBLESHOOTING}.md`.

### Известные ограничения

- Только arm64-v8a (95%+ Android 2020+).
- VPN-режим заглушён — пользователь должен настроить системный прокси сам (или через SocksDroid на мобильной сети).
- Статистика байт/соединений = 0 (dnstt-client не экспортирует через stdout).
