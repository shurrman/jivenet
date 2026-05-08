package net.jivenet.client

import android.content.Context
import android.util.Log
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.SystemProxyStatus
import java.util.concurrent.atomic.AtomicReference

/**
 * Тонкая Kotlin-обёртка над libbox.aar (sing-box gomobile-биндинг).
 *
 * Заменяет связку tun2socks + dnstt-client SOCKS5 на sing-box, который:
 *   * Сам читает TUN fd и поднимает userspace-стек (gVisor)
 *   * Сам резолвит DNS через DoH (фиксит DNS-leak)
 *   * Использует SOCKS5 outbound (наш dnstt-client) как транспорт
 *   * Автоматически переподключается при смене сети (built-in monitor)
 *
 * Если libbox.aar не собран (например в CI без NDK), методы делают no-op
 * и `isAvailable()` возвращает false — пользователь получит понятную
 * ошибку в TunnelService при попытке VPN, а сборка APK не упадёт.
 *
 * Использование:
 *   1) [setup] (один раз на старте Application или Service) — задаёт пути.
 *   2) [start] (каждый раз перед запуском VPN) — поднимает CommandServer
 *      и StartOrReloadService с нашим JSON-конфигом.
 *   3) [stop] — останавливает sing-box service и CommandServer.
 */
object SingboxBridge {

    private const val TAG = "SingboxBridge"

    private val server = AtomicReference<CommandServer?>()

    val isAvailable: Boolean by lazy {
        try {
            Class.forName("io.nekohasekai.libbox.Libbox")
            true
        } catch (_: Throwable) {
            Log.w(TAG, "libbox.aar не подключен — VPN-режим недоступен. " +
                "Соберите через android/scripts/build-singbox-aar.sh.")
            false
        }
    }

    /** Глобальная инициализация libbox (пути для логов/кеша/working). Идемпотентно. */
    fun setup(ctx: Context) {
        if (!isAvailable) return
        // gobind-сгенерированные классы используют get*/set*, поэтому Kotlin
        // не может видеть их как property и приходится использовать сеттеры.
        val opts = SetupOptions().apply {
            basePath = ctx.filesDir.absolutePath
            workingPath = (ctx.getExternalFilesDir(null) ?: ctx.filesDir).absolutePath
            tempPath = ctx.cacheDir.absolutePath
            logMaxLines = 1000
            oomKillerEnabled = false   // наш конфиг невелик, лишний шум не нужен
        }
        runCatching { Libbox.setup(opts) }
            .onFailure { Log.w(TAG, "Libbox.setup", it) }
        Log.i(TAG, "libbox setup: base=${ctx.filesDir.absolutePath} " +
            "version=${runCatching { Libbox.version() }.getOrNull()}")
    }

    /**
     * Запускает sing-box service с переданным JSON-конфигом.
     *
     * @param ctx              для путей setup
     * @param platformInterface объект, реализующий PlatformInterface — в нём
     *                          libbox получит TUN fd через openTun() и т.п.
     * @param configJson       сгенерированный sing-box config
     */
    fun start(ctx: Context, platformInterface: PlatformInterface, configJson: String) {
        check(isAvailable) {
            "libbox.aar отсутствует — соберите через android/scripts/build-singbox-aar.sh"
        }
        setup(ctx)

        // Останавливаем предыдущий запуск если был
        stop()

        val handler = NoopCommandHandler()
        val srv: CommandServer = Libbox.newCommandServer(handler, platformInterface)
            ?: error("Libbox.newCommandServer вернул null")
        server.set(srv)
        srv.start()                                   // gRPC unix-сокет в filesDir/command.sock
        srv.startOrReloadService(configJson, OverrideOptions())
        Log.i(TAG, "sing-box started")
    }

    fun stop() {
        val srv = server.getAndSet(null) ?: return
        runCatching { srv.closeService() }.onFailure { Log.w(TAG, "closeService", it) }
        runCatching { srv.close() }.onFailure { Log.w(TAG, "server.close", it) }
        Log.i(TAG, "sing-box stopped")
    }

    fun isRunning(): Boolean = server.get() != null

    /** sing-box CommandServerHandler — мы все методы делаем no-op:
     *  это контрол-плейн (UI/IPC), нам нужна только функция запуска туннеля. */
    private class NoopCommandHandler : CommandServerHandler {
        override fun serviceStop() = Unit
        override fun serviceReload() = Unit
        override fun getSystemProxyStatus(): SystemProxyStatus =
            SystemProxyStatus().apply { setAvailable(false); setEnabled(false) }
        override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit
        override fun triggerNativeCrash() = Unit
        override fun writeDebugMessage(message: String?) = Unit
    }
}
