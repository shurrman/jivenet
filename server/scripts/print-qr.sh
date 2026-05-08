#!/usr/bin/env bash
# Печатает JSON-конфиг и QR-код для импорта в Android-приложение jivenet.
#
# Читает TUNNEL_DOMAIN из /etc/dnstt/server.env и публичный ключ из
# /etc/dnstt/server.pub. DoH resolver можно переопределить аргументом или
# переменной окружения DOH.
#
# Usage:
#   ./print-qr.sh                                   # QR в терминал (ansiutf8)
#   ./print-qr.sh --doh https://dns.google/dns-query
#   ./print-qr.sh --png /tmp/jivenet.png            # PNG вместо ANSI
#   ./print-qr.sh --json-only                       # только JSON без QR
#   ./print-qr.sh --utf8                            # явно UTF-8 (по умолчанию)
#   ./print-qr.sh --compact                         # плотнее ANSI (ansi256)

set -euo pipefail

ENV_FILE=${ENV_FILE:-/etc/dnstt/server.env}
PUB_FILE=${PUB_FILE:-/etc/dnstt/server.pub}
DOH=${DOH:-https://1.1.1.1/dns-query}
MODE=proxy     # MVP: в приложении работает только proxy. VPN — «скоро».
FORMAT=ansiutf8
PNG_OUT=""
JSON_ONLY=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --doh) DOH=$2; shift 2 ;;
        --doh=*) DOH=${1#*=}; shift ;;
        --png) PNG_OUT=$2; shift 2 ;;
        --png=*) PNG_OUT=${1#*=}; shift ;;
        --mode) MODE=$2; shift 2 ;;
        --mode=*) MODE=${1#*=}; shift ;;
        --utf8) FORMAT=ansiutf8; shift ;;
        --compact) FORMAT=ansi256; shift ;;
        --ascii) FORMAT=ansi; shift ;;
        --json-only) JSON_ONLY=1; shift ;;
        -h|--help)
            sed -n '3,15p' "$0"; exit 0 ;;
        *)
            echo "error: неизвестный аргумент: $1" >&2
            echo "       --help для справки" >&2
            exit 2 ;;
    esac
done

[[ -r "$ENV_FILE" ]] || { echo "error: не читается $ENV_FILE (sudo?)" >&2; exit 1; }
[[ -r "$PUB_FILE" ]] || { echo "error: не читается $PUB_FILE (sudo?)" >&2; exit 1; }

# shellcheck disable=SC1090
. "$ENV_FILE"
: "${TUNNEL_DOMAIN:?TUNNEL_DOMAIN не задан в $ENV_FILE}"

PUBKEY=$(tr -d ' \t\r\n' < "$PUB_FILE")
[[ ${#PUBKEY} -eq 64 ]] || { echo "error: pubkey длиной ${#PUBKEY} вместо 64" >&2; exit 1; }
[[ "$PUBKEY" =~ ^[0-9a-fA-F]+$ ]] || { echo "error: pubkey не hex" >&2; exit 1; }

# Формат JSON обязан совпадать с тем, что парсит Android (TunnelConfig.fromJson):
#   {"domain":"...","pubkey":"...","doh":"...","mode":"proxy|vpn","localPort":1080}
# localPort опционален, не включаем — приложение возьмёт дефолт 1080.
JSON=$(printf '{"domain":"%s","pubkey":"%s","doh":"%s","mode":"%s"}' \
    "$TUNNEL_DOMAIN" "$PUBKEY" "$DOH" "$MODE")

if [[ $JSON_ONLY -eq 1 ]]; then
    echo "$JSON"
    exit 0
fi

cat <<SUMMARY
Tunnel domain : $TUNNEL_DOMAIN
Public key    : $PUBKEY
DoH resolver  : $DOH
Mode          : $MODE

JSON:
    $JSON

SUMMARY

if ! command -v qrencode >/dev/null; then
    echo "warn: qrencode не установлен — сканировать QR можно только через JSON вручную" >&2
    echo "      установка: sudo apt install qrencode" >&2
    exit 0
fi

if [[ -n "$PNG_OUT" ]]; then
    qrencode -t PNG -o "$PNG_OUT" <<<"$JSON"
    echo "QR сохранён: $PNG_OUT"
    echo "Чтобы открыть на Mac:"
    echo "    scp $(whoami)@\$(hostname -I | awk '{print \$1}'):$PNG_OUT ."
    echo "    open $(basename "$PNG_OUT")"
else
    echo "QR-код (сканировать в приложении jivenet → Настройки → Сканировать QR):"
    echo
    qrencode -t "$FORMAT" <<<"$JSON"
fi
