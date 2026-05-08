#!/usr/bin/env bash
# Генерирует ключевую пару для dnstt-server. Приватный ключ кладётся
# в /etc/dnstt/server.key (только root:dnstt, mode 0640). Публичный
# выводится в stdout и дописывается в /etc/dnstt/server.pub.
set -euo pipefail

KEY_DIR=${KEY_DIR:-/etc/dnstt}
PRIV="$KEY_DIR/server.key"
PUB="$KEY_DIR/server.pub"

if [[ $EUID -ne 0 ]]; then
    echo "error: must be run as root" >&2
    exit 1
fi

if ! command -v dnstt-server >/dev/null 2>&1; then
    echo "error: dnstt-server not in PATH (install первый)" >&2
    exit 1
fi

mkdir -p "$KEY_DIR"

if [[ -s "$PRIV" ]]; then
    echo "info: $PRIV уже существует — ключи не перегенерируются" >&2
    # Выведем существующий публичный ключ.
    if [[ -s "$PUB" ]]; then
        cat "$PUB"
    else
        # Восстановить публичный из приватного.
        dnstt-server -privkey-file "$PRIV" -pubkey >"$PUB"
        chmod 0644 "$PUB"
        cat "$PUB"
    fi
    exit 0
fi

# dnstt-server -gen-key печатает две строки: privkey и pubkey (hex).
# В современных версиях вывод идёт в stdout; если флаги отличаются —
# скрипт упадёт и пользователь увидит ошибку.
tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT

dnstt-server -gen-key -privkey-file "$PRIV" -pubkey-file "$PUB"
chmod 0640 "$PRIV"
chown root:dnstt "$PRIV"
chmod 0644 "$PUB"

echo "Public key:"
cat "$PUB"
