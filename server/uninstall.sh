#!/usr/bin/env bash
# Откат установки jivenet-сервера.
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
    echo "error: запускайте через sudo" >&2
    exit 1
fi

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)

read -rp "Удалить dnstt-server, наш systemd unit, dante-config и ключи? [y/N] " ans
[[ "$ans" =~ ^[yY]$ ]] || { echo "отмена"; exit 0; }

systemctl disable --now dnstt-server 2>/dev/null || true
systemctl disable --now 3proxy 2>/dev/null || true
systemctl disable --now danted 2>/dev/null || true       # legacy
systemctl disable --now tinyproxy 2>/dev/null || true    # legacy

rm -f /etc/systemd/system/dnstt-server.service
rm -f /etc/systemd/system/3proxy.service
rm -f /etc/systemd/system/danted.service
systemctl daemon-reload

# iptables: снимаем REDIRECT :53→:5300 (и любые другие наши правила)
if command -v iptables >/dev/null; then
    while iptables -t nat -C PREROUTING -p udp --dport 53 -j REDIRECT --to-ports 5300 2>/dev/null; do
        iptables -t nat -D PREROUTING -p udp --dport 53 -j REDIRECT --to-ports 5300
    done
    command -v netfilter-persistent >/dev/null && netfilter-persistent save || true
fi

rm -f /usr/local/bin/dnstt-server /usr/local/bin/3proxy
rm -rf /etc/3proxy

# Ключи и конфиги сервера удаляем только после подтверждения — иначе ключ
# и ваш публичный ключ для Android потеряются.
read -rp "Удалить /etc/dnstt (включая приватный ключ сервера)? [y/N] " ans2
if [[ "$ans2" =~ ^[yY]$ ]]; then
    rm -rf /etc/dnstt
fi

read -rp "Удалить системного пользователя dnstt? [y/N] " ans3
if [[ "$ans3" =~ ^[yY]$ ]]; then
    userdel dnstt 2>/dev/null || true
fi

echo "готово. dante-server и пакеты apt оставлены нетронутыми."
