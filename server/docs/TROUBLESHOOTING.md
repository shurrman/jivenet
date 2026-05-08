# Troubleshooting

## Клиент подключается, но интернета нет

1. **3proxy работает?**
   ```
   systemctl status 3proxy
   ss -tlnp | grep 127.0.0.1:3128
   ```
2. **Прокси наружу пускает?** На самом сервере:
   ```
   curl -x http://127.0.0.1:3128 https://ifconfig.co    # HTTP-режим
   curl --socks5 127.0.0.1:3128 https://ifconfig.co     # SOCKS5-режим
   ```
   Оба должны вернуть публичный IP сервера.
3. **dnstt-server видит клиента?** `journalctl -u dnstt-server -f` — при подключении клиента появляются строки `begin session ...`, `begin stream ...`.
4. **3proxy логирует запросы?** `journalctl -u 3proxy -f` — должны быть строки вида `CONNECT <host>:<port> HTTP/1.1`.
5. **Клиент висит с `opening stream: timeout`?** Значит dnstt-server перезапускался и сессия клиента устарела. Выключите-включите туннель в приложении на телефоне.

## На клиенте нет ответов от DoH

1. Проверьте, что DoH URL доступен с устройства: `curl https://1.1.1.1/dns-query?name=example.com&type=A -H 'accept: application/dns-json'` (если есть Termux).
2. Смените DoH resolver (`1.1.1.1` → `dns.google` и т.д.) — некоторые провайдеры блокируют Cloudflare DoH.
3. На сервере: `tcpdump -ni any udp port 5300` во время теста — должны быть входящие пакеты.

## Мобильная сеть (LTE/5G/EDGE): туннель не поднимается

Российские мобильные операторы (МТС/МегаФон/Билайн/Tele2/Yota) **блокируют публичные DoH по IP**: `1.1.1.1`, `8.8.8.8`, `9.9.9.9`, `208.67.222.222`, `185.228.168.9`, `223.5.5.5` — все возвращают `Connection refused` на TCP:443. DoH по hostname'у dnstt-client на Android **вообще не резолвит** (Go без cgo на Android не умеет), так что менять Cloudflare на Mullvad в URL бесполезно.

**Решение** (проверено, работает на EDGE от МегаФона):

### 1. На сервере: снизить `MTU` до 512

DNS-резолвер оператора пересылает запросы на authoritative NS **обрезая EDNS0** — размер ответа ограничивается 512 байтами. dnstt-server по умолчанию требует ≥1232, возвращает `FORMERR: requester payload size 512 is too small`.

Фикс через `/etc/dnstt/server.env`:

```
MTU=512
```

Уже прописано в install.sh и systemd unit по умолчанию — для новых установок ничего делать не нужно. Проверить:

```bash
sudo ps -ef | grep dnstt-server
# → должно быть: dnstt-server -udp 0.0.0.0:5300 -mtu 512 ...
```

Trade-off: эффективный payload станет ~215 байт вместо ~950 (медленнее), но работает везде.

### 2. На клиенте: режим UDP через DNS оператора

В приложении jivenet → Настройки → чип **«Авто UDP»** (первый в списке). Подставит `udp://<IP-вашего-оператора>:53` (например `udp://10.152.222.141:53` у МегаФона). dnstt-client шлёт обычные DNS-запросы к резолверу оператора, тот рекурсивно идёт до нашего authoritative NS.

Важно: **прямой UDP к нашему IP (`udp://93.77.166.152:53`) не работает** — оператор перехватывает UDP:53 и заворачивает только на свои серверы.

### 3. На клиенте: глобальный прокси (без компьютера — через SocksDroid)

