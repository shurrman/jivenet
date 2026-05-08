package net.jivenet.client

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.*
import net.jivenet.client.config.ConfigRepository
import net.jivenet.client.config.TunnelConfig

/**
 * VPN-режим. В отличие от Proxy-режима (где пользователь сам настраивает
 * прокси в WiFi/SocksDroid), TunnelService:
 *
 *   1. Запускает dnstt-client (subprocess) — слушает SOCKS5/HTTP на 127.0.0.1:1080.
 *   2. Поднимает TUN через VpnService.Builder — Android начинает заворачивать
 *      ВЕСЬ трафик устройства в этот fd.
 *   3. Запускает tun2socks (in-process Go-биндинг) — читает из TUN-fd и
 *      форвардит TCP/UDP в наш SOCKS5 прокси на 127.0.0.1:1080.
 *
 * Преимущество: пользователь делает один тап и весь трафик идёт через
 * туннель. Никакого SocksDroid и adb. Работает на мобильной сети.
 *
 * ВАЖНО: само приложение исключается из VPN через addDisallowedApplication —
 * иначе DNS-запросы dnstt-client (UDP к резолверу оператора) попадут в TUN
 * и зациклятся.
 */
class TunnelService : VpnService() {

    companion object {
        const val ACTION_START = "net.jivenet.client.START_VPN"
        const val ACTION_STOP = "net.jivenet.client.STOP_VPN"
        private const val TAG = "TunnelService"

        // Приватная подсеть для TUN — не должна пересекаться с реальными.
        private const val VIRTUAL_IP = "10.200.0.2"
        private const val VIRTUAL_DNS = "1.1.1.1"
        private const val MTU = 1500
    }

    @Volatile private var tunnel: ParcelFileDescriptor? = null
    @Volatile private var tunFd: Int = -1
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }

        Notifications.ensureChannel(this)
        val stopIntent = Intent(this, TunnelService::class.java).setAction(ACTION_STOP)
        startForeground(
            Notifications.NOTIF_ID,
            Notifications.build(
                this,
                getString(R.string.notif_vpn_title),
                getString(R.string.status_connecting),
                stopIntent,
            ),
        )

        scope.launch { launchVpn() }
        return START_STICKY
    }

    private suspend fun launchVpn() {
        val cfg = ConfigRepository(this).current()
        if (!cfg.isComplete()) {
            Log.w(TAG, "config incomplete — stopping")
            shutdown(); return
        }
        if (!Tun2socksBridge.isAvailable()) {
            Log.e(TAG, "tun2socks AAR отсутствует — VPN-режим недоступен")
            shutdown(); return
        }

        // 1. dnstt-client должен слушать ДО того, как мы запустим tun2socks.
        try {
            DnsttBridge.startProxy(this, cfg)
            Log.i(TAG, "dnstt-client started (listening 127.0.0.1:${cfg.localPort})")
        } catch (t: Throwable) {
            Log.e(TAG, "dnstt-client start failed", t)
            shutdown(); return
        }

        // 2. Поднимаем TUN.
        val pfd = try {
            buildVpnInterface()
        } catch (t: Throwable) {
            Log.e(TAG, "VpnService.Builder.establish() failed", t)
            shutdown(); return
        }
        tunnel = pfd
        tunFd = pfd.detachFd() // Go возьмёт владение fd
        Log.i(TAG, "TUN установлен fd=$tunFd")

        // 3. tun2socks: TUN-fd → SOCKS5 (наш dnstt-client).
        try {
            Tun2socksBridge.start(tunFd, "socks5://127.0.0.1:${cfg.localPort}", MTU)
        } catch (t: Throwable) {
            Log.e(TAG, "tun2socks start failed", t)
            shutdown()
        }
    }

    private fun buildVpnInterface(): ParcelFileDescriptor {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(MTU)
            .addAddress(VIRTUAL_IP, 30)
            .addRoute("0.0.0.0", 0)            // весь IPv4 в туннель
            // ВАЖНО: addDnsServer НЕ ВЫЗЫВАЕМ намеренно. tun2socks ловит
            // UDP DNS-пакеты в TUN, пытается переслать через SOCKS5 UDP
            // ASSOCIATE — но 3proxy auto-mode на сервере UDP-relay не
            // поддерживает, и DNS-запросы дохнут (Chrome → NO_INTERNET).
            // Без addDnsServer Android использует DNS underlying network
            // (резолвер оператора через ccmni0, мимо VPN). Это DNS-leak,
            // но сами TCP-коннекты к резолвлённым IP идут через TUN.
            // Долгосрочный фикс — fakeip+DoH в самом приложении.
            // Сами себя исключаем — dnstt-client на 127.0.0.1:1080 и его
            // исходящие UDP к DNS оператора не должны заходить обратно в TUN.
            .addDisallowedApplication(packageName)

        // Тап по значку ключа в статус-баре открывает наше приложение.
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        builder.setConfigureIntent(pi)

        return builder.establish()
            ?: error("VpnService.Builder.establish() returned null (no permission?)")
    }

    override fun onRevoke() {
        Log.i(TAG, "onRevoke()")
        shutdown()
    }

    override fun onDestroy() {
        shutdown()
        scope.cancel()
        super.onDestroy()
    }

    private fun shutdown() {
        runCatching { Tun2socksBridge.stop() }
        runCatching { DnsttBridge.stop() }
        runCatching { tunnel?.close() }
        tunnel = null
        tunFd = -1
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
