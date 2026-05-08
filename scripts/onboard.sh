#!/usr/bin/env bash
# Готовит «бандл онбординга» для нового пользователя:
#   onboarding/
#     ├─ jivenet.apk            ← APK для установки на Android
#     ├─ jivenet-config.png     ← QR с конфигом, отсканировать в приложении
#     ├─ jivenet-config.json    ← тот же конфиг текстом (на случай если QR не отсканится)
#     └─ INSTALL.txt            ← пошаговая инструкция
#
# Опционально (если стоит `gh` CLI и есть права на репо) загружает APK + PNG
# в GitHub Release — пользователь скачает по короткой ссылке, не нужно тащить
# 32 МБ через мессенджер.
#
# Usage:
#   ./scripts/onboard.sh                            # локальный bundle
#   ./scripts/onboard.sh --release v0.2.0           # + публикация в GitHub Release
#   ./scripts/onboard.sh --doh https://1.1.1.1/dns-query
#                                                    # переопределить DoH в QR
#   ./scripts/onboard.sh --mode vpn|proxy           # mode в конфиге (default vpn)

set -euo pipefail

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
OUT="$HERE/onboarding"

# --- параметры ----------------------------------------------------------------
DOH="https://1.1.1.1/dns-query"
MODE="vpn"
RELEASE_TAG=""
SSH_USER=wsoft
SSH_HOST=93.77.166.152

while [[ $# -gt 0 ]]; do
    case "$1" in
        --doh) DOH=$2; shift 2 ;;
        --mode) MODE=$2; shift 2 ;;
        --release) RELEASE_TAG=$2; shift 2 ;;
        --ssh) SSH_USER_HOST=$2; SSH_USER=${SSH_USER_HOST%@*}; SSH_HOST=${SSH_USER_HOST#*@}; shift 2 ;;
        -h|--help)
            sed -n '2,18p' "$0"; exit 0 ;;
        *) echo "error: unknown arg $1" >&2; exit 2 ;;
    esac
done

# --- источник APK ------------------------------------------------------------
APK_SRC=""
for c in \
    "$HERE/jivenet-debug.apk" \
    "$HERE/android/app/build/outputs/apk/release/app-release.apk" \
    "$HERE/android/app/build/outputs/apk/debug/app-debug.apk" ; do
    [[ -f "$c" ]] && APK_SRC=$c && break
done
if [[ -z "$APK_SRC" ]]; then
    echo "error: APK не найден. Сначала соберите:" >&2
    echo "       cd android && ./gradlew :app:assembleDebug" >&2
    exit 1
fi
echo "ok: APK = $APK_SRC ($(du -h "$APK_SRC" | awk '{print $1}'))"

# --- конфиг с сервера ---------------------------------------------------------
echo "запрос конфига с сервера ${SSH_USER}@${SSH_HOST}…"
JSON=$(ssh -o ConnectTimeout=5 -l "$SSH_USER" "$SSH_HOST" \
    "sudo bash /home/wsoft/jivenet-server/scripts/print-qr.sh \
        --json-only --doh '$DOH' --mode '$MODE'" 2>/dev/null)
