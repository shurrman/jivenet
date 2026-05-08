#!/usr/bin/env bash
# Кросс-компилирует dnstt-client под Android ABI и кладёт готовые бинарники
# в app/src/main/jniLibs/<abi>/libdnstt_client.so
#
# Требуется:
#   * ANDROID_NDK_HOME (путь к Android NDK r25+)
#   * Go 1.21+
#
# Почему имя libdnstt_client.so: Android PackageManager копирует только файлы
# из jniLibs/<abi>/ с префиксом "lib" и расширением ".so" в
# ApplicationInfo.nativeLibraryDir, откуда процесс имеет право на exec().
# Расширение .so тут фикция — файл является обычным ELF-бинарником.

set -euo pipefail

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
SRC=${DNSTT_SRC:-$HERE/dnstt-src}
OUT=$HERE/app/src/main/jniLibs

if [[ ! -d "$SRC/dnstt-client" ]]; then
    echo "error: исходники dnstt не найдены в $SRC" >&2
    echo "       git clone https://www.bamsoftware.com/git/dnstt.git $SRC" >&2
    exit 1
fi

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    # Пробуем найти NDK в SDK автоматически
    for sdk in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
        if [[ -n "$sdk" && -d "$sdk/ndk" ]]; then
            ANDROID_NDK_HOME=$(ls -d "$sdk/ndk"/* 2>/dev/null | sort -V | tail -1 || true)
            [[ -n "$ANDROID_NDK_HOME" ]] && break
        fi
    done
fi
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    echo "error: ANDROID_NDK_HOME не задан и не найден автоматически" >&2
    exit 1
fi
echo "NDK: $ANDROID_NDK_HOME"

# Определяем хост для toolchain (darwin-x86_64 работает и на arm64 через rosetta;
# аналогично linux-x86_64 — под Linux/WSL).
case "$(uname -s)" in
    Darwin) host=darwin-x86_64 ;;
    Linux)  host=linux-x86_64  ;;
    *)      echo "unsupported host: $(uname -s)"; exit 1 ;;
esac
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$host/bin"
if [[ ! -d "$TOOLCHAIN" ]]; then
    echo "error: toolchain не найден в $TOOLCHAIN" >&2
    exit 1
fi

API=${ANDROID_MIN_API:-26}

build() {
    local abi=$1 goarch=$2 clang=$3
    echo "=== $abi ($goarch) ==="
    mkdir -p "$OUT/$abi"
    # CGO_ENABLED=0 — не требует C-компилятора, работает на Android, и
    # DNS-резолвинг не задевает (HTTP DoH идёт через Go-реализацию с системным
    # IP resolver'ом — на Android используется android.net.*, проброшенный
    # через Go runtime).
    (
        cd "$SRC/dnstt-client"
        CGO_ENABLED=0 \
        GOOS=android \
        GOARCH=$goarch \
            go build -trimpath -ldflags='-s -w' \
            -o "$OUT/$abi/libdnstt_client.so" .
    )
    ls -lh "$OUT/$abi/libdnstt_client.so"
}

# Android ABI ↔ Go architecture:
#   arm64-v8a     → arm64  (современные телефоны, 95%+ устройств)
#   armeabi-v7a   → arm    (старые 32-bit)
#   x86_64        → amd64  (эмулятор, Chromebooks)
build arm64-v8a   arm64 aarch64-linux-android${API}-clang
build armeabi-v7a arm   armv7a-linux-androideabi${API}-clang
build x86_64      amd64 x86_64-linux-android${API}-clang

echo
echo "готово:"
find "$OUT" -name libdnstt_client.so -exec ls -lh {} \;
