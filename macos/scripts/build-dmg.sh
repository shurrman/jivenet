#!/usr/bin/env bash
# Делает DMG из собранной .app — это удобнее zip'а: юзер открывает .dmg,
# видит Finder-окно с jivenet.app и shortcut'ом на /Applications, тащит
# одно в другое — установлено.
#
# Usage:
#   ./scripts/build-dmg.sh   # создаёт macos/build/jivenet-<version>.dmg

set -euo pipefail

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
ROOT=$(cd -- "$HERE/.." && pwd)
BUILD_DIR=$HERE/build
APP=$BUILD_DIR/jivenet.app

if [[ ! -d "$APP" ]]; then
    echo "→ .app не найден, собираю…"
    bash "$HERE/scripts/build-app.sh" release
fi

VERSION=$(grep -oE 'versionName = "[^"]*"' "$ROOT/android/app/build.gradle.kts" \
    | head -1 | sed -E 's/.*"([^"]+)".*/\1/')
DMG=$BUILD_DIR/jivenet-${VERSION}.dmg
echo "→ $DMG"

# Свежая raw stage-папка с тем что попадёт в DMG
STAGE=$(mktemp -d -t jivenet-dmg)
trap 'rm -rf "$STAGE"' EXIT
cp -R "$APP" "$STAGE/"
ln -s /Applications "$STAGE/Applications"

# hdiutil — встроенный инструмент macOS, не требует brew install create-dmg.
# Создаём UDZO-формат (compressed), заодно даём имя volume = "jivenet X.Y.Z"
# которое будет видно в Finder.
rm -f "$DMG"
hdiutil create \
    -volname "jivenet $VERSION" \
    -srcfolder "$STAGE" \
    -ov -format UDZO \
    -fs HFS+ \
    "$DMG" >/dev/null

echo
echo "================================================================"
echo "  готово: $DMG ($(du -h "$DMG" | awk '{print $1}'))"
echo "================================================================"
echo
echo "Открыть:   open $DMG"
echo "Установить: dragging jivenet.app → /Applications в Finder."
