package net.jivenet.client

import net.jivenet.client.config.TunnelConfig

/**
 * Генератор JSON-конфига для sing-box 1.14+.
 *
 * Архитектура: TUN → DoH-резолвер (через прокси) → SOCKS5 outbound (наш
 * dnstt-client) → сервер → интернет.
 *
 *     Android apps  →  TUN (sing-box, gVisor stack)
 *                            │
 *                            ├─ DNS UDP/53  →  hijacked  →  fakeip + DoH
 *                            │                              (через "proxy")
 *                            │
 *                            └─ TCP/UDP    →  routed     →  outbound[proxy]
 *                                                             (SOCKS5 → 127.0.0.1:1080)
 *                                                                 │
 *                                                                 ▼
 *                                                            dnstt-client
 *                                                                 │
 *                                                                 ▼
 *                                                            DoH-туннель
 *                                                                 │
 *                                                                 ▼
 *                                                            наш сервер
 *
 * Что фиксится по сравнению с tun2socks v0.2.0:
 *   * **DNS-leak**: запросы Android приложений шли через системный резолвер
 *     (DNS оператора, мимо туннеля). Теперь sing-box ловит UDP-DNS на TUN,
 *     резолвит сам через DoH-сервер (над SOCKS5), отдаёт обратно ответом
 *     на TUN. DNS-резолвинг идёт ИЗ серверной точки — оператор видит только
 *     зашифрованный трафик dnstt.
 *   * **FakeIP**: для каждого DNS-запроса sing-box возвращает фейковый IP
 *     из 198.18.0.0/15. Когда приложение лезет на этот IP, sing-box
 *     обратно мапит fakeip→hostname и пробрасывает через outbound (real
 *     resolution делается уже на серверной стороне через 3proxy auto-DNS).
 *     Это убирает DNS-rebinding-проблемы и ускоряет — fakeip отдаётся
 *     мгновенно, без round-trip на DoH.
 *
 * Формат конфига — sing-box 1.14 (новый, после миграции от 1.12):
 *   https://sing-box.sagernet.org/migration/#migrate-to-new-dns-server-formats
 *   * dns.servers[*].type = "https" / "udp" / "fakeip"
 *   * fakeip как отдельный server, не nested-опция
 *   * route.default_domain_resolver указывает по умолчанию
 */
object SingboxConfig {

    /**
     * @param tunnelMtu       MTU TUN-интерфейса (1500 для большинства сетей).
     * @param tunInet4Address подсеть TUN на стороне Android (gateway/30).
     */
    fun build(
        cfg: TunnelConfig,
        tunnelMtu: Int = 1500,
        tunInet4Address: String = "172.19.0.1/30",
        ourPackageName: String,
    ): String {
        // sing-box внутри использует обычный DoH к Cloudflare (1.1.1.1).
        // Сам DoH-запрос уходит через outbound `proxy` (SOCKS5 → dnstt),
        // поэтому блокировки оператора не страшны: трафик зашифрован.
        return """
        {
          "log": { "level": "warn" },

          "dns": {
            "servers": [
              { "type": "https",
                "tag": "doh-remote",
                "server": "1.1.1.1",
                "detour": "proxy" },
              { "type": "fakeip",
                "tag": "dns-fakeip",
                "inet4_range": "198.18.0.0/15" }
            ],
            "rules": [
              { "query_type": ["A", "AAAA"], "server": "dns-fakeip" }
            ],
            "independent_cache": true,
            "strategy": "ipv4_only"
          },

          "inbounds": [
            {
              "type": "tun",
              "tag": "tun-in",
              "mtu": $tunnelMtu,
              "address": ["$tunInet4Address"],
              "auto_route": true,
              "strict_route": false,
              "stack": "gvisor",
              "exclude_package": ["$ourPackageName"]
            }
          ],

          "outbounds": [
            {
              "type": "socks",
              "tag": "proxy",
              "server": "127.0.0.1",
              "server_port": ${cfg.localPort},
              "version": "5"
            },
            { "type": "direct", "tag": "direct" }
          ],

          "route": {
            "rules": [
              { "action": "sniff" },
              { "protocol": "dns", "action": "hijack-dns" },
              { "ip_is_private": true, "outbound": "direct" }
            ],
            "auto_detect_interface": false,
            "default_domain_resolver": "doh-remote"
          },

          "experimental": {
            "cache_file": { "enabled": true, "store_fakeip": true },
            "clash_api": { "external_controller": "127.0.0.1:9090" }
          }
        }
        """.trimIndent()
    }
}
