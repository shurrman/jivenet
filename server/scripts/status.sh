#!/usr/bin/env bash
# Быстрая диагностика: статус юнитов, слушатели, правило NAT, последние
# сессии из journald.
set -euo pipefail

colour() { printf '\033[1;36m%s\033[0m\n' "$*"; }

colour "== systemd units =="
systemctl --no-pager status dnstt-server.service 3proxy.service || true

echo
colour "== sockets =="
echo "-- dnstt (udp :5300):"
ss -ulnp 2>/dev/null | awk 'NR==1 || /:5300/'
echo
echo "-- 3proxy (tcp 127.0.0.1:3128):"
ss -tlnp 2>/dev/null | awk 'NR==1 || /127\.0\.0\.1:3128/'

echo
colour "== smoke-тест локального 3proxy =="
echo -n "HTTP-прокси:  "
curl -s --max-time 5 -x http://127.0.0.1:3128 https://ifconfig.co 2>/dev/null || echo "FAIL"
echo -n "SOCKS5:       "
curl -s --max-time 5 --socks5 127.0.0.1:3128 https://ifconfig.co 2>/dev/null || echo "FAIL"

echo
colour "== iptables NAT =="
iptables -t nat -L PREROUTING -n -v 2>/dev/null || nft list chain ip nat prerouting 2>/dev/null || true

echo
colour "== последние 20 строк dnstt-server =="
journalctl -u dnstt-server --no-pager -n 20 || true

echo
if [[ -f /etc/dnstt/server.env ]]; then
    colour "== конфиг /etc/dnstt/server.env =="
    grep -vE '^\s*(#|$)' /etc/dnstt/server.env
fi

if [[ -f /etc/dnstt/server.pub ]]; then
    colour "== public key =="
    cat /etc/dnstt/server.pub
fi
