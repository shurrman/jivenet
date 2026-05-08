# Удобные таргеты для обслуживания уже установленного сервера.
# Для первоначальной установки используйте ./install.sh <tunnel-domain>.

.PHONY: help build install-systemd enable status logs gen-keys test-dns restart stop

DOMAIN ?= $(shell awk -F= '/^TUNNEL_DOMAIN=/ {print $$2}' /etc/dnstt/server.env 2>/dev/null)
RESOLVER ?= 1.1.1.1

help:
	@echo "Доступные цели:"
	@echo "  make build           — пересобрать dnstt-server из server/dnstt/"
	@echo "  make build-3proxy    — пересобрать 3proxy из server/3proxy/"
	@echo "  make install-systemd — переустановить systemd-юниты и /etc/3proxy/"
	@echo "  make enable          — enable + start dnstt-server и 3proxy"
	@echo "  make status          — быстрая диагностика"
	@echo "  make logs            — journalctl -f dnstt-server"
	@echo "  make gen-keys        — создать ключ (если ещё нет)"
	@echo "  make test-dns        — проверка, что DNS делегирован и доходит до нас"
	@echo "  make qr [ARGS=...]   — показать QR-код текущего конфига"
	@echo "  make restart / stop  — управление сервисом"

build:
	cd dnstt/dnstt-server && GOFLAGS=-trimpath go build -ldflags='-s -w' -o dnstt-server . \
		&& sudo install -m0755 dnstt-server /usr/local/bin/dnstt-server

install-systemd:
	sudo install -m0644 systemd/dnstt-server.service /etc/systemd/system/
	sudo install -m0644 systemd/3proxy.service /etc/systemd/system/
	sudo install -d -m0755 /etc/3proxy
	sudo install -m0644 etc/3proxy.cfg /etc/3proxy/3proxy.cfg
	sudo systemctl daemon-reload

enable:
	sudo systemctl enable --now 3proxy dnstt-server

build-3proxy:
	cd 3proxy && make -f Makefile.Linux && sudo install -m0755 bin/3proxy /usr/local/bin/3proxy

status:
	@bash scripts/status.sh

logs:
	sudo journalctl -u dnstt-server -f

gen-keys:
	sudo bash scripts/gen-keys.sh

test-dns:
	@if [ -z "$(DOMAIN)" ]; then echo "DOMAIN не задан и /etc/dnstt/server.env не найден"; exit 1; fi
	@echo "=== dig +trace $(DOMAIN) ===" && dig +trace +tries=1 +time=3 $(DOMAIN) NS
	@echo "=== dig TXT random.$(DOMAIN) @$(RESOLVER) ===" && dig +short TXT random.$(DOMAIN) @$(RESOLVER) || true

restart:
	sudo systemctl restart dnstt-server

stop:
	sudo systemctl stop dnstt-server

qr:
	@sudo bash scripts/print-qr.sh $(ARGS)
