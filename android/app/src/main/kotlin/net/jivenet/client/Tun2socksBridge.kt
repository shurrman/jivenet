package net.jivenet.client

import android.util.Log

/**
 * Тонкая Kotlin-обёртка над gomobile-биндингом tun2socks-mobile.
 *
 * После `gomobile bind -o app/libs/tun2socks.aar` gomobile генерирует класс
 * `tun2socksmobile.Tun2socksmobile` со static-методами Start/Stop/IsRunning.
 * Этот файл изолирует остальные компоненты от деталей биндинга — если
 * сигнатура tun2socks API меняется, правка ограничена этим файлом.
 *
 * Если AAR ещё не собран (что бывает в CI без NDK), методы делают no-op
 * и возвращают false на isRunning, чтобы сборка не падала на Kotlin level.
 */
object Tun2socksBridge {

    private const val TAG = "Tun2socksBridge"

    private val available: Boolean by lazy {
        try {
            // Класс генерируется gomobile из пакета tun2socksmobile.
            Class.forName("tun2socksmobile.Tun2socksmobile")
            true
        } catch (_: ClassNotFoundException) {
            Log.w(TAG, "tun2socks AAR не подключен — VPN-режим недоступен")
            false
        }
    }

    fun isAvailable(): Boolean = available

    fun start(tunFd: Int, proxyUrl: String, mtu: Int = 1500) {
        if (!available) {
            throw IllegalStateException(
                "tun2socks AAR отсутствует. Соберите через android/scripts/build-aar.sh"
            )
        }
        try {
            val cls = Class.forName("tun2socksmobile.Tun2socksmobile")
            // gomobile из Go-int32 генерирует Java-int.
            val m = cls.getDeclaredMethod(
                "start",
                Int::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType,
            )
            m.invoke(null, tunFd, proxyUrl, mtu)
            Log.i(TAG, "tun2socks started fd=$tunFd → $proxyUrl mtu=$mtu")
        } catch (t: Throwable) {
            Log.e(TAG, "tun2socks start failed", t)
            throw t
        }
    }

    fun stop() {
        if (!available) return
        runCatching {
            Class.forName("tun2socksmobile.Tun2socksmobile")
                .getDeclaredMethod("stop")
                .invoke(null)
        }.onFailure { Log.w(TAG, "tun2socks stop", it) }
    }

    fun isRunning(): Boolean {
        if (!available) return false
        return runCatching {
            Class.forName("tun2socksmobile.Tun2socksmobile")
                .getDeclaredMethod("isRunning")
                .invoke(null) as Boolean
        }.getOrDefault(false)
    }
}
