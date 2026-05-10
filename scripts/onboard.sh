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
# Всегда зовём gradle сами: assembleDebug идемпотентный (UP-TO-DATE если
# ничего не менялось, ~1с), но гарантирует что мы шипим текущий код.
# До v0.9.5 скрипт сначала смотрел в `$HERE/jivenet-debug.apk` (root-level
# stale-копия) и только потом в build/outputs — на v0.9.4-релизе это
# вылилось в загрузку 40-МБ APK от прошлого билда вместо свежего 42-МБ.
echo "сборка APK (gradle assembleDebug)…"
( cd "$HERE/android" && ./gradlew :app:assembleDebug ) | tail -5

DEBUG_APK="$HERE/android/app/build/outputs/apk/debug/app-debug.apk"
RELEASE_APK="$HERE/android/app/build/outputs/apk/release/app-release.apk"
# Release APK предпочтителен (R8-минифицирован, меньше), debug — fallback.
if [[ -f "$RELEASE_APK" ]]; then
    APK_SRC=$RELEASE_APK
elif [[ -f "$DEBUG_APK" ]]; then
    APK_SRC=$DEBUG_APK
else
    echo "error: APK не найден после сборки — что-то пошло не так в gradle" >&2
    echo "       проверь: cd android && ./gradlew :app:assembleDebug" >&2
    exit 1
fi

# Сверка version в APK с build.gradle.kts: ловит ситуации когда gradle
# вернул UP-TO-DATE на закешированный APK от прошлой версии (бывает на
# некоторых конфигурациях build cache).
GRADLE_VER=$(grep -oE 'versionName = "[^"]*"' "$HERE/android/app/build.gradle.kts" \
    | head -1 | sed -E 's/.*"([^"]+)".*/\1/')
# aapt лежит в Android SDK build-tools, не в PATH. Ищем по ANDROID_HOME
# либо по дефолтным путям macOS/Linux. Если не нашли — version-check skip
# (это belt-and-suspenders, основной защиты достаточно gradle build выше).
APK_VER=""
AAPT=""
if command -v aapt >/dev/null 2>&1; then
    AAPT=$(command -v aapt)
elif command -v aapt2 >/dev/null 2>&1; then
    AAPT=$(command -v aapt2)
else
    for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
               "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
        [[ -z "$sdk" || ! -d "$sdk/build-tools" ]] && continue
        # самая свежая build-tools (последняя по сортировке)
        bt=$(ls "$sdk/build-tools" | sort -V | tail -1)
        [[ -x "$sdk/build-tools/$bt/aapt" ]] && { AAPT=$sdk/build-tools/$bt/aapt; break; }
    done
fi
if [[ -n "$AAPT" ]]; then
    APK_VER=$("$AAPT" dump badging "$APK_SRC" 2>/dev/null \
        | awk -F"'" '/^package:/{for(i=1;i<=NF;i++) if($i ~ /versionName=/) print $(i+1)}')
fi
if [[ -n "$APK_VER" && -n "$GRADLE_VER" && "$APK_VER" != "$GRADLE_VER" ]]; then
    echo "error: APK versionName='$APK_VER' ≠ build.gradle '$GRADLE_VER'" >&2
    echo "       вероятно gradle взял закешированный APK. Запусти:" >&2
    echo "       cd android && ./gradlew :app:clean :app:assembleDebug" >&2
    exit 1
fi
echo "ok: APK = $APK_SRC ($(du -h "$APK_SRC" | awk '{print $1}'), version=${APK_VER:-?})"

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

    # Выдёргиваем из CHANGELOG.md только секцию текущей версии — иначе на
    # GitHub Release появлялся бы весь changelog включая v0.1.0 с
    # «VPN-режим заглушён» и пр. историческими ограничениями, которые
    # юзер при скролле читает как «текущее состояние».
    NOTES_FILE=$(mktemp -t jivenet-notes.XXXXXX)
    trap 'rm -f "$NOTES_FILE"' EXIT
    awk -v ver="${RELEASE_TAG#v}" '
        # Начало нашей секции — печатаем
        $0 ~ "^## "ver"( |$|—)" { in_section=1; print; next }
        # Следующая секция (любая другая ## ...) — выходим
        in_section && /^## / { exit }
        in_section { print }
    ' "$HERE/CHANGELOG.md" > "$NOTES_FILE"
    if [[ ! -s "$NOTES_FILE" ]]; then
        echo "warning: в CHANGELOG.md нет секции для $RELEASE_TAG, кладу полный файл" >&2
        cp "$HERE/CHANGELOG.md" "$NOTES_FILE"
    fi
    # Хвост со ссылкой на полный CHANGELOG в репо
    cat >> "$NOTES_FILE" <<EOF

---

История всех версий: [CHANGELOG.md](https://github.com/shurrman/jivenet/blob/main/CHANGELOG.md).
EOF

    if ! gh release view "$RELEASE_TAG" >/dev/null 2>&1; then
        echo "создаю релиз $RELEASE_TAG…"
        gh release create "$RELEASE_TAG" \
            --title "$RELEASE_TAG" \
            --notes-file "$NOTES_FILE" \
            "$OUT/jivenet.apk" "$OUT/jivenet-config.png" "$OUT/INSTALL.txt"
    else
        echo "релиз уже есть, обновляю notes и прикрепляю файлы…"
        gh release edit "$RELEASE_TAG" --notes-file "$NOTES_FILE"
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
