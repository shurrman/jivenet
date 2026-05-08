#!/usr/bin/env bash
# Собирает tun2socks.aar через gomobile bind и кладёт его в app/libs/.
# Используется для VPN-режима (TunnelService → VpnService → TUN-fd → tun2socks → SOCKS5 dnstt).
#
# Зачем gomobile, а не ProcessBuilder: JVM не передаёт произвольные fd
# дочерним процессам. Поэтому tun2socks работает в том же процессе APK
# через JNI — fd передаётся как простой long-параметр.
#
# Требуется:
#   * Android NDK (ANDROID_NDK_HOME или authopick из ANDROID_HOME/ndk)
#   * Go 1.21+
#   * gomobile установлен (`go install golang.org/x/mobile/cmd/gomobile@latest`)

set -euo pipefail

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
SRC="$HERE/tun2socks-mobile"
OUT="$HERE/app/libs/tun2socks.aar"

if [[ ! -d "$SRC" ]]; then
    echo "error: исходники tun2socks-mobile не найдены в $SRC" >&2
    exit 1
fi

# 1) NDK
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

# 2) gomobile
GOPATH=$(go env GOPATH)
GOMOBILE="$GOPATH/bin/gomobile"
if [[ ! -x "$GOMOBILE" ]]; then
    echo "Устанавливаю gomobile…"
    GO111MODULE=on go install golang.org/x/mobile/cmd/gomobile@latest
    GO111MODULE=on go install golang.org/x/mobile/cmd/gobind@latest
fi
"$GOMOBILE" init 2>/dev/null || true

# 3) bind
mkdir -p "$HERE/app/libs"
cd "$SRC"
"$GOMOBILE" bind \
    -target=android/arm64 \
    -androidapi 26 \
    -trimpath \
    -ldflags='-s -w' \
    -o "$OUT" \
    .

echo "готово:"
ls -lh "$OUT"
