#!/usr/bin/env bash
# jivenet server installer.
#
# Usage: sudo ./install.sh <tunnel-domain> [public-ip]
#   <tunnel-domain>   Под-домен, делегированный на этот VPS. Пример: t.example.com
#   [public-ip]       Публичный IPv4 VPS. Если не указан — определится автоматически.
#
# Что делает скрипт:
#   1. Ставит пакеты (Go+build-essential для сборки dnstt и 3proxy, qrencode).
#   2. Клонирует и собирает dnstt-server.
#   3. Клонирует и собирает 3proxy (HTTP + SOCKS5 auto-detect).
#   4. Создаёт системного пользователя dnstt.
#   5. Генерирует X25519-ключ.
#   6. Устанавливает конфиги 3proxy и systemd.
#   7. Пробрасывает UDP :53 → :5300.
#   8. Печатает готовые DNS-записи и QR-конфиг для Android.

set -euo pipefail

# --- Parameters -----------------------------------------------------------

TUNNEL_DOMAIN="${1:-}"
PUBLIC_IP="${2:-}"

if [[ -z "$TUNNEL_DOMAIN" ]]; then
    cat <<USAGE >&2
Usage: sudo $0 <tunnel-domain> [public-ip]

Пример:
  sudo $0 t.example.com 203.0.113.7

Перед запуском убедитесь, что:
  - домен (или подомен) у вас есть,
  - у регистратора будет возможность создать NS/A записи (после установки
    скрипт распечатает, какие именно).
USAGE
    exit 2
fi

if [[ $EUID -ne 0 ]]; then
    echo "error: запускайте через sudo" >&2
    exit 1
fi

# Определяем публичный IP, если не задан
if [[ -z "$PUBLIC_IP" ]]; then
    PUBLIC_IP=$(ip -4 -o addr show scope global \
        | awk '{print $4}' | cut -d/ -f1 | head -1 || true)
fi
if [[ -z "$PUBLIC_IP" ]]; then
    echo "error: не удалось определить публичный IP; передайте вторым аргументом" >&2
    exit 1
fi

# Конфигурация по-умолчанию
DNSTT_LISTEN_PORT=${DNSTT_LISTEN_PORT:-5300}
PROXY_PORT=${PROXY_PORT:-3128}
DEFAULT_DOH="https://1.1.1.1/dns-query"

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)

# --- OS check -------------------------------------------------------------

. /etc/os-release 2>/dev/null || true
case "${ID:-}" in
    debian|ubuntu) ;;
    *)
        echo "warn: протестировано на Debian 12/13 и Ubuntu. Текущая ОС: ${PRETTY_NAME:-unknown}"
        echo "      продолжаю, но возможны сюрпризы" >&2
        ;;
esac

# --- Packages -------------------------------------------------------------

echo "[1/9] Установка пакетов…"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y --no-install-recommends \
    git ca-certificates build-essential \
    iptables iptables-persistent \
    qrencode \
    curl dnsutils

# Debian 12 идёт с Go 1.19, но современный dnstt тянет kcp-go, который
# требует Go 1.21+. Ставим свежий Go в /usr/local/go из официального tarball.
GO_REQUIRED_MIN_MINOR=21
GO_VERSION=${GO_VERSION:-1.22.9}
need_install_go=0
if command -v /usr/local/go/bin/go >/dev/null; then
    GO_BIN=/usr/local/go/bin/go
elif command -v go >/dev/null; then
    GO_BIN=go
else
    need_install_go=1
fi
if [[ $need_install_go -eq 0 ]]; then
    current=$("$GO_BIN" version | awk '{print $3}' | sed 's/^go//')
    minor=$(echo "$current" | awk -F. '{print $2}')
    if [[ ${minor:-0} -lt $GO_REQUIRED_MIN_MINOR ]]; then
        need_install_go=1
    fi
fi
if [[ $need_install_go -eq 1 ]]; then
    echo "       ставлю Go ${GO_VERSION} в /usr/local/go…"
    arch=$(dpkg --print-architecture)
    case "$arch" in
        amd64) goarch=amd64 ;;
        arm64) goarch=arm64 ;;
        *) echo "error: unsupported arch $arch" >&2; exit 1 ;;
    esac
    tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
    curl -fsSL "https://go.dev/dl/go${GO_VERSION}.linux-${goarch}.tar.gz" -o "$tmp/go.tgz"
    rm -rf /usr/local/go
    tar -C /usr/local -xzf "$tmp/go.tgz"
    GO_BIN=/usr/local/go/bin/go