if [[ -z "$JSON" || "$JSON" != \{* ]]; then
    echo "error: не получил JSON-конфиг с сервера" >&2
    echo "       проверьте доступ: ssh -l ${SSH_USER} ${SSH_HOST}" >&2
    exit 1
fi
echo "ok: конфиг = $JSON"

# --- собираем bundle ----------------------------------------------------------
rm -rf "$OUT"
mkdir -p "$OUT"
cp "$APK_SRC" "$OUT/jivenet.apk"
echo "$JSON" > "$OUT/jivenet-config.json"
qrencode -t PNG -s 10 -o "$OUT/jivenet-config.png" -- "$JSON"

VERSION=$(grep -oE 'versionName = "[^"]*"' "$HERE/android/app/build.gradle.kts" | head -1 | sed -E 's/.*"([^"]+)".*/\1/' || echo unknown)
PUBKEY=$(echo "$JSON" | python3 -c 'import sys,json; print(json.load(sys.stdin)["pubkey"])')
DOMAIN=$(echo "$JSON" | python3 -c 'import sys,json; print(json.load(sys.stdin)["domain"])')

cat > "$OUT/INSTALL.txt" <<EOF
jivenet — установка на Android
================================

Версия APK:    ${VERSION:-unknown}
Tunnel domain: $DOMAIN
Public key:    $PUBKEY
DoH resolver:  $DOH
Режим:         $MODE

ШАГИ
----

1. Установите APK:
     - перешлите jivenet.apk на телефон (мессенджер / Bluetooth / USB);
     - откройте файл, разрешите «Установка из неизвестных источников»;
     - подтвердите установку.

2. Импортируйте конфиг:
     - откройте «jivenet» → шестерёнка ⚙ (настройки);
     - нажмите «Сканировать QR» → отсканируйте jivenet-config.png
       (можно прислать на телефон и сканировать с экрана другого устройства);
     - либо введите поля из jivenet-config.json вручную;
     - нажмите «Сохранить».

3. Подключитесь:
     - вернитесь на главный экран → «Подключить»;
     - Android спросит «Разрешить VPN-соединение» → согласиться;
     - в статус-баре появится значок ключа VPN, весь трафик идёт через сервер.

4. Проверка:
     - откройте https://ifconfig.co в любом браузере;
     - должен показать IP сервера.

ЕСЛИ ИНТЕРНЕТ НЕ РАБОТАЕТ НА МОБИЛЬНОЙ СЕТИ
-------------------------------------------

Российские операторы блокируют публичные DoH-резолверы. В приложении:
шестерёнка → DoH resolver → выбрать чип «Авто UDP» (первый в списке).
Это переключит туннель на UDP через DNS оператора. После Сохранить →
Отключить → Подключить.

ТРЕБОВАНИЯ
----------

- Android 8.0 (API 26) или новее.
- Процессор arm64-v8a (практически все устройства 2015+).
- ~33 MB свободного места.
EOF

# Архив для удобства (по желанию)
( cd "$HERE" && zip -qr "$OUT/jivenet-onboarding.zip" "$(basename "$OUT")" -x "*/jivenet-onboarding.zip" )

echo
echo "================================================================================"
echo "  bundle готов:  $OUT"
echo "================================================================================"
ls -lh "$OUT"
echo
echo "Передайте получателю любым способом:"
echo "  - jivenet.apk + jivenet-config.png (минимум — APK и QR)"
echo "  - jivenet-onboarding.zip (всё одним архивом)"
echo "  - INSTALL.txt — пошаговая инструкция"

# --- GitHub Release (опционально) ---------------------------------------------
if [[ -n "$RELEASE_TAG" ]]; then
    echo
    echo "=== загружаю в GitHub Release $RELEASE_TAG ==="
    if ! command -v gh >/dev/null; then
        echo "error: gh CLI не установлен (brew install gh)" >&2
        exit 1
    fi
    if ! gh auth status >/dev/null 2>&1; then
        echo "сначала залогиньтесь: gh auth login" >&2
        exit 1
    fi
    if ! gh release view "$RELEASE_TAG" >/dev/null 2>&1; then
        echo "создаю релиз $RELEASE_TAG…"
        gh release create "$RELEASE_TAG" \
            --title "$RELEASE_TAG" \
            --notes-file "$HERE/CHANGELOG.md" \
            "$OUT/jivenet.apk" "$OUT/jivenet-config.png" "$OUT/INSTALL.txt"
    else
        echo "релиз уже есть, прикрепляю файлы…"
        gh release upload "$RELEASE_TAG" --clobber \
            "$OUT/jivenet.apk" "$OUT/jivenet-config.png" "$OUT/INSTALL.txt"
    fi
    BASE="https://github.com/shurrman/jivenet/releases/download/$RELEASE_TAG"
    PAGE="https://github.com/shurrman/jivenet/releases/tag/$RELEASE_TAG"
    echo
    echo "ok: релиз опубликован."
    echo
    echo "Страница релиза:"
    echo "  $PAGE"
    echo
    echo "Прямые ссылки на скачивание (отправлять получателю):"
    echo "  APK:    $BASE/jivenet.apk"
    echo "  QR:     $BASE/jivenet-config.png"
    echo "  INSTALL: $BASE/INSTALL.txt"
fi
