package net.jivenet.client

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import net.jivenet.client.config.ConfigRepository

/**
 * Proxy-режим: туннель работает как локальный SOCKS5 на 127.0.0.1:<port>.
 * VpnService не используется; Android не знает, что это прокси, поэтому
 * пользователь должен настроить приложения вручную.
 */
class ProxyService : Service() {

    companion object {
        const val ACTION_START = "net.jivenet.client.START_PROXY"
        const val ACTION_STOP  = "net.jivenet.client.STOP_PROXY"
        private const val TAG = "ProxyService"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { shutdown(); return START_NOT_STICKY }

        Notifications.ensureChannel(this)
        scope.launch {
            val cfg = ConfigRepository(this@ProxyService).current()
            if (!cfg.isComplete()) { shutdown(); return@launch }

            val stopIntent = Intent(this@ProxyService, ProxyService::class.java)
                .setAction(ACTION_STOP)
            startForeground(
                Notifications.NOTIF_ID,
                Notifications.build(
                    this@ProxyService,
                    getString(R.string.notif_proxy_title),
                    getString(R.string.proxy_hint, "127.0.0.1", cfg.localPort),
                    stopIntent
                )
            )

            try {
                DnsttBridge.startProxy(this@ProxyService, cfg)
                Log.i(TAG, "SOCKS5 listening on 127.0.0.1:${cfg.localPort}")
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t)
                shutdown()
            }
        }
        return START_STICKY
    }

    private fun shutdown() {
        try { DnsttBridge.stop() } catch (_: Throwable) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        try { DnsttBridge.stop() } catch (_: Throwable) {}
        scope.cancel()
        super.onDestroy()
    }
}
