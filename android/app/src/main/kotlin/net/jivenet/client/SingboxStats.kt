package net.jivenet.client

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP-поллер sing-box clash-api для получения статистики туннеля.
 *
 * sing-box запускает clash-совместимый REST на 127.0.0.1:9090 (см.
 * SingboxConfig — секция `experimental.clash_api`). У эндпоинта
 * `GET /connections` есть поля:
 *   {
 *     "downloadTotal": 12345,    // байт скачано через все outbound
 *     "uploadTotal": 6789,       // байт отправлено
 *     "connections": [...],      // активные стримы
 *     "memory": ...
 *   }
 *
 * Для нашего UI берём:
 *   * downloadTotal/uploadTotal → bytesRecv / bytesSent
 *   * len(connections) → activeConns
 *
 * Запрос идёт мимо TUN: наш app исключён через `exclude_package` в
 * sing-box-конфиге, поэтому 127.0.0.1:9090 доходит напрямую до
 * листенера clash-api без петли.
 */
object SingboxStats {

    private const val TAG = "SingboxStats"
    private const val URL_STR = "http://127.0.0.1:9090/connections"

    /** Свежий снимок или null, если sing-box не отвечает / не запущен. */
    suspend fun fetch(): Snapshot? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(URL_STR).openConnection() as HttpURLConnection).apply {
                connectTimeout = 500
                readTimeout = 1500
                requestMethod = "GET"
                // localhost — TLS не нужен.
                useCaches = false
                doInput = true
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (conn.responseCode != 200) return@runCatching null
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = JSONObject(text)
                Snapshot(
                    bytesUp = obj.optLong("uploadTotal", 0L),
                    bytesDown = obj.optLong("downloadTotal", 0L),
                    activeConns = obj.optJSONArray("connections")?.length() ?: 0,
                )
            } finally {
                conn.disconnect()
            }
        }.onFailure {
            // Нормально, когда sing-box ещё не стартовал — листенера нет.
            // Не логируем: иначе спамит каждую секунду до подключения.
        }.getOrNull()
    }

    data class Snapshot(
        val bytesUp: Long,
        val bytesDown: Long,
        val activeConns: Int,
    )
}
