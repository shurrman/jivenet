package net.jivenet.client.config

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class TunnelMode { VPN, PROXY }

@Serializable
data class TunnelConfig(
    val domain: String = "",
    val pubkey: String = "",
    val doh: String = "https://1.1.1.1/dns-query",
    // VPN — основной режим: захватывает весь трафик через TUN+tun2socks.
    // PROXY оставлен для тех, кто хочет per-app настройку или Firefox+SOCKS5.
    val mode: TunnelMode = TunnelMode.VPN,
    val localPort: Int = 1080,
) {
    fun isComplete(): Boolean =
        domain.contains('.') &&
            pubkey.length == 64 && pubkey.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' } &&
            (doh.startsWith("https://") || doh.startsWith("udp://") || doh.startsWith("dot://")) &&
            localPort in 1024..65535

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        /** Парсит JSON из QR-кода. Схема:
         *   {"domain":"...","pubkey":"...","doh":"...","mode":"vpn|proxy","localPort":1080}
         *  Поля кроме domain/pubkey — опциональны.
         */
        fun fromJson(text: String, current: TunnelConfig): TunnelConfig {
            val qr = json.decodeFromString<QrPayload>(text)
            return current.copy(
                domain = qr.domain ?: current.domain,
                pubkey = qr.pubkey ?: current.pubkey,
                doh = qr.doh ?: current.doh,
                mode = qr.mode?.let {
                    runCatching { TunnelMode.valueOf(it.uppercase()) }.getOrNull()
                } ?: current.mode,
                localPort = qr.localPort ?: current.localPort,
            )
        }
    }

    @Serializable
    private data class QrPayload(
        val domain: String? = null,
        val pubkey: String? = null,
        val doh: String? = null,
        val mode: String? = null,
        val localPort: Int? = null,
    )
}

/** Хранилище настроек. Используется из UI через ConfigRepository. */
class ConfigRepository(private val context: Context) {
    companion object {
        private val DOMAIN = stringPreferencesKey("domain")
        private val PUBKEY = stringPreferencesKey("pubkey")
        private val DOH = stringPreferencesKey("doh")
        private val MODE = stringPreferencesKey("mode")
        private val LOCAL_PORT = intPreferencesKey("local_port")
    }

    val flow: Flow<TunnelConfig> = context.dataStore.data.map { p ->
        TunnelConfig(
            domain = p[DOMAIN].orEmpty(),
            pubkey = p[PUBKEY].orEmpty(),
            doh = p[DOH] ?: "https://1.1.1.1/dns-query",
            mode = p[MODE]?.let { runCatching { TunnelMode.valueOf(it) }.getOrNull() } ?: TunnelMode.VPN,
            localPort = p[LOCAL_PORT] ?: 1080,
        )
    }

    suspend fun current(): TunnelConfig = flow.first()

    suspend fun save(cfg: TunnelConfig) {
        context.dataStore.edit { p ->
            p[DOMAIN] = cfg.domain.trim()
            p[PUBKEY] = cfg.pubkey.trim().lowercase()
            p[DOH] = cfg.doh.trim()
            p[MODE] = cfg.mode.name
            p[LOCAL_PORT] = cfg.localPort
        }
    }
}

private val Context.dataStore by preferencesDataStore(name = "tunnel_config")
