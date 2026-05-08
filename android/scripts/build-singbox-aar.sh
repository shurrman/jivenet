#!/usr/bin/env bash
# Собирает libbox.aar (sing-box experimental/libbox) для Android через
# gomobile bind. Кладёт результат в android/app/libs/libbox.aar.
#
# sing-box используется как замена tun2socks: даёт TUN-инбаунд,
# встроенный DoH-резолвер с FakeIP (фиксит DNS-leak), monitor сетевых
# изменений (auto-reconnect handled by sing-box itself).
#
# Зависимости:
#   * Go 1.24+ (sing-box требует именно эту версию). На macOS подгружаем
#     отдельный установленный go из ~/go-1.25/bin (как для tun2socks).
#   * Android NDK (через ANDROID_NDK_HOME / ANDROID_SDK_ROOT/ndk/*)
#   * gomobile форка sagernet (sing-box использует свой fork с правками)
#   * JDK 17+ (gomobile bind вызывает javac)
#
# Источники (не коммитятся):
#   git clone --depth=1 https://github.com/SagerNet/sing-box.git \
#       android/sing-box-src
#
# Сборка занимает 5-15 мин. После успеха libbox.aar ~ 60 МБ — это OK,
# Gradle при сборке APK выкинет неиспользуемое (R8/ProGuard).
#
# Тэги (точно как в официальном Makefile):
#   with_gvisor   — userspace TCP/IP стек для TUN inbound (нужен!)
#   with_quic     — QUIC outbound и DoH/DoQ
#   with_wireguard — wireguard outbound (опционально, можно убрать для размера)
#   with_utls     — uTLS-фингерпринты для DoH
#   with_clash_api — управление через REST (можно отключить, нам не нужно)

set -euo pipefail

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
SRC="$HERE/sing-box-src"
OUT="$HERE/app/libs/libbox.aar"

if [[ ! -d "$SRC" ]]; then
    echo "error: $SRC не найден"
    echo "       выполните: git clone --depth=1 https://github.com/SagerNet/sing-box.git $SRC"
    exit 1
fi

# 1) Go 1.24+
GOBIN=""
if [[ -x "$HOME/go-1.25/bin/go" ]]; then
    GOBIN="$HOME/go-1.25/bin"
elif [[ -x "$HOME/go-1.24/bin/go" ]]; then
    GOBIN="$HOME/go-1.24/bin"
fi
if [[ -n "$GOBIN" ]]; then
    export PATH="$GOBIN:$PATH"
fi
# $GOPATH/bin должен быть в PATH, иначе gomobile не найдёт gobind
# (он ищет его через exec.LookPath).
export PATH="$(go env GOPATH)/bin:$PATH"
GO_VERSION=$(go version | awk '{print $3}')
echo "Go: $GO_VERSION"
case "$GO_VERSION" in
    go1.24*|go1.25*|go1.26*|go1.27*) ;;
    *) echo "error: требуется Go 1.24+, установлен $GO_VERSION" >&2; exit 1 ;;
esac

# 2) NDK
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    for sdk in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
        if [[ -n "$sdk" && -d "$sdk/ndk" ]]; then
            ANDROID_NDK_HOME=$(ls -d "$sdk/ndk"/* 2>/dev/null | sort -V | tail -1 || true)
            [[ -n "$ANDROID_NDK_HOME" ]] && break
        fi
    done
fi
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    echo "error: ANDROID_NDK_HOME не задан" >&2
    exit 1
fi
export ANDROID_NDK_HOME
echo "NDK: $ANDROID_NDK_HOME"

# 3) gomobile форка sagernet (отличается от upstream — sing-box без него
#    не собирается из-за патчей по multipathtcp и проч.)
GOPATH=$(go env GOPATH)
GOMOBILE="$GOPATH/bin/gomobile"
need_install=true
if [[ -x "$GOMOBILE" ]]; then
    # Проверим, что это форк sagernet (по выводу --help у форка есть -libname).
    if "$GOMOBILE" bind --help 2>&1 | grep -q "libname"; then
        need_install=false
    fi
fi
if $need_install; then
    echo "Устанавливаю sagernet/gomobile..."
    GO111MODULE=on go install -v github.com/sagernet/gomobile/cmd/gomobile@v0.1.12
    GO111MODULE=on go install -v github.com/sagernet/gomobile/cmd/gobind@v0.1.12
fi
echo "gomobile init..."
"$GOMOBILE" init

# 4) bind
mkdir -p "$HERE/app/libs"
cd "$SRC"

TAGS="with_gvisor,with_quic,with_utls,badlinkname,tfogo_checklinkname0"
LDFLAGS="-X internal/godebug.defaultGODEBUG=multipathtcp=0 -s -w -buildid= -checklinkname=0"

echo "gomobile bind (tags=$TAGS)..."
"$GOMOBILE" bind \
    -v \
    -target=android/arm64 \
    -androidapi 26 \
    -javapkg=io.nekohasekai \
    -libname=box \
    -trimpath \
    -buildvcs=false \
    -ldflags "$LDFLAGS" \
    -tags "$TAGS" \
    -o "$OUT" \
    ./experimental/libbox

echo "готово:"
ls -lh "$OUT"
