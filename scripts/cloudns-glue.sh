#!/usr/bin/env bash
# Управляет glue-A-записями NS-серверов туннеля через ClouDNS API.
#
# Читает .env в корне репозитория:
#   CLOUDNS_AUTH_ID=...           (главный API-пользователь, основной случай)
#     или
#   CLOUDNS_SUB_AUTH_ID=...        (sub-user)
#   CLOUDNS_AUTH_PASSWORD=...
#   CLOUDNS_DOMAIN=jivejournal.top
#
# Usage:
#   ./scripts/cloudns-glue.sh status                   # показать NS+A для tunnel
#   ./scripts/cloudns-glue.sh apply <subdomain> <ip>   # создать/обновить ns1+ns2 A-записи
#       пример: ./cloudns-glue.sh apply jivenet 93.77.166.152
#       (создаст ns1.jivenet → 93.77.166.152 и ns2.jivenet → 93.77.166.152)

set -euo pipefail

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
[[ -f "$HERE/.env" ]] || { echo "error: .env не найден в $HERE" >&2; exit 1; }
set -a; . "$HERE/.env"; set +a

API="https://api.cloudns.net/dns"
DOMAIN="${CLOUDNS_DOMAIN:?CLOUDNS_DOMAIN не задан}"
# Поддерживаем оба типа аккаунта: главный (auth-id) и sub-user (sub-auth-id).
if [[ -n "${CLOUDNS_AUTH_ID:-}" ]]; then
    AUTH="auth-id=${CLOUDNS_AUTH_ID}&auth-password=${CLOUDNS_AUTH_PASSWORD:?}"
elif [[ -n "${CLOUDNS_SUB_AUTH_ID:-}" ]]; then
    AUTH="sub-auth-id=${CLOUDNS_SUB_AUTH_ID}&auth-password=${CLOUDNS_AUTH_PASSWORD:?}"
else
    echo "error: ни CLOUDNS_AUTH_ID, ни CLOUDNS_SUB_AUTH_ID не заданы в .env" >&2
    exit 1
fi

# Любой запрос к API возвращает JSON. Используем jq если есть, иначе python.
jq() {
    if command -v "/opt/homebrew/bin/jq" >/dev/null; then "/opt/homebrew/bin/jq" "$@"
    elif command -v jq >/dev/null; then command jq "$@"
    else python3 -c 'import sys,json; print(json.load(sys.stdin), end="")' ; fi
}

api() {  # api METHOD ENDPOINT [extra-query-string]
    local method=$1 endpoint=$2 extra=${3:-}
    local q="${AUTH}&domain-name=${DOMAIN}"
    [[ -n "$extra" ]] && q="${q}&${extra}"
    curl -sS -X "$method" "${API}/${endpoint}.json?${q}"
}

check_auth() {
    local resp
    resp=$(api GET login)
    if echo "$resp" | grep -q '"status":"Success"'; then return 0; fi
    echo "ClouDNS auth FAILED: $resp" >&2
    echo "Проверьте Settings → API в панели ClouDNS, обновите .env" >&2
    return 1
}

# Возвращает id записи если найдена, иначе пусто.
# args: host record-type
find_record() {
    local host=$1 type=$2
    local resp
    resp=$(api GET records "type=${type}&host=${host}")
    # API возвращает либо {} либо {"id1":{...,"host":"...","record":"...","type":"..."}, ...}
    python3 - "$host" "$type" <<'PY' <<<"$resp"
import sys, json
host_arg, type_arg = sys.argv[1], sys.argv[2]
data = json.load(sys.stdin)
if not isinstance(data, dict): sys.exit(0)
for rid, rec in data.items():
    if rec.get("host") == host_arg and rec.get("type") == type_arg:
        print(rid, rec.get("record",""))
        break
PY
}

upsert_a() {
    local host=$1 ip=$2
    local existing
    existing=$(find_record "$host" A)
    if [[ -z "$existing" ]]; then
        echo "+ create A $host → $ip"
        api POST add-record "record-type=A&host=${host}&record=${ip}&ttl=3600" | head -c 200
        echo
    else
        local rid current
        rid=$(awk '{print $1}' <<<"$existing")
        current=$(awk '{print $2}' <<<"$existing")
        if [[ "$current" == "$ip" ]]; then
            echo "= ok    A $host → $ip (id $rid)"
        else
            echo "~ update A $host : $current → $ip (id $rid)"
            api POST mod-record "record-id=${rid}&record=${ip}&ttl=3600&host=${host}" | head -c 200
            echo
        fi
    fi
}

cmd_status() {
    check_auth
    echo "=== A-записи зоны $DOMAIN (host начинается с 'ns1' или 'ns2') ==="
    api GET records "type=A" | python3 -c '
import sys, json
d = json.load(sys.stdin)
if not isinstance(d, dict): sys.exit(0)
for rid, r in d.items():
    h = r.get("host","")
    if h.startswith("ns1") or h.startswith("ns2"):
        print(f"  {h:30}  {r.get(\"record\",\"\"):16}  ttl={r.get(\"ttl\")} id={rid}")
'
    echo
    echo "=== NS-делегирование (host = subdomain) ==="
    api GET records "type=NS" | python3 -c '
import sys, json
d = json.load(sys.stdin)
if not isinstance(d, dict): sys.exit(0)
for rid, r in d.items():
    print(f"  {r.get(\"host\",\"\"):30}  NS  {r.get(\"record\",\"\")}")
'
}

cmd_apply() {
    local sub=$1 ip=$2
    check_auth
    echo "Целевой подомен: $sub.$DOMAIN  →  glue для NS-сервера на IP: $ip"
    echo
    upsert_a "ns1.${sub}" "$ip"
    upsert_a "ns2.${sub}" "$ip"
    echo
    echo "=== итоговое состояние ==="
    cmd_status
}

case "${1:-}" in
    status)
        cmd_status
        ;;
    apply)
        sub="${2:?usage: $0 apply <subdomain> <ip>}"
        ip="${3:?usage: $0 apply <subdomain> <ip>}"
        cmd_apply "$sub" "$ip"
        ;;
    *)
        sed -n '2,15p' "$0" >&2
        exit 2
        ;;
esac
