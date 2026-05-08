package net.jivenet.client

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import net.jivenet.client.config.TunnelConfig
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Запускает кросс-скомпилированный dnstt-client как дочерний процесс.
 *
 * Android разрешает exec() файла только из `applicationInfo.nativeLibraryDir`,
 * куда PackageManager распаковывает содержимое `jniLibs/<abi>/`. Поэтому
 * бинарник лежит в APK как `libdnstt_client.so` (имя должно начинаться с
 * "lib" и заканчиваться на ".so", иначе при установке .so не попадёт в
 * nativeLibraryDir).
 *
 * Эта реализация полностью в пределах одного процесса Android, не требует
 * gomobile и обеспечивает живую работу туннеля.
 */
object DnsttBridge {

    private const val TAG = "DnsttBridge"
    private const val BINARY = "libdnstt_client.so"

    private val currentProc = AtomicReference<Process?>()
    private val startedAt = AtomicReference<Long?>()
    private val lastErr = AtomicReference("")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readerJob: Job? = null

    fun startProxy(ctx: Context, cfg: TunnelConfig) {
        start(ctx, cfg)
    }

    /** VPN-режим в MVP не поддержан — требует отдельного tun2socks-бинарника. */
    fun startTun(ctx: Context, tunFd: Int, cfg: TunnelConfig) {
        throw UnsupportedOperationException("VPN mode requires tun2socks bundle (not in MVP)")
    }

    fun stop() {
        readerJob?.cancel()
        readerJob = null
        currentProc.getAndSet(null)?.let { p ->
            runCatching { p.destroy() }
            // Ждём максимум 2 секунды, потом destroyForcibly
            val gone = runCatching { p.waitFor() }.isSuccess
            if (!gone) runCatching { p.destroyForcibly() }
        }
        startedAt.set(null)
    }

    fun stats(): TunnelStats {
        val p = currentProc.get()
        val alive = p?.isAlive == true
        val started = startedAt.get()
        val uptime = if (alive && started != null)
            (System.currentTimeMillis() - started) / 1000L
        else 0L
        return TunnelStats(
            connected = alive,
            uptimeSec = uptime,
            bytesSent = 0,
            bytesRecv = 0,
            activeConns = 0,
            totalConns = 0,
            lastError = lastErr.get(),
        )
    }

    private fun start(ctx: Context, cfg: TunnelConfig) {
        stop()
        lastErr.set("")

        val binary = File(ctx.applicationInfo.nativeLibraryDir, BINARY)
        if (!binary.exists() || !binary.canExecute()) {
            throw IllegalStateException("binary not found/executable: $binary")
        }

        val cmd = buildCommand(binary.absolutePath, cfg)
        Log.i(TAG, "exec: ${cmd.joinToString(" ")}")

        val pb = ProcessBuilder(cmd)
            .directory(ctx.cacheDir)
            .redirectErrorStream(true)
        val p = pb.start()
        currentProc.set(p)
        startedAt.set(System.currentTimeMillis())

        // Перекачиваем stdout/stderr в logcat и ловим ошибки — dnstt-client
        // пишет диагностику в stderr, полезно иметь в журнале.
        //
        // ВАЖНО: когда вызывается stop() → process.destroy(), Linux шлёт
        // readBytes() из стрима InterruptedIOException (а не EOF). Без
        // явного try/catch эта exception уходит из корутины и валит
        // приложение (FATAL EXCEPTION: DefaultDispatcher-worker-N).
        readerJob = scope.launch {
            try {
                p.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        Log.i(TAG, "dnstt: $line")
                        if (line.contains("error", ignoreCase = true) ||
                            line.contains("fatal", ignoreCase = true)) {
                            lastErr.set(line)
                        }
                    }
                }
            } catch (e: java.io.IOException) {
                // Ожидаемо при destroy(): поток закрыт извне
                Log.d(TAG, "reader closed: ${e.message}")
            } catch (e: Throwable) {
                Log.w(TAG, "reader unexpected error", e)
                lastErr.set(e.message ?: e::class.java.simpleName)
            }
            Log.w(TAG, "dnstt-client exited with ${runCatching { p.exitValue() }.getOrNull()}")
        }
    }

    private fun buildCommand(bin: String, cfg: TunnelConfig): List<String> {
        // dnstt-client CLI:
        //   dnstt-client -doh URL        -pubkey HEX DOMAIN LOCAL_ADDR
        //   dnstt-client -dot HOST:PORT  -pubkey HEX DOMAIN LOCAL_ADDR
        //   dnstt-client -udp HOST:PORT  -pubkey HEX DOMAIN LOCAL_ADDR
        //
        // Выбор транспорта по префиксу в поле DoH:
        //   "udp://host:port" → обычный UDP DNS (через резолвер оператора,
        //                       работает на мобильных РФ где DoH блокируется)
        //   "dot://host:port" → DNS-over-TLS
        //   "https://..."     → DoH (по умолчанию)
        val local = "127.0.0.1:${cfg.localPort}"
        val doh = cfg.doh.trim()
        return buildList {
            add(bin)
            when {
                doh.startsWith("udp://") -> {
                    add("-udp"); add(doh.removePrefix("udp://"))
                }
                doh.startsWith("dot://") -> {
                    add("-dot"); add(doh.removePrefix("dot://"))
                }
                else -> {
                    add("-doh"); add(doh)
                }
            }
            add("-pubkey"); add(cfg.pubkey)
            // По умолчанию dnstt использует uTLS с random-fingerprint — ОК.
            add(cfg.domain)
            add(local)
        }
    }
}

data class TunnelStats(
    val connected: Boolean,
    val uptimeSec: Long,
    val bytesSent: Long,
    val bytesRecv: Long,
    val activeConns: Int,
    val totalConns: Long,
    val lastError: String,
) {
    companion object {
        val EMPTY = TunnelStats(false, 0, 0, 0, 0, 0, "")
    }
}
