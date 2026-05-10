package net.jivenet.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/**
 * Читает системные DNS-резолверы активной сети через ConnectivityManager.
 * Нужно, когда DoH заблокирован оператором: тогда мы используем резолвер
 * самого оператора в UDP-режиме dnstt-client (флаг -udp host:port).
 *
 * Возвращает первый IPv4-адрес или null, если его нет (редкий случай —
 * только IPv6, но сеть, где провайдер раздаёт только IPv6 DNS в РФ, не
 * встречается).
 */
object SystemDns {
    fun firstIPv4Resolver(ctx: Context): String? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val net = cm.activeNetwork ?: return null
        val lp = cm.getLinkProperties(net) ?: return null
        return lp.dnsServers
            .filterIsInstance<Inet4Address>()
            .firstOrNull()
            ?.hostAddress
    }

    /** "udp://<ip>:53" или null, если резолвер не определился */
    fun udpResolverUrl(ctx: Context): String? =
        firstIPv4Resolver(ctx)?.let { "udp://$it:53" }

    /**
     * IPv4-резолвер сотовой сети — даже если default-сеть сейчас Wi-Fi.
     *
     * Используется для приоритетного DoH (см. DohWatchdog): на мобильной
     * сети РФ DPI часто пропускает запросы к собственному DNS оператора, но
     * блокирует «чужие» DoH. Когда же default = Wi-Fi, активной сетью в
     * `activeNetwork` будет wlan0 — оттуда мы DNS оператора не получим.
     * Поэтому перебираем все привязанные сети и ищем именно ту, у которой
     * `TRANSPORT_CELLULAR + NOT_VPN`.
     */
    fun cellularIPv4Resolver(ctx: Context): String? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        @Suppress("DEPRECATION")  // allNetworks — единственный путь без NetworkRequest на старте
        val networks = cm.allNetworks
        for (n in networks) {
            val nc = cm.getNetworkCapabilities(n) ?: continue
            if (!nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) continue
            if (!nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (!nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
            val lp = cm.getLinkProperties(n) ?: continue
            val ip = lp.dnsServers.filterIsInstance<Inet4Address>().firstOrNull()
                ?: continue
            return ip.hostAddress
        }
        return null
    }

    /** "udp://<cellular-ip>:53" или null. */
    fun cellularUdpResolverUrl(ctx: Context): String? =
        cellularIPv4Resolver(ctx)?.let { "udp://$it:53" }
}
