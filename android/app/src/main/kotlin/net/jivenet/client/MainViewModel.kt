package net.jivenet.client

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.jivenet.client.config.ConfigRepository
import net.jivenet.client.config.TunnelConfig

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ConfigRepository(app)

    val config: StateFlow<TunnelConfig> = repo.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, TunnelConfig())

    private val _stats = MutableStateFlow(TunnelStats.EMPTY)
    val stats: StateFlow<TunnelStats> = _stats

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // Раз в секунду опрашиваем DnsttBridge (uptime/connected) и
            // sing-box clash-api (байты + активные стримы). Оба источника
            // дёшевы: первый — atomic-чтение, второй — local HTTP.
            //
            // totalConns копим как peak активных за сессию (clash-api отдаёт
            // только текущий список): когда соединения закрываются, активные
            // падают, а total остаётся как «сколько максимум одновременно
            // было открыто» — даёт ощущение масштаба без отдельного счётчика.
            var sessionStartedAt = 0L
            var peakActive = 0
            while (true) {
                val dnstt = runCatching { DnsttBridge.stats() }.getOrDefault(TunnelStats.EMPTY)
                val sb = SingboxStats.fetch()

                // Сбрасываем peak при reconnect (uptime обнулился).
                if (dnstt.uptimeSec == 0L || sessionStartedAt == 0L) {
                    sessionStartedAt = if (dnstt.uptimeSec > 0L) System.currentTimeMillis() else 0L
                    if (dnstt.uptimeSec == 0L) peakActive = 0
                }
                val active = sb?.activeConns ?: 0
                if (active > peakActive) peakActive = active

                _stats.value = dnstt.copy(
                    bytesSent = sb?.bytesUp ?: 0L,
                    bytesRecv = sb?.bytesDown ?: 0L,
                    activeConns = active,
                    totalConns = peakActive.toLong(),
                )
                delay(1_000)
            }
        }
    }

    suspend fun currentConfig(): TunnelConfig = repo.current()
    suspend fun save(cfg: TunnelConfig) = repo.save(cfg)
}