fi
export PATH="/usr/local/go/bin:$PATH"
echo "       Go: $("$GO_BIN" version)"

# --- Build dnstt-server ---------------------------------------------------

echo "[2/9] Сборка dnstt-server…"
DNSTT_SRC="$HERE/dnstt"
if [[ ! -d "$DNSTT_SRC" ]]; then
    # bamsoftware.com — dumb HTTP git server (нет shallow). Сначала пробуем
    # github-зеркало (быстрее, --depth=1 работает), затем fallback на
    # оригинал полным клоном.
    git clone --depth=1 https://github.com/net2share/dnstt.git "$DNSTT_SRC" \
        || git clone https://www.bamsoftware.com/git/dnstt.git "$DNSTT_SRC"
fi
(
    cd "$DNSTT_SRC/dnstt-server"
    # CGO_ENABLED=0 — чистый Go, без gcc. dnstt не требует C-зависимостей,
    # но transitive deps (reedsolomon asm-пути) иногда подтягивают cgo.
    CGO_ENABLED=0 GOFLAGS='-trimpath' go build -ldflags='-s -w' -o dnstt-server .
    install -m0755 dnstt-server /usr/local/bin/dnstt-server
)
echo "ok: установлен /usr/local/bin/dnstt-server"

# --- Build 3proxy ---------------------------------------------------------

echo "[3/9] Сборка 3proxy…"
THREEPROXY_SRC="$HERE/3proxy"
if [[ ! -d "$THREEPROXY_SRC" ]]; then
    git clone --depth=1 https://github.com/3proxy/3proxy.git "$THREEPROXY_SRC"
fi
# Идемпотентно: пропускаем сборку, если бинарь уже установлен и новее, чем src
need_rebuild=1
if [[ -x /usr/local/bin/3proxy ]]; then
    newer_src=$(find "$THREEPROXY_SRC/src" -type f -newer /usr/local/bin/3proxy 2>/dev/null | head -1)
    [[ -z "$newer_src" ]] && need_rebuild=0
fi
if [[ $need_rebuild -eq 1 ]]; then
    (
        cd "$THREEPROXY_SRC"
        make -f Makefile.Linux >/dev/null
        install -m0755 bin/3proxy /usr/local/bin/3proxy
    )
fi
echo "ok: установлен /usr/local/bin/3proxy ($(ls -s --block-size=K /usr/local/bin/3proxy | awk '{print $1}'))"

# --- System user ----------------------------------------------------------

echo "[4/9] Создание пользователя dnstt…"
if ! id -u dnstt >/dev/null 2>&1; then
    useradd --system --no-create-home --shell /usr/sbin/nologin dnstt
fi
install -d -o root -g dnstt -m 0750 /etc/dnstt

# --- Keys -----------------------------------------------------------------

echo "[5/9] Генерация ключей dnstt…"
bash "$HERE/scripts/gen-keys.sh"
PUBKEY=$(cat /etc/dnstt/server.pub)

# --- Environment file -----------------------------------------------------

echo "[6/9] Конфиг /etc/dnstt/server.env…"
cat >/etc/dnstt/server.env <<EOF
# jivenet dnstt-server environment (см. dnstt-server.service)
LISTEN_ADDR=0.0.0.0:${DNSTT_LISTEN_PORT}
TUNNEL_DOMAIN=${TUNNEL_DOMAIN}
FORWARD_ADDR=127.0.0.1:${PROXY_PORT}
# MTU=512 — минимально допустимый EDNS0 payload size. Даёт совместимость
# с "кривыми" DNS-резолверами мобильных операторов (РФ), которые обрезают
# EDNS0 при пересылке на authoritative NS. Trade-off: медленнее (~215 байт
# эффективный payload вместо ~950 для 1232), но работает везде. На чистом
# DoH (где EDNS0 сохраняется) можно временно поднять до 1232.
MTU=512
EOF
chmod 0644 /etc/dnstt/server.env

# --- 3proxy ---------------------------------------------------------------

