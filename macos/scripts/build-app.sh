#!/usr/bin/env bash
# Сборка macos/jivenet.app — полный цикл:
#   1. cross-compile dnstt-client (universal arm64+amd64)
#   2. swift build -c release (universal через `arm64;x86_64` arch'ы)
#   3. упаковка в bundle .app/Contents/{MacOS, Resources, Info.plist}
#   4. ad-hoc codesign (без notarization — юзер откроет «правый клик → Open»
#      первый раз, дальше Gatekeeper запоминает)
#
# Output: macos/build/jivenet.app
#
# Usage:
#   ./scripts/build-app.sh                 # debug-сборка для тестов
#   ./scripts/build-app.sh release         # release (-O, оптимизированная)

set -euo pipefail

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
ROOT=$(cd -- "$HERE/.." && pwd)
APP_DIR=$HERE/app
BUILD_DIR=$HERE/build
APP=$BUILD_DIR/jivenet.app

BUILD_TYPE=${1:-release}
SWIFT_CONFIG=$([[ "$BUILD_TYPE" == "release" ]] && echo "release" || echo "debug")

VERSION=$(grep -oE 'versionName = "[^"]*"' "$ROOT/android/app/build.gradle.kts" \
    | head -1 | sed -E 's/.*"([^"]+)".*/\1/')
echo "→ version $VERSION ($BUILD_TYPE)"

# 1) dnstt-client universal binary
if [[ ! -x "$HERE/app/Resources/dnstt-client" ]]; then
    echo "→ собираю dnstt-client (universal)…"
    bash "$HERE/scripts/build-dnstt-darwin.sh"
else
    echo "→ dnstt-client уже собран ($(du -h "$HERE/app/Resources/dnstt-client" | awk '{print $1}'))"
fi

# 2) Swift build — universal через --arch
echo "→ swift build ($SWIFT_CONFIG)…"
(
    cd "$APP_DIR"
    swift build -c "$SWIFT_CONFIG" \
        --arch arm64 --arch x86_64 \
        2>&1 | tail -8
)

# Swift кладёт в Products/Release/ или Products/Debug/ (Capitalized). Простой
# bash-апкейс первой буквы: ${var^} требует bash 4+, на macOS 3.2 — обходим
# через python.
SWIFT_CONFIG_CAP=$(python3 -c "import sys; print(sys.argv[1].capitalize())" "$SWIFT_CONFIG")
EXEC="$APP_DIR/.build/apple/Products/$SWIFT_CONFIG_CAP/jivenet"
if [[ ! -x "$EXEC" ]]; then
    echo "error: swift не положил universal-бинарник в $EXEC" >&2
    exit 1
fi
file "$EXEC" | head -1

# 3) Bundle .app
echo "→ собираю .app bundle…"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"

cp "$EXEC" "$APP/Contents/MacOS/jivenet"
cp "$HERE/app/Resources/dnstt-client" "$APP/Contents/Resources/dnstt-client"
chmod +x "$APP/Contents/MacOS/jivenet" "$APP/Contents/Resources/dnstt-client"

# Info.plist — LSUIElement=true делает приложение «accessory» (только меню-бар,
# без иконки в Dock и без главного окна). NSHighResolutionCapable=true — для
# Retina-дисплеев (по умолчанию выключен в bare-bones bundle).
cat > "$APP/Contents/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleDevelopmentRegion</key><string>ru</string>
    <key>CFBundleExecutable</key><string>jivenet</string>
    <key>CFBundleIdentifier</key><string>net.jivenet.client</string>
    <key>CFBundleInfoDictionaryVersion</key><string>6.0</string>
    <key>CFBundleName</key><string>jivenet</string>
    <key>CFBundleDisplayName</key><string>jivenet</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleSignature</key><string>????</string>
    <key>CFBundleShortVersionString</key><string>$VERSION</string>
    <key>CFBundleVersion</key><string>$VERSION</string>
    <key>LSMinimumSystemVersion</key><string>14.0</string>
    <key>LSUIElement</key><true/>
    <key>NSHighResolutionCapable</key><true/>
    <key>NSHumanReadableCopyright</key><string>GPL-3.0 jivenet authors</string>
    <!-- Камера нужна для QR-сканера в Настройках. Без этого ключа
         AVCaptureDevice.requestAccess мгновенно возвращает denied и
         macOS убивает процесс при первой попытке. -->
    <key>NSCameraUsageDescription</key>
    <string>Камера используется только для сканирования QR-кода с конфигом jivenet (домен и pubkey сервера). Кадры никуда не отправляются.</string>
</dict>
</plist>
EOF

# PkgInfo — старый OSType-маркер, всё ещё ожидается некоторыми утилитами
echo -n "APPL????" > "$APP/Contents/PkgInfo"

# 4) Ad-hoc подпись. Без неё приложение запускается, но Gatekeeper кричит
# про «не удалось проверить разработчика». Ad-hoc делает приложение
# «notarizable-ready» (notarization требует Apple Developer Program — пропускаем
# на v0.9.5).
echo "→ ad-hoc codesign…"
codesign --force --deep --sign - "$APP" 2>&1 | head -3

echo
echo "================================================================"
echo "  готово: $APP ($(du -sh "$APP" | awk '{print $1}'))"
echo "================================================================"
echo
echo "Установка:"
echo "  cp -R $APP /Applications/"
echo "  open /Applications/jivenet.app"
echo
echo "Первый запуск: правый клик → Open (обходит Gatekeeper для unnotarized app)."
