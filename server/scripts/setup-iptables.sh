#!/usr/bin/env bash
# Настраивает пробрасывание UDP :53 → :5300, чтобы dnstt-server мог
# работать на непривилегированном порту. Умеет iptables и nftables
# (detection по наличию бинарников).
set -euo pipefail

DNSTT_PORT=${DNSTT_PORT:-5300}
PUBLIC_IFACE=${PUBLIC_IFACE:-$(ip -4 route show default | awk '{print $5; exit}')}

if [[ $EUID -ne 0 ]]; then
    echo "error: must be run as root" >&2
    exit 1
fi

if [[ -z "$PUBLIC_IFACE" ]]; then
    echo "error: не удалось определить внешний интерфейс; укажите PUBLIC_IFACE=eth0 ..." >&2
    exit 1
fi

echo "info: используется интерфейс $PUBLIC_IFACE, порт dnstt = $DNSTT_PORT"

install_iptables() {
    # Удаляем ранее добавленное правило (если есть), чтобы не плодить дубли
    while iptables -t nat -C PREROUTING -i "$PUBLIC_IFACE" -p udp --dport 53 \
            -j REDIRECT --to-ports "$DNSTT_PORT" 2>/dev/null; do
        iptables -t nat -D PREROUTING -i "$PUBLIC_IFACE" -p udp --dport 53 \
            -j REDIRECT --to-ports "$DNSTT_PORT"
    done
    iptables -t nat -A PREROUTING -i "$PUBLIC_IFACE" -p udp --dport 53 \
        -j REDIRECT --to-ports "$DNSTT_PORT"
    echo "ok: iptables PREROUTING установлен"

    if command -v netfilter-persistent >/dev/null 2>&1; then
        netfilter-persistent save
        echo "ok: сохранено через netfilter-persistent"
    else
        echo "warn: netfilter-persistent не установлен — правило не переживёт reboot"
        echo "      apt install iptables-persistent"
    fi
}

install_nftables() {
    local table=nat
    nft list table ip "$table" >/dev/null 2>&1 || nft add table ip "$table"
    nft list chain ip "$table" prerouting >/dev/null 2>&1 || \
        nft 'add chain ip nat prerouting { type nat hook prerouting priority -100; }'
    # Удаляем конфликтующие правила и добавляем своё
    nft -a list chain ip nat prerouting 2>/dev/null \
        | awk '/udp dport 53/ && /redirect/ { for (i=1;i<=NF;i++) if ($i=="handle") print $(i+1) }' \
        | while read -r handle; do
            [[ -n "$handle" ]] && nft delete rule ip nat prerouting handle "$handle" || true
        done
    nft add rule ip nat prerouting iifname "$PUBLIC_IFACE" udp dport 53 redirect to :$DNSTT_PORT
    echo "ok: nftables правило установлено"

    # Сохранение между ребутами
    if [[ -d /etc/nftables.d ]] || [[ -f /etc/nftables.conf ]]; then
        nft list ruleset >/etc/nftables.conf
        echo "ok: сохранено в /etc/nftables.conf"
    fi
}

# На Debian 12/13 по умолчанию используется nftables-бэкенд iptables-nft.
# Обе утилиты обычно доступны. Предпочитаем iptables для совместимости
# с iptables-persistent, но если iptables нет — fallback на nft.
if command -v iptables >/dev/null 2>&1; then
    install_iptables
elif command -v nft >/dev/null 2>&1; then
    install_nftables
else
    echo "error: ни iptables, ни nft не найдены" >&2
    exit 1
fi

echo
echo "Текущее правило NAT PREROUTING:"
iptables -t nat -L PREROUTING -n -v 2>/dev/null | head -20 || \
    nft list chain ip nat prerouting 2>/dev/null
