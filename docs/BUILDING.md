# Сборка из исходников

Документ для разработчиков: как развернуть сервер и собрать Android-APK на чистой машине, как расширять/править.

---

## 1. Сервер (Debian 12/13, Ubuntu 22.04+)

### Что делает `install.sh`

```
server/install.sh <tunnel-domain> [public-ip]
```

Идемпотентная последовательность (9 шагов):

1. `apt install` пакеты: `git ca-certificates build-essential iptables iptables-persistent qrencode curl dnsutils`. Проверяет Go — если нет или < 1.21 — скачивает **Go 1.22.9** с go.dev в `/usr/local/go`. Debian 12 штатно поставляет 1.19, которого недостаточно для современного `kcp-go`.
2. Клонирует dnstt с github-зеркала (`github.com/net2share/dnstt`) в `server/dnstt/`, собирает `dnstt-server` с `CGO_ENABLED=0`, `-trimpath -ldflags='-s -w'`, ставит в `/usr/local/bin/`.
3. Клонирует **3proxy** из `github.com/3proxy/3proxy` в `server/3proxy/`, собирает через `make -f Makefile.Linux`, ставит в `/usr/local/bin/3proxy`. Повторный запуск: пересборка только если src новее бинаря.
4. Создаёт системного юзера `dnstt` (без домашнего каталога, shell=nologin).
5. `scripts/gen-keys.sh` → пара X25519 в `/etc/dnstt/server.key` (`0640`, `root:dnstt`) и `/etc/dnstt/server.pub` (`0644`). Если ключи уже есть — пропускает.
6. Пишет `/etc/dnstt/server.env` с `LISTEN_ADDR=0.0.0.0:5300`, `TUNNEL_DOMAIN=<arg>`, `FORWARD_ADDR=127.0.0.1:3128`.
7. Ставит конфиг `/etc/3proxy/3proxy.cfg` (auto-mode, HTTP+SOCKS5 на :3128, allow только loopback), systemd-юнит `3proxy.service` с hardening. Если у вас сохранился legacy `danted` от предыдущих версий jivenet — отключается автоматически.
8. Ставит `systemd/dnstt-server.service` с hardening-директивами, `enable --now`.
9. `scripts/setup-iptables.sh` добавляет правило NAT (iptables или nft), сохраняет через `netfilter-persistent`.

В конце — smoke-тесты `curl --socks5` и `curl -x http`, печать конфига, JSON и QR.

### Ручной порядок (если не хочется запускать install.sh целиком)

```bash
cd server
sudo apt install -y git ca-certificates build-essential iptables iptables-persistent qrencode curl dnsutils
# Свежий Go (если нужен)
curl -fsSL https://go.dev/dl/go1.22.9.linux-amd64.tar.gz | sudo tar -C /usr/local -xz
export PATH=/usr/local/go/bin:$PATH

# dnstt-server
git clone https://github.com/net2share/dnstt.git dnstt
cd dnstt/dnstt-server
CGO_ENABLED=0 go build -trimpath -ldflags='-s -w' -o dnstt-server .
sudo install -m0755 dnstt-server /usr/local/bin/
cd ../..

# 3proxy (HTTP + SOCKS5 auto)
git clone --depth=1 https://github.com/3proxy/3proxy.git 3proxy
(cd 3proxy && make -f Makefile.Linux && sudo install -m0755 bin/3proxy /usr/local/bin/3proxy)

# Каталоги и пользователь
sudo useradd --system --no-create-home --shell /usr/sbin/nologin dnstt
sudo install -d -o root -g dnstt -m 0750 /etc/dnstt
sudo install -d -m 0755 /etc/3proxy

sudo bash scripts/gen-keys.sh
echo 'LISTEN_ADDR=0.0.0.0:5300
TUNNEL_DOMAIN=your.domain.com
FORWARD_ADDR=127.0.0.1:3128' | sudo tee /etc/dnstt/server.env

sudo install -m0644 etc/3proxy.cfg /etc/3proxy/3proxy.cfg
sudo install -m0644 systemd/3proxy.service /etc/systemd/system/
sudo install -m0644 systemd/dnstt-server.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now 3proxy dnstt-server

sudo bash scripts/setup-iptables.sh
```

### Пересобрать dnstt-server или 3proxy на месте

```bash
cd server
make build           # dnstt-server → /usr/local/bin/
make build-3proxy    # 3proxy → /usr/local/bin/
sudo systemctl restart dnstt-server 3proxy
```

