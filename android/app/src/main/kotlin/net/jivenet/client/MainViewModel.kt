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
        // Пулим статистику из Go-слоя раз в секунду. Это быстро (atomic-чтение),
        // но не стоит крутить таймер, когда ничего не запущено — Go вернёт
        // пустой snapshot, всё ок.
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                runCatching { DnsttBridge.stats() }
                    .onSuccess { _stats.value = it }
                delay(1_000)
            }
        }
    }

    suspend fun currentConfig(): TunnelConfig = repo.current()
    suspend fun save(cfg: TunnelConfig) = repo.save(cfg)
}