echo "[7/9] Настройка 3proxy (HTTP + SOCKS5 auto-mode)…"
install -d -m 0755 /etc/3proxy
install -m0644 "$HERE/etc/3proxy.cfg" /etc/3proxy/3proxy.cfg
install -m0644 "$HERE/systemd/3proxy.service" /etc/systemd/system/3proxy.service

# Если был старый Dante от прошлых версий jivenet — аккуратно выводим из игры
if systemctl is-enabled danted >/dev/null 2>&1 || systemctl is-active danted >/dev/null 2>&1; then
    systemctl disable --now danted 2>/dev/null || true
fi

systemctl daemon-reload
systemctl reset-failed 3proxy 2>/dev/null || true
systemctl enable --now 3proxy

# --- dnstt-server unit ----------------------------------------------------

echo "[8/9] systemd unit dnstt-server…"
install -m0644 "$HERE/systemd/dnstt-server.service" /etc/systemd/system/dnstt-server.service
systemctl daemon-reload
systemctl enable --now dnstt-server

# --- iptables -------------------------------------------------------------

echo "[9/9] iptables: UDP :53 → :${DNSTT_LISTEN_PORT}…"
DNSTT_PORT="$DNSTT_LISTEN_PORT" bash "$HERE/scripts/setup-iptables.sh"

# --- Status ---------------------------------------------------------------

sleep 1
echo
echo "=== Проверка сервисов ==="
systemctl is-active dnstt-server && echo "  dnstt-server: active"
systemctl is-active 3proxy && echo "  3proxy:       active"

# Локальный smoke-тест через 3proxy (должен вернуть публичный IP VPS)
echo -n "SOCKS5-тест: "
curl -s --max-time 5 --socks5 127.0.0.1:${PROXY_PORT} https://ifconfig.co || echo "FAIL"
echo -n "HTTP-тест:   "
curl -s --max-time 5 -x http://127.0.0.1:${PROXY_PORT} https://ifconfig.co || echo "FAIL"

# --- Summary for user -----------------------------------------------------

# Предполагаемые имена authoritative NS. Обычно регистраторы хотят два,
# допустимо указать один и тот же IP для двух разных меток (ns1/ns2).
NS1="ns1.${TUNNEL_DOMAIN}"
NS2="ns2.${TUNNEL_DOMAIN}"

CONFIG_JSON=$(cat <<JSON
{"domain":"${TUNNEL_DOMAIN}","pubkey":"${PUBKEY}","doh":"${DEFAULT_DOH}","mode":"proxy"}
JSON
)

cat <<SUMMARY

================================================================================
  УСТАНОВКА ЗАВЕРШЕНА
================================================================================

  Tunnel domain : ${TUNNEL_DOMAIN}
  Public IP     : ${PUBLIC_IP}
  Public key    : ${PUBKEY}
  DoH resolver  : ${DEFAULT_DOH}

--------------------------------------------------------------------------------
  ЧТО НАСТРОИТЬ У РЕГИСТРАТОРА ДОМЕНА
--------------------------------------------------------------------------------

В панели регистратора домена (пример для ${TUNNEL_DOMAIN%%.*}.example.com):

  1. A-записи для будущих NS-серверов:
        ${NS1}.    A    ${PUBLIC_IP}
        ${NS2}.    A    ${PUBLIC_IP}

  2. NS-делегирование поддомена:
        ${TUNNEL_DOMAIN}.    NS    ${NS1}.
        ${TUNNEL_DOMAIN}.    NS    ${NS2}.

После появления записей (TTL + до 30 минут) можно проверить:

    dig +trace ${TUNNEL_DOMAIN}
    dig TXT random123.${TUNNEL_DOMAIN} @1.1.1.1

Подробнее: server/docs/DNS-SETUP.md

--------------------------------------------------------------------------------
  КОНФИГ ДЛЯ ANDROID-КЛИЕНТА
--------------------------------------------------------------------------------

JSON:
    ${CONFIG_JSON}

QR-код (отсканируйте в приложении jivenet):

SUMMARY

qrencode -t ansiutf8 -o - <<<"$CONFIG_JSON" || echo "(qrencode не выдал вывод — проверьте вручную)"

cat <<'TAIL'

--------------------------------------------------------------------------------
Диагностика:  server/scripts/status.sh
Логи:         journalctl -u dnstt-server -f
Удаление:     server/uninstall.sh
================================================================================
TAIL