### Обновить dnstt / 3proxy из апстрима

```bash
cd server/dnstt   && git pull && cd ..
cd server/3proxy  && git pull && cd ..
cd server && make build build-3proxy
sudo systemctl restart dnstt-server 3proxy
```

### Почему 3proxy как форвард-цель

См. [`ARCHITECTURE.md`](ARCHITECTURE.md#почему-3proxy-auto-mode-а-не-отдельный-dantetinyproxy). В двух словах: auto-mode 3proxy слушает на одном порту HTTP и SOCKS5 одновременно — детектит по первому байту. Это даёт максимальную совместимость (системный прокси Android = HTTP; FoxyProxy Firefox = SOCKS5). Если хотите заменить на что-то другое — смените `FORWARD_ADDR` в `/etc/dnstt/server.env` и остановите 3proxy.

### Известные grass: systemd-resolved

На Ubuntu и некоторых установках Debian systemd-resolved держит `:53`. Тогда iptables REDIRECT не сработает: ядро сначала доставит пакет localhost-демону.

Фикс:

```bash
sudo sed -i 's/^#DNSStubListener=yes/DNSStubListener=no/' /etc/systemd/resolved.conf
sudo systemctl restart systemd-resolved
# затем переналить правила iptables
sudo bash server/scripts/setup-iptables.sh
```

---

## 2. Android-клиент

### Требования

- **macOS** (тестировалось), **Linux** (должно работать) или **Windows** (не тестировалось).
- **Java 17** (OpenJDK или Temurin). Java 19+ работает, но AGP 8.5 пишет warning. Рекомендуется 17 — и в `android/gradle.properties` пин-строка `org.gradle.java.home=...`.
- **Go 1.21+** — нужен для кросс-компиляции `dnstt-client`. Пакет в homebrew: `brew install go`.
- **Android SDK**: commandline-tools, platforms;android-34, build-tools;34.0.0, platform-tools.
- **Android NDK r26**: нужен только для armeabi-v7a и x86_64 ABI. Для arm64-v8a (дефолт) — не нужен, Go умеет собирать pure-Go android/arm64.

### Установка SDK (macOS, без Android Studio)

```bash
# 1. Скачать commandline-tools
mkdir -p ~/Library/Android/sdk/cmdline-tools
cd ~/Library/Android/sdk/cmdline-tools
curl -fsSL -o cmdline.zip 'https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip'
unzip -q cmdline.zip && rm cmdline.zip && mv cmdline-tools latest

# 2. Принять лицензии
export ANDROID_HOME=~/Library/Android/sdk
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
yes | sdkmanager --licenses

# 3. Поставить компоненты
sdkmanager --install "platform-tools" "platforms;android-34" "build-tools;34.0.0"
# Опционально (для других ABI):
sdkmanager --install "ndk;26.3.11579264"
```

### Установка SDK (Linux)

Аналогично, только `commandlinetools-linux-*_latest.zip` вместо mac-версии.

### Создать `android/local.properties`

```
sdk.dir=/Users/<you>/Library/Android/sdk
```

Gradle читает его, чтобы найти SDK.

### Шаги сборки

```bash
cd android

# 1. Клонировать dnstt (используется как subprocess через ProcessBuilder)
git clone --depth=1 https://github.com/net2share/dnstt.git dnstt-src

# 2. Собрать нативный бинарник dnstt-client под arm64
./scripts/build-binaries.sh
# → app/src/main/jniLibs/arm64-v8a/libdnstt_client.so

# 3. Клонировать tun2socks и собрать .aar для VPN-режима
git clone --depth=1 https://github.com/xjasonlyu/tun2socks.git tun2socks-src
./scripts/build-aar.sh
# → app/libs/tun2socks.aar  (через gomobile bind, нужен ANDROID_NDK_HOME)

# 4. Собрать APK
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :app:assembleDebug
#   → app/build/outputs/apk/debug/app-debug.apk
```

Если шаг 3 (`tun2socks.aar`) пропустить, APK всё равно соберётся — Tun2socksBridge через рефлексию определит отсутствие класса и VPN-режим в UI пометит как недоступный (Proxy-режим работает без AAR).

### Что именно лежит в APK

```
lib/arm64-v8a/libdnstt_client.so    — наш кросс-скомпилированный dnstt-client (7.6 МБ)
lib/arm64-v8a/libbarhopper_v3.so    — ML Kit native для QR-сканера
lib/arm64-v8a/libandroidx.graphics.path.so
lib/arm64-v8a/libdatastore_shared_counter.so
lib/arm64-v8a/libimage_processing_util_jni.so
classes.dex                         — JVM-код (Kotlin/Java)
res/, resources.arsc                — ресурсы
AndroidManifest.xml
```

### Почему бинарник — `libdnstt_client.so`

Android >= 10 запрещает `exec()` файлов из `/data/data/<pkg>/`. Единственная легальная дыра — `applicationInfo.nativeLibraryDir`: PackageManager извлекает туда `jniLibs/<abi>/` при установке. Но только файлы с префиксом `lib` и расширением `.so`. Мы этим пользуемся: расширение `.so` — фикция, файл на самом деле ELF executable.

Чтобы Android гарантированно вытаскивал `.so` на диск (а не держал в zip-mapping, откуда exec не работает):

```kotlin
// app/build.gradle.kts
android.packaging {
    jniLibs.useLegacyPackaging = true
}
```

### Release-сборка + подпись

По умолчанию `assembleRelease` требует keystore. Один раз создаём:

```bash
keytool -genkey -v -keystore ~/jivenet-release.keystore \
    -alias jivenet -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass <PASS> -keypass <PASS> \
    -dname "CN=jivenet,O=jivenet,C=RU"
```

Добавляем в `android/app/build.gradle.kts`:

```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE") ?: "~/jivenet-release.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = "jivenet"
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
```

Собираем:

```bash
export KEYSTORE_PASSWORD=... KEY_PASSWORD=...
./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

Проверяем подпись:

```bash
~/Library/Android/sdk/build-tools/34.0.0/apksigner verify --verbose app-release.apk
```

---

## 3. Расширения и модификации

### Добавить ABI: armeabi-v7a и x86_64

**Нужен NDK.** `go build` для android/arm требует внешнего cc (см. ошибку про `runtime/cgo` в логе сборки — это пометка, что Go для android/arm не компилирует без CGO).

```bash
export ANDROID_NDK_HOME=~/Library/Android/sdk/ndk/26.3.11579264
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin"

# armeabi-v7a
mkdir -p android/app/src/main/jniLibs/armeabi-v7a
cd android/dnstt-src/dnstt-client
CGO_ENABLED=1 GOOS=android GOARCH=arm GOARM=7 \
    CC="$TOOLCHAIN/armv7a-linux-androideabi26-clang" \
    go build -trimpath -ldflags='-s -w' \
    -o ../../app/src/main/jniLibs/armeabi-v7a/libdnstt_client.so .

# x86_64
mkdir -p ../../app/src/main/jniLibs/x86_64
CGO_ENABLED=1 GOOS=android GOARCH=amd64 \
    CC="$TOOLCHAIN/x86_64-linux-android26-clang" \
    go build -trimpath -ldflags='-s -w' \
    -o ../../app/src/main/jniLibs/x86_64/libdnstt_client.so .
```

В `app/build.gradle.kts` снимаем `abiFilters += "arm64-v8a"` (или расширяем список), пересобираем APK.

`scripts/build-binaries.sh` уже написан под это — просто раскомментируйте соответствующие блоки.

### VPN-режим (реализован)

> Этот раздел оставлен для исторической справки. VPN-режим уже реализован — см. `TunnelService.kt`, `Tun2socksBridge.kt`, `tun2socks-mobile/`. Ниже — что было сделано:

План работ, примерно 2–3 часа:

1. **Собрать tun2socks-бинарник** (кросс-компиляция под android/arm64):
   ```bash
   git clone --depth=1 https://github.com/xjasonlyu/tun2socks.git
   cd tun2socks
   CGO_ENABLED=0 GOOS=android GOARCH=arm64 \
       go build -trimpath -ldflags='-s -w' \
       -o /path/to/jivenet/android/app/src/main/jniLibs/arm64-v8a/libtun2socks.so .
   ```

2. **Обновить `TunnelService.kt`**:
   - Воссоздать удалённый `buildVpnInterface()` c `VpnService.Builder`.
   - После `establish()` получить `ParcelFileDescriptor`, через `detachFd()` взять int.
   - Запустить `libtun2socks.so` через `ProcessBuilder` с аргументами:
     ```
     libtun2socks.so -device fd://<FD> -proxy socks5://127.0.0.1:1080 -loglevel warning
     ```
   - Передать FD дочернему процессу через `ProcessBuilder.redirectInput(ProcessBuilder.Redirect.from(...))` или через Unix domain socket (tun2socks поддерживает оба способа через флаги, но работа с FD на Android требует наследования — см. `FileDescriptor.fromFd()` + native set-closed-on-exec unset).
   - Параллельно запустить `libdnstt_client.so` как в Proxy-режиме — он слушает 127.0.0.1:1080, куда tun2socks пересылает SOCKS5.

3. **Обновить UI** (`SettingsActivity.kt`, `MainActivity.kt`) — снять заглушку «скоро» с VPN-чипа.

4. **Обновить манифест** — `android:foregroundServiceType="specialUse"` уже стоит, permission `BIND_VPN_SERVICE` тоже. Проверить, что `VpnService.prepare()` вызывается из UI.

Тонкие места:

- **Передача fd**: `ProcessBuilder` на JVM не даёт наследовать произвольный fd. Варианты: (а) создать Unix domain socket, передать fd через `SCM_RIGHTS`, tun2socks принимает через `-device unix:///path`; (б) написать JNI-обёртку, которая копирует fd в stdin/stdout дочернего процесса. Проще (а).
- **onRevoke/restart**: когда пользователь гасит VPN из настроек Android, приходит `onRevoke()` — надо убить оба subprocess и освободить fd.
- **DNS leaks**: в `Builder.addDnsServer("1.1.1.1")` надо указывать виртуальный DNS внутри tun, чтобы приложения не обходили туннель.

Когда всё заработает — уберите флаг `enabled = false` в `SettingsActivity.kt` → `ModeSelector` и снимите `stopSelf()` в `TunnelService.kt`.

### Парсинг статистики из dnstt-client

`dnstt-client` пишет в stderr диагностику, например:

```
2026/04/17 08:12:34 NXDOMAIN: 0 bytes are too short to contain a ClientID
2026/04/17 08:12:35 12345 bytes sent, 67890 bytes received
```

В `DnsttBridge.kt` stdout/stderr уже читается в корутине. Сейчас мы только пишем в logcat и ловим `error`/`fatal` в `lastError`. Можно добавить regex-парсер:

```kotlin
private val statRegex = Regex("""(\d+) bytes sent, (\d+) bytes received""")
// в цикле чтения строк:
statRegex.find(line)?.let {
    bytesSent.set(it.groupValues[1].toLong())
    bytesRecv.set(it.groupValues[2].toLong())
}
```

и прокидывать в `stats()`.

### Авто-переподключение при смене сети

```kotlin
val cm = getSystemService(ConnectivityManager::class.java)
cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) { DnsttBridge.stop(); DnsttBridge.startProxy(ctx, cfg) }
})
```

Поместить в `ProxyService.onCreate`, отменить в `onDestroy`. Тонкость — дождаться стабильной сети (~1 секунда), иначе будет рестарт на каждом хэндовере LTE.

---

## 4. Обновления

### Обновить AGP / Kotlin / Compose

В `android/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application") version "8.5.2" apply false          // ← бампать
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false    // ← бампать
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20" apply false
}
```

В `android/app/build.gradle.kts`:

```kotlin
val composeBom = platform("androidx.compose:compose-bom:2024.09.02")  // ← бампать
```

Проверить совместимость версий: [developer.android.com/jetpack/androidx/releases/compose-kotlin](https://developer.android.com/jetpack/androidx/releases/compose-kotlin).

### Обновить Gradle wrapper

```bash
cd android
./gradlew wrapper --gradle-version 8.11 --distribution-type bin
```

---

## 5. Troubleshooting сборки

### `cannot find runtime/cgo` при кросс-компиляции

Для `android/arm` и `android/amd64` Go требует CGO, значит нужен NDK clang. См. раздел «Добавить ABI».

### `Plugin not found: org.jetbrains.kotlin.plugin.serialization`

Плагин не прописан в корневом `build.gradle.kts` с `apply false`. Добавить:

```kotlin
id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20" apply false
```

### `error: attribute android:cx not found` в vector drawable

`<circle>` не поддерживается в Android vector drawables. Заменить на `<path>` с дугой:

```xml
<path android:pathData="M<cx>,<cy> m-<r>,0 a<r>,<r> 0 1,0 <2r>,0 a<r>,<r> 0 1,0 -<2r>,0"/>
```

### `@Composable invocations can only happen from the context of a @Composable function`

Вызов `stringResource(...)` (или другого composable) внутри `scope.launch { ... }` или другой не-composable лямбды. Фикс: вынести в локальную переменную до корутины.

### `Downloading https://services.gradle.org/distributions/gradle-8.10.2-bin.zip` висит

Первый запуск wrapper скачивает ~100 МБ + все плагины AGP/Kotlin (~1 ГБ). Последующие builds — секунды. Не отменяйте первый запуск раньше чем через 10–15 минут.

### `Execution failed for task ':app:stripDebugDebugSymbols'. Unable to strip libdnstt_client.so`

Это **warning**, не ошибка — Android не понял, как strip-нуть Go-бинарник, и упаковал его как есть. Нормально, бинарник уже stripped через `-ldflags='-s -w'` при сборке.

### APK устанавливается, но при запуске сервиса падает с `exec format error`

Архитектура процессора не совпадает с ABI бинарника. Проверить:

```bash
adb shell getprop ro.product.cpu.abi     # должно быть arm64-v8a
```

Если `armeabi-v7a` или `x86_64` — нужно собрать соответствующий бинарник (см. раздел про ABI) и пересобрать APK без `abiFilters`.

---

## 6. Структура Android-кода

```
android/app/src/main/
├── AndroidManifest.xml                               # permissions, сервисы
├── jniLibs/arm64-v8a/libdnstt_client.so              # нативный бинарник dnstt-client
├── kotlin/net/jivenet/client/
│   ├── MainActivity.kt         # UI главного экрана, кнопка ON/OFF
│   ├── MainViewModel.kt        # Flow с конфигом и статистикой, 1s polling
│   ├── SettingsActivity.kt     # форма настроек + QR-сканер
│   ├── DnsttBridge.kt          # ★ ProcessBuilder обёртка над libdnstt_client.so
│   ├── ProxyService.kt         # foreground-сервис, держит туннель в Proxy-режиме
│   ├── TunnelService.kt        # заглушка VpnService (VPN-режим в MVP выключен)
│   ├── Notifications.kt        # канал уведомлений + builder
│   ├── config/TunnelConfig.kt  # DataStore + JSON-парсер QR payload
│   └── ui/
│       ├── QrScanner.kt        # CameraX + ML Kit Barcode
│       └── StatsCard.kt        # Compose-карточка со статистикой
└── res/
    ├── drawable/ic_launcher_{bg,fg}.xml
    ├── mipmap-anydpi-v26/ic_launcher{,_round}.xml    # адаптивная иконка
    ├── values/{strings,themes}.xml
    └── xml/{network_security_config,data_extraction_rules}.xml
```

### Точка приложения правок — `DnsttBridge.kt`

Вся логика работы с нативным бинарником изолирована. Если:

- Поменялся формат CLI dnstt-client → правьте `buildCommand()`.
- Нужны новые флаги (например, `-utls`) → добавьте в `TunnelConfig` и прокиньте.
- Нужно логировать больше → расширяйте `readerJob` в `start()`.

Kotlin-код ничего не знает про Noise/KCP/smux — это всё у бинарника.

### Точка приложения правок — `server/install.sh`

При добавлении новой зависимости на сервере (например, нового форвард-цели) — добавляйте пакет в `apt install`, создавайте юнит в `systemd/`, установку в отдельный шаг install.sh.

---

## 7. Полезные команды

```bash
# Быстрая проверка работоспособности сервера
ssh user@vps 'sudo bash /path/to/jivenet/server/scripts/status.sh'

# Перепечатать QR
ssh user@vps 'sudo bash /path/to/jivenet/server/scripts/print-qr.sh'
ssh user@vps 'sudo bash /path/to/jivenet/server/scripts/print-qr.sh --doh https://dns.google/dns-query'
ssh user@vps 'sudo bash /path/to/jivenet/server/scripts/print-qr.sh --png /tmp/q.png' \
    && scp user@vps:/tmp/q.png . && open q.png

# Логи туннеля в реальном времени
ssh user@vps 'sudo journalctl -u dnstt-server -f'

# Полный adb-workflow для локальной разработки
cd android
./gradlew :app:installDebug   # собрать + установить на подключённое устройство
adb logcat | grep -E 'DnsttBridge|ProxyService|TunnelService'
```
