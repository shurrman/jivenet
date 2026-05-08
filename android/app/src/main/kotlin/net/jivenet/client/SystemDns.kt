package net.jivenet.client

import android.content.Context
import android.net.ConnectivityManager
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
}
