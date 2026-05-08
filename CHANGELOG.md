# Changelog

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