Android на мобильной сети **не даёт настроить HTTP-прокси через GUI** (per-Wi-Fi настройка не применяется к LTE). Без компьютера — **[SocksDroid](https://f-droid.org/packages/net.typeblog.socks/)** (F-Droid, без root):

1. Установить SocksDroid.
2. New Profile: Server IP `127.0.0.1`, Server Port `1080`, SOCKS5, без auth.
3. **Обязательно:** Per-App Proxy → Bypass selected apps → отметить **jivenet** (иначе его собственные DNS-запросы к резолверу оператора зайдут обратно в туннель = петля).
4. Save → Connect → разрешить VPN.

Запуск каждый раз: сначала «Подключить» в jivenet, потом Connect в SocksDroid.

Подробный гид — [`../../docs/ANDROID-PROXY-SETUP.md`](../../docs/ANDROID-PROXY-SETUP.md) → «Способ 3. SocksDroid».

### 3-альт. То же через adb (если телефон подключён к ПК)

```bash
# Включить
adb shell settings put global http_proxy 127.0.0.1:1080

# Выключить (любой из трёх):
adb shell settings put global http_proxy :0
adb shell settings put global http_proxy ""
adb shell settings delete global http_proxy

# Проверить
adb shell settings get global http_proxy
```

Быстрее, но требует компьютер + USB-отладку. В отличие от SocksDroid, не перехватывает UDP — например игры, гонящие UDP напрямую, всё равно пойдут мимо. Для домашней отладки OK, для повседневного использования — SocksDroid удобнее.

### Как понять что именно блокировка DoH

На сервере `journalctl -u dnstt-server -f`:

- **Нет `begin session`** при попытке подключения — запросы не доходят (IP-блок DoH).
- **`FORMERR: payload size 512 is too small`** — запросы доходят через резолвер оператора, но серверу нужен больший MTU → снижайте `MTU=512`.
- **`begin session` + `begin stream` есть, но Chrome пишет `ERR_CONNECTION_REFUSED`** — прокси не настроен на устройстве → `adb shell settings put global http_proxy 127.0.0.1:1080`.

### Радикальные обходы (если оператор режет даже UDP:53 к authoritative)

Встречалось на некоторых корпоративных/детских тарифах. Тогда:

- Поднять **собственный DoT на VPS на :853** и использовать `dot://93.77.166.152:853` в приложении (порт :853 режется реже, чем :443).
- Поднять **собственный DoH на нестандартном порту** (например :8443) с Let's Encrypt сертификатом на `tunnel.example.com`.
- **Мигрировать на альтернативный транспорт**: Shadowsocks/v2ray поверх HTTPS — не DNS-туннель вообще.

## `dig +trace` падает на нашем NS

Почти всегда это **отсутствие glue records** у регистратора. См. [DNS-SETUP.md](DNS-SETUP.md) → раздел «Если делегирование не работает».

## Как поменять DoH resolver на сервере

На сервере DoH не используется — сервер слушает обычный UDP/53. Смена DoH делается **только на клиенте**.

## Как поменять домен туннеля

```
sudoedit /etc/dnstt/server.env   # TUNNEL_DOMAIN=новый.домен
sudo systemctl restart dnstt-server
```

Не забудьте перенастроить делегирование у регистратора и обновить конфиг в Android-клиенте.

## Ротация ключей

Если приватный ключ скомпрометирован:

```
sudo rm /etc/dnstt/server.key /etc/dnstt/server.pub
sudo bash server/scripts/gen-keys.sh
sudo systemctl restart dnstt-server
```

Новый публичный ключ нужно прошить во всех Android-клиентах — показать им QR через `sudo bash server/scripts/print-qr.sh` (или `make qr`).

## Клиент держит сессию, но качает медленно

Характерно для DNS-туннеля. Ориентиры:

- Обычный Cloudflare DoH: 100–400 KB/s.
- При проблемах: проверьте rate-limits DoH-провайдера (NextDNS со свободным аккаунтом лимитирует ≈ 300K запросов/день).
- `-mtu` у `dnstt-server` можно попробовать уменьшить (1232 → 900) если DoH-провайдер обрезает EDNS0.

## Порт 53 занят systemd-resolved

Типично для Ubuntu и некоторых установок Debian:

```
ss -ulnp '( sport = :53 )'
# если видно systemd-resolved → нужно снять его с 0.0.0.0:53
sudo sed -i 's/^#DNSStubListener=yes/DNSStubListener=no/' /etc/systemd/resolved.conf
sudo systemctl restart systemd-resolved
```

dnstt слушает на `:5300`, а iptables делает REDIRECT `:53 → :5300` — если `:53` занят другим процессом, REDIRECT не сработает (он же должен достичь dnstt). После снятия resolved с :53 перезапустите `setup-iptables.sh`.

## Как снять всё и переустановить

```
sudo bash server/uninstall.sh
sudo bash server/install.sh t.example.com
```

## «На Android подключено, но браузер не ходит»

Характерные причины:

1. **Android Wi-Fi прокси указан, но применён к ДРУГОЙ сети.** Proxy-настройка привязана к конкретной Wi-Fi-сети (или мобильной). Проверьте, что вы на той же сети, где прописан прокси.
2. **Заблокировано на уровне файрвола Android.** Некоторые бренды (Xiaomi/MIUI, Huawei) имеют политики запрещающие приложениям ходить на loopback. Попробуйте через FoxyProxy-Firefox (оно работает в пределах своего приложения).
3. **Chrome игнорирует proxy в редких сборках.** Проверьте через `curl` в Termux: `curl -x 127.0.0.1:1080 https://ifconfig.co` — должен вернуть IP сервера.
4. **Неправильный pubkey в настройках.** Ошибка покажется в `sudo journalctl -u dnstt-server` как `session NOT established` — сверьте 64-символьный hex с `/etc/dnstt/server.pub`.

## 3proxy логи: код `00801`

`00801` в первом поле лог-строки = код ошибки 3proxy `HOST_UNREACHABLE`. Типично когда Chrome/приложение резолвит IPv6-only хост (`ds6.probe.whatismyipaddress.com` и подобные), а 3proxy из конфига-по-умолчанию резолвит только IPv4. Не влияет на работу обычных сайтов.

Если нужно включить IPv6 — добавьте в `/etc/3proxy/3proxy.cfg`:

```
nserver [2606:4700:4700::1111]:53
```

и перезапустите 3proxy.
