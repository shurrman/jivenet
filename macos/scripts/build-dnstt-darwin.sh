#!/usr/bin/env bash
# Кросс-компилирует dnstt-client под darwin/arm64 + darwin/amd64,
# склеивает через `lipo` в universal binary и кладёт в
# macos/app/Resources/dnstt-client.
#
# Используется при сборке .app — бинарник едет внутри
# Contents/Resources/ и запускается из Swift через Process().
#
# Требуется:
#   * Go 1.21+ (brew install go)
#   * Xcode CLI tools (lipo)
#   * Исходники dnstt в android/dnstt-src/ (общие с Android, не дублируем)

set -euo pipefail

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
ROOT=$(cd -- "$HERE/.." && pwd)
SRC=${DNSTT_SRC:-$ROOT/android/dnstt-src}
OUT_DIR=$HERE/app/Resources
OUT=$OUT_DIR/dnstt-client

if [[ ! -d "$SRC/dnstt-client" ]]; then
    echo "error: исходники dnstt не найдены в $SRC" >&2
    echo "       git clone https://www.bamsoftware.com/git/dnstt.git $SRC" >&2
    exit 1
fi
if ! command -v go >/dev/null; then
    echo "error: go не установлен (brew install go)" >&2
    exit 1
fi
if ! command -v lipo >/dev/null; then
    echo "error: lipo не найден (поставь Xcode Command Line Tools)" >&2
    exit 1
fi

mkdir -p "$OUT_DIR"

build_arch() {
    local arch=$1 goarch=$2
    local out=$OUT.$arch
    echo "→ darwin/$goarch"
    (
        cd "$SRC/dnstt-client"
        # CGO выключаем — собираем static-binary, без зависимостей от
        # libSystem-версионирования. Размер итогового файла ~9 MB,
        # запускается на macOS 11+.
        CGO_ENABLED=0 GOOS=darwin GOARCH=$goarch \
            go build -trimpath -ldflags='-s -w' \
            -o "$out" .
    )
    if [[ ! -x "$out" ]]; then
        echo "error: $out не собрался" >&2; exit 1
    fi
    echo "  size: $(du -h "$out" | awk '{print $1}')"
}

build_arch arm64 arm64
build_arch x86_64 amd64

echo "→ universal (lipo)"
lipo -create -output "$OUT" "$OUT.arm64" "$OUT.x86_64"
chmod +x "$OUT"
rm -f "$OUT.arm64" "$OUT.x86_64"

echo
echo "ok: $OUT"
file "$OUT"
echo "size: $(du -h "$OUT" | awk '{print $1}')"
