package net.jivenet.client

// Этот файл реализует io.nekohasekai.libbox.PlatformInterface — мост между
// sing-box (Go) и Android-side функциями (VpnService.Builder, certificates,
// network monitoring). Минимальная реализация: только то, что нужно для
// VPN-режима с одним outbound (SOCKS5 → dnstt-client) и DoH-резолвером.
//
// Структура и часть логики (особенно openTun) опираются на SFA
// (sing-box-for-android, GPL-3): https://github.com/SagerNet/sing-box-for-android
// → app/src/main/java/io/nekohasekai/sfa/bg/{VPNService.kt, PlatformInterfaceWrapper.kt}.
// При обновлении libbox API сверяться с теми файлами.

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.security.KeyStore
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Адаптер VpnService→PlatformInterface для sing-box.
 *
 * Обязанности:
 *   * [openTun] — создать TUN через VpnService.Builder, вернуть fd.
 *     Параметры берёт из TunOptions, которые libbox формирует из секции
 *     `inbounds[type=tun]` нашего sing-box-конфига.
 *   * [autoDetectInterfaceControl] — `protect(fd)` для исходящих
 *     соединений sing-box (DoH/SOCKS5), чтобы они не шли обратно в TUN.
 *
 * Остальные методы — sensible-defaults / no-op:
 *   - getInterfaces / readWIFIState — sing-box справится без них (auto_detect_interface
 *     получит данные из platformInterface.startDefaultInterfaceMonitor → у нас no-op).
 *   - localDNSTransport — null: DNS внутри sing-box идёт через DoH-сервер,
 *     описанный в конфиге, не через системный резолвер.
 *   - systemCertificates — извлекаем CA-store Android (для проверки TLS DoH-сервера).
 */
class SingboxPlatform(
    private val service: VpnService,
    private val sessionName: String,
    private val configureIntent: Class<*>?,   // обычно MainActivity
) : PlatformInterface {

    private companion object {
        private const val TAG = "SingboxPlatform"
    }

    @Volatile
    var fileDescriptor: ParcelFileDescriptor? = null
        private set

    // -- TUN -----------------------------------------------------------------

    override fun openTun(options: TunOptions): Int {
        if (VpnService.prepare(service) != null) error("VPN permission missing")

        val builder = service.run { Builder() }
            .setSession(sessionName)
            .setMtu(options.mtu)

        // IPv4-адреса для TUN (берём из inet4_address[] sing-box-config).
        val inet4 = options.inet4Address
        while (inet4.hasNext()) {
            val a = inet4.next()
            builder.addAddress(a.address(), a.prefix())
        }
        val inet6 = options.inet6Address
        while (inet6.hasNext()) {
            val a = inet6.next()
            builder.addAddress(a.address(), a.prefix())
        }

        if (options.autoRoute) {
            // Маршруты — auto_route=true в sing-box указывает захватить весь
            // трафик. inet4_route_address пустой → значит «всё».
            val r4 = options.inet4RouteAddress
            if (r4.hasNext()) {
                while (r4.hasNext()) {
                    val a = r4.next()
                    builder.addRoute(a.address(), a.prefix())
                }
            } else {
                builder.addRoute("0.0.0.0", 0)
            }
            val r6 = options.inet6RouteAddress
            if (r6.hasNext()) {
                while (r6.hasNext()) {
                    val a = r6.next()
                    builder.addRoute(a.address(), a.prefix())
                }
            }

            // DNS-серверы из секции dns sing-box-config. Указываются как
            // ip-адрес внутри туннеля (например 172.19.0.1) — Android отправит
            // сюда DNS UDP, sing-box перехватит на TUN и обработает встроенным
            // DoH-резолвером. Это и есть фикс DNS-leak.
            if (options.dnsMode.value != "disabled") {
                val dns = options.dnsServerAddress
                while (dns.hasNext()) {
                    builder.addDnsServer(dns.next())
                }
            }

            // Per-app proxy (мы не используем — но если из конфига придёт, применим).
            val include = options.includePackage
            while (include.hasNext()) runCatching {
                builder.addAllowedApplication(include.next())
            }.onFailure { Log.w(TAG, "addAllowedApplication", it) }
            val exclude = options.excludePackage
            while (exclude.hasNext()) runCatching {
                builder.addDisallowedApplication(exclude.next())
            }.onFailure { Log.w(TAG, "addDisallowedApplication", it) }
        }

        // Тап по значку ключа в статус-баре открывает MainActivity.
        configureIntent?.let { cls ->
            val pi = PendingIntent.getActivity(
                service,
                0,
                Intent(service, cls),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.setConfigureIntent(pi)
        }

        val pfd = builder.establish() ?: error("VpnService.Builder.establish() returned null")
        fileDescriptor = pfd
        Log.i(TAG, "TUN установлен fd=${pfd.fd} mtu=${options.mtu}")
        return pfd.fd
    }

    fun closeTun() {
        runCatching { fileDescriptor?.close() }
        fileDescriptor = null
    }

    // -- protect: исходящий трафик sing-box идёт мимо TUN ---------------------

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        // Для исходящих сокетов (DoH HTTPS, SOCKS5 dial к 127.0.0.1:1080)
        // ставим protect — иначе они попадают в наш собственный TUN и
        // получаются loops. dnstt-client мы стартуем под нашим UID и его
        // трафик уходит через addDisallowedApplication, но протекшие
        // outbound из sing-box нужно защитить отдельно.
        if (!service.protect(fd)) {
            Log.w(TAG, "protect($fd) failed — может быть loop при DoH/SOCKS5")
        }
    }

    // -- остальные методы — minimal stub ------------------------------------

    override fun useProcFS(): Boolean = false

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String?,
        sourcePort: Int,
        destinationAddress: String?,
        destinationPort: Int,
    ): ConnectionOwner = error("findConnectionOwner not used")

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        // Auto-reconnect делаем сами через ConnectivityManager.NetworkCallback
        // в TunnelService — так проще и не зависит от sing-box-internals.
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) = Unit

    override fun getInterfaces(): NetworkInterfaceIterator =
        EmptyNetworkInterfaceIterator

    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun clearDNSCache() = Unit
    override fun readWIFIState(): WIFIState? = null
    override fun localDNSTransport(): LocalDNSTransport? = null
    override fun sendNotification(notification: Notification?) = Unit
    override fun startNeighborMonitor(listener: NeighborUpdateListener?) = Unit
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) = Unit
    override fun registerMyInterface(name: String?) = Unit

    @OptIn(ExperimentalEncodingApi::class)
    override fun systemCertificates(): StringIterator {
        val certs = mutableListOf<String>()
        runCatching {
            val ks = KeyStore.getInstance("AndroidCAStore")
            ks.load(null, null)
            val aliases = ks.aliases()
            while (aliases.hasMoreElements()) {
                val cert = ks.getCertificate(aliases.nextElement()) ?: continue
                certs += "-----BEGIN CERTIFICATE-----\n" +
                    Base64.encode(cert.encoded) +
                    "\n-----END CERTIFICATE-----"
            }
        }.onFailure { Log.w(TAG, "systemCertificates", it) }
        return StringList(certs)
    }

    private object EmptyNetworkInterfaceIterator : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = false
        override fun next(): io.nekohasekai.libbox.NetworkInterface =
            error("empty iterator")
    }

    private class StringList(private val items: List<String>) : StringIterator {
        private var idx = 0
        override fun hasNext(): Boolean = idx < items.size
        override fun next(): String = items[idx++]
        override fun len(): Int = items.size
    }
}
