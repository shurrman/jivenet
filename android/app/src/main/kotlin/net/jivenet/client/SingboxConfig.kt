package net.jivenet.client

import net.jivenet.client.config.TunnelConfig

/**
 * Генератор JSON-конфига для sing-box.
 *
 * Архитектура: TUN → DoH-резолвер (через прокси) → SOCKS5 outbound (наш
 * dnstt-client) → сервер → интернет.
 *
 *     Android apps  →  TUN (sing-box, gVisor stack)
 *                            │
 *                            ├─ DNS UDP/53  →  hijacked  →  DoH://1.1.1.1
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
 * Источник истины для опций — sing-box docs:
 *   https://sing-box.sagernet.org/configuration/
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
        // sing-box понимает плоский DoH URL ("https://1.1.1.1/dns-query")
        // и делает к нему обычный HTTPS-POST. Если в нашем cfg.doh
        // префикс udp:// или dot:// — это спецсхема dnstt-client (он
        // использует операторский DNS как транспорт), к sing-box это не
        // относится. Для встроенного DoH-резолвера ВСЕГДА Cloudflare —
        // он стабилен, и сами DoH-запросы идут через туннель, поэтому
        // блокировки на стороне оператора не страшны.
        val resolverDoh = "https://1.1.1.1/dns-query"

        return """
        {
          "log": { "level": "warn" },

          "dns": {
            "servers": [
              { "tag": "doh-remote",
                "address": "$resolverDoh",
                "address_resolver": "dns-direct",
                "detour": "proxy" },
              { "tag": "dns-direct",
                "address": "1.1.1.1",
                "detour": "direct" },
              { "tag": "dns-fakeip",
                "address": "fakeip" },
              { "tag": "dns-block",
                "address": "rcode://success" }
            ],
            "rules": [
              { "outbound": "any", "server": "dns-direct" },
              { "query_type": ["A", "AAAA"], "server": "dns-fakeip" }
            ],
            "fakeip": {
              "enabled": true,
              "inet4_range": "198.18.0.0/15"
            },
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
              "exclude_package": ["$ourPackageName"],
              "platform": {
                "http_proxy": { "enabled": false }
              },
              "sniff": true
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
              { "protocol": "dns", "action": "hijack-dns" },
              { "ip_is_private": true, "outbound": "direct" }
            ],
            "auto_detect_interface": false,
            "default_domain_resolver": "doh-remote"
          },

          "experimental": {
            "cache_file": { "enabled": true, "store_fakeip": true }
          }
        }
        """.trimIndent()
    }
}
