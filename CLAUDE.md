# jivenet — заметки для Claude

## Workflow для новой версии (ОБЯЗАТЕЛЬНО)

Когда правки готовы и тестируются, всегда выполнить **все** шаги:

1. **`CHANGELOG.md`** — добавить новую секцию `## X.Y.Z — YYYY-MM-DD` сверху
   с разделами «Добавлено / Изменено / Исправлено». Описывать кратко но
   с конкретикой (какой код / что фиксит / поле-проверено или нет).
2. **`android/app/build.gradle.kts`** — поднять `versionCode` (на 1) и
   `versionName` (`X.Y.Z`).
3. **README'ы** — пересмотреть и при необходимости обновить:
   * **`README.md`** (корень) — таблица «Что нового в v0.9.x», секции про
     поведение приложения (мобильная сеть, troubleshooting, ограничения),
     размер APK, структура репозитория. **Точно** обновить таблицу версий
     добавив строку для новой `vX.Y.Z`.
   * **`android/README.md`** — список kotlin-файлов в структуре, таблица
     порогов watchdog'а если они менялись, шаги сборки если появились
     новые скрипты, перечень фич v0.9.x в шапке.
   Если по итогам диффа изменений в коде новых файлов / переименований /
   изменения порогов нет — README'ы можно не трогать, но **всегда**
   проверить нет ли в них устаревших утверждений вида «X не реализовано»
   когда X уже сделано.
4. **Коммит** в стиле `vX.Y.Z: <короткое summary>`. Тело коммита — пара
   абзацев в духе CHANGELOG-секции, на русском, с трейлером
   `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>`.
5. **Push в `main`** — `git push origin main` (репо solo, прямой push
   разрешён в `~/.claude/settings.json`).
6. **GitHub Release** — `bash scripts/onboard.sh --release vX.Y.Z`. Скрипт
   сам:
   * соберёт debug-APK через `gradlew assembleDebug` (из
     `android/app/build/outputs/`, не из root!)
   * сверит APK `versionName` с `build.gradle.kts`
   * запросит конфиг с сервера `wsoft@93.77.166.152`
   * создаст QR + `INSTALL.txt`
   * опубликует release с тремя ассетами (APK, QR, INSTALL).
   Тег `vX.Y.Z` создастся автоматически через `gh release create`.

После этого проверить: `gh release list` показывает `vX.Y.Z` как Latest,
`git tag -l 'v*'` содержит новый тег, на странице репо
https://github.com/shurrman/jivenet README не противоречит фактической
версии.

## Структура проекта

* **`server/`** — Debian-сервер (dnstt-server + 3proxy SOCKS5,
  systemd-юниты). Деплой через `scripts/install.sh`. SSH:
  `wsoft@93.77.166.152`. Серверный config-printer:
  `/home/wsoft/jivenet-server/scripts/print-qr.sh`.
* **`android/`** — Android-клиент.
  * `app/src/main/kotlin/net/jivenet/client/` — Kotlin sources.
  * `app/src/main/jniLibs/arm64-v8a/libdnstt_client.so` — кросс-собранный
    Go-бинарник dnstt-client. Собирается через
    `scripts/build-dnstt-android.sh`.
  * `app/libs/libbox.aar` — sing-box gomobile-bind. Собирается через
    `scripts/build-singbox-aar.sh` (требует
    `with_clash_api,with_gvisor,with_quic,with_utls` build-tags).
* **`scripts/onboard.sh`** — release-инструмент (см. workflow выше).

## Стек туннеля (v0.9.x)

```
Android app → TUN → sing-box (libbox.aar)
                       │
                       ├─ DNS hijack → fakeip + DoH через "proxy"
                       └─ TCP/UDP   → SOCKS5 outbound
                                         │
                                         ▼
                                  dnstt-client subprocess (порт cfg.localPort=1080)
                                         │
                                         ▼ DoH/DoT/UDP-DNS, KCP+smux
                                  upstream DoH (Cloudflare 1.1.1.1 / cellular DNS)
                                         │
                                         ▼ DNS auth
                                  jivenet сервер (dnstt-server :5300, 3proxy :1080)
                                         │
                                         ▼
                                       интернет
```

* `TunnelService.kt` — VpnService, держит mutex, network-callback,
  watchdog instance.
* `DohWatchdog.kt` (v0.9.4) — failover между cellular DNS и
  пользовательской DoH. Триггеры: STALL (upload растёт, download нет),
  DEAD-FROM-START (download==0 после 60с initial-deadline).
* `DnsttBridge.kt` — запускает `libdnstt_client.so` как subprocess
  (Android разрешает exec только из `nativeLibraryDir`). Принимает
  `dohOverride` от watchdog'а.
* `SingboxBridge.kt` / `SingboxConfig.kt` / `SingboxPlatform.kt` —
  обёртка над libbox. PlatformInterface отдаёт TUN fd через
  `VpnService.Builder.establish()`, делает `protect()` на исходящие
  сокеты sing-box.
* `SingboxStats.kt` — HTTP-поллер clash-api на 127.0.0.1:9090
  (`uploadTotal`, `downloadTotal`, `connections[]`).

## Тестирование на устройстве

* Подключённое устройство Android: `adb devices` (Pixel 3a-class,
  arm64-v8a). UID нашего debug-app: `10397`,
  package `net.jivenet.client.debug`.
* APK: `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`.
* Логи: `adb logcat -d | grep -aE 'TunnelService:|DohWatchdog:|DohChain:|SingboxBridge:|DnsttBridge: dnstt:'`.
* Clash-api: `adb forward tcp:19090 tcp:9090 && curl -s
  http://127.0.0.1:19090/connections`.
* DataStore с конфигом: `/data/data/net.jivenet.client.debug/files/datastore/tunnel_config.preferences_pb`
  (формат — androidx PreferenceMap protobuf).
* Скриншот: `adb shell screencap -p > /tmp/x.png`.

## Конвенции кода

* Комментарии и log-сообщения — на русском.
* Длинные `Log.i(TAG, ...)` со специфичными деталями приветствуются —
  это основной способ диагностики на live-устройстве.
* Magic-числа выносить в `companion object` константы с поясняющим
  комментарием почему именно такое значение (см. `INITIAL_DEADLINE_MS = 60_000L`
  в `DohWatchdog.kt` — там объяснено почему не 30 и не 90).
