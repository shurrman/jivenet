package net.jivenet.client

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.jivenet.client.config.ConfigRepository
import net.jivenet.client.config.TunnelConfig

/**
 * VPN-режим v0.9.0 — на базе sing-box (libbox.aar).
 *
 * Поток:
 *   1. dnstt-client (subprocess) → SOCKS5 на 127.0.0.1:cfg.localPort
 *   2. sing-box принимает наш JSON-конфиг через Libbox.NewCommandServer:
 *        - TUN inbound (получает fd через PlatformInterface.openTun)
 *        - DNS-резолвер (DoH через "proxy" outbound — фикс DNS-leak)
 *        - SOCKS5 outbound → 127.0.0.1:cfg.localPort
 *   3. ConnectivityManager.NetworkCallback ловит смену сети
 *      (Wi-Fi ↔ мобильная) → перезапускаем туннель целиком (auto-reconnect).
 *
 * v0.9.4 добавляет [DohWatchdog]: dnstt-client получает DoH из приоритетной
 * очереди (DNS оператора → пользовательский fallback). Watchdog следит за
 * счётчиками clash-api и переключает DoH когда соединения есть, а байты не
 * текут 15+ секунд — типичная картина «DoH unreachable» на новой сети.
 */
class TunnelService : VpnService() {

    companion object {
        const val ACTION_START = "net.jivenet.client.START_VPN"
        const val ACTION_STOP = "net.jivenet.client.STOP_VPN"
        private const val TAG = "TunnelService"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var platform: SingboxPlatform? = null
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null

    // Активные non-VPN физические интерфейсы (Wi-Fi/Cellular/Ethernet).
    // Когда состав меняется (Wi-Fi joins/leaves, switch SIM) — перезапуск.
    private val activeIfaces = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var lastReconnectAt = 0L
    @Volatile private var running = false

    // Состояние DoH-цепочки. Обновляется в startTunnel/restartTunnel и
    // используется watchdog'ом для переключения.
    @Volatile private var dohList: List<String> = emptyList()
    @Volatile private var currentDoh: String = ""
    private var watchdog: DohWatchdog? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch { shutdown(stopSelf = true) }
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

        scope.launch { mutex.withLock { startTunnel() } }
        return START_STICKY
    }

    private suspend fun startTunnel() {
        if (running) {
            Log.i(TAG, "startTunnel: already running, skip")
            return
        }
        val cfg = ConfigRepository(this).current()
        if (!cfg.isComplete()) {
            Log.w(TAG, "config incomplete — stopping")
            shutdown(stopSelf = true); return
        }
        if (!SingboxBridge.isAvailable) {
            Log.e(TAG, "libbox.aar отсутствует — VPN-режим недоступен. " +
                "Соберите через android/scripts/build-singbox-aar.sh.")
            shutdown(stopSelf = true); return
        }

        // 0) Цепочка DoH + пробинг доступности.
        //    Делаем ДО старта sing-box, пока TUN ещё не поднят — иначе
        //    пробинг пошёл бы через наш собственный туннель и result был бы
        //    бесполезен.
        dohList = DohChain.buildList(this, cfg)
        currentDoh = DohChain.pickReachable(dohList) ?: cfg.doh
        Log.i(TAG, "DoH-цепочка: $dohList — стартуем с $currentDoh")

        // 1) dnstt-client subprocess — должен быть до старта sing-box.
        try {
            DnsttBridge.startProxy(this, cfg, currentDoh)
            Log.i(TAG, "dnstt-client запущен на 127.0.0.1:${cfg.localPort} doh=$currentDoh")
        } catch (t: Throwable) {
            Log.e(TAG, "dnstt-client старт упал", t)
            shutdown(stopSelf = true); return
        }

        // 2) sing-box. PlatformInterface создаст TUN через VpnService.Builder
        //    в callback openTun().
        val pi = SingboxPlatform(
            service = this,
            sessionName = getString(R.string.app_name),
            configureIntent = MainActivity::class.java,
        )
        platform = pi
        val configJson = SingboxConfig.build(
            cfg = cfg,
            ourPackageName = packageName,
        )

        try {
            SingboxBridge.start(this, pi, configJson)
            running = true
            Log.i(TAG, "sing-box запущен")
        } catch (t: Throwable) {
            Log.e(TAG, "sing-box не запустился — конфиг или libbox", t)
            shutdown(stopSelf = true); return
        }

        // 3) Watchdog запускаем только если есть на что переключаться.
        if (dohList.size > 1) {
            val wd = DohWatchdog(onSwitchTo = { newDoh ->
                // Не блокируем watchdog: рестарт sing-box занимает 2–3с.
                scope.launch { mutex.withLock { restartWithDoh(newDoh) } }
            })
            wd.start(dohList, currentDoh)
            watchdog = wd
        }

        registerNetworkCallback()
    }

    /**
     * Auto-reconnect. ConnectivityManager шлёт колбэки на смене сети
     * (Wi-Fi ↔ Cellular ↔ нет сети). При появлении новой default-сети
     * после потери — перезапускаем sing-box, чтобы он переоткрыл DoH/SOCKS5
     * через protect()-сокеты на новой underlying network.
     */
    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return

        // registerDefaultNetworkCallback не годится: при поднятом VPN
        // default = наш TUN, и underlying-сеть мы оттуда не видим.
        // Подписываемся отдельно на ВСЕ non-VPN INTERNET-сети с
        // VALIDATED — это исключает VPN-петли и transient-сети
        // которые Android создаёт во время probe.
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val iface = cm.getLinkProperties(network)?.interfaceName ?: return
                val added = activeIfaces.add(iface)
                if (added && running) maybeRestart("интерфейс +$iface (активны: $activeIfaces)")
            }

            override fun onLost(network: Network) {
                val iface = cm.getLinkProperties(network)?.interfaceName ?: return
                activeIfaces.remove(iface)
                // На onLost не рестартим — sing-box dies сам когда сеть
                // действительно ушла, дождёмся onAvailable.
            }
        }
        // Дебаунс при регистрации: первая порция onAvailable (для уже
        // активных сетей) не должна вызывать restart — это initial state.
        lastReconnectAt = System.currentTimeMillis()
        cm.registerNetworkCallback(req, cb)
        connectivityCallback = cb
        Log.i(TAG, "NetworkCallback registered (NOT_VPN+VALIDATED)")
    }

    /**
     * Дебаунс перезапуска: 3-секундное окно. Защищает от:
     *   * initial-flurry onAvailable при registerNetworkCallback;
     *   * validation-bouncing когда Android создаёт временные Network handles;
     *   * onAvailable/onLost-флэппинга при коротких глитчах сети.
     */
    private fun maybeRestart(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastReconnectAt < 3_000L) {
            Log.d(TAG, "skip restart ($reason) — debounce")
            return
        }
        lastReconnectAt = now
        Log.i(TAG, "$reason — перезапуск туннеля")
        scope.launch { mutex.withLock { restartTunnel() } }
    }

    /**
     * Полный рестарт после смены сети. Заодно пересобираем DoH-цепочку:
     * cellular DNS на новой сети может отличаться (другая SIM/APN), либо
     * SIM могла пропасть совсем — тогда останется только fallback.
     */
    private suspend fun restartTunnel() {
        if (!running) return
        val cfg = ConfigRepository(this@TunnelService).current()

        val newList = DohChain.buildList(this, cfg)
        // Если в новом списке ещё есть текущий DoH — оставляем его (туннель
        // только что работал на нём, нет смысла менять). Иначе пробуем
        // выбрать живой через пробинг.
        val newDoh = if (currentDoh in newList) currentDoh
            else DohChain.pickReachable(newList) ?: cfg.doh
        dohList = newList
        currentDoh = newDoh
        Log.i(TAG, "restart: DoH-цепочка $newList → текущий $newDoh")

        runCatching { SingboxBridge.stop() }
        platform?.closeTun()
        runCatching { DnsttBridge.stop() }

        try {
            DnsttBridge.startProxy(this@TunnelService, cfg, newDoh)
        } catch (t: Throwable) {
            Log.e(TAG, "dnstt restart fail", t)
            shutdown(stopSelf = true); return
        }
        val pi = SingboxPlatform(this@TunnelService, getString(R.string.app_name), MainActivity::class.java)
        platform = pi
        try {
            SingboxBridge.start(
                this@TunnelService,
                pi,
                SingboxConfig.build(cfg = cfg, ourPackageName = packageName),
            )
            Log.i(TAG, "перезапуск туннеля успешен (doh=$newDoh)")
        } catch (t: Throwable) {
            Log.e(TAG, "sing-box restart fail", t)
            shutdown(stopSelf = true); return
        }

        // Обновляем watchdog (если он есть): новый список + позиция.
        watchdog?.setDohList(newList, newDoh)
        // Если список вырос с 1 до >1 (например Wi-Fi пропал, остался только
        // cellular но cfg.doh — публичный) — нужен новый watchdog.
        if (watchdog == null && newList.size > 1) {
            val wd = DohWatchdog(onSwitchTo = { d ->
                scope.launch { mutex.withLock { restartWithDoh(d) } }
            })
            wd.start(newList, newDoh)
            watchdog = wd
        }
    }

    /**
     * Рестарт по решению watchdog'а — переключение DoH в текущей сети.
     * Сеть не менялась, NetworkCallback не трогаем; и **sing-box не
     * трогаем** — рестартим ТОЛЬКО dnstt-client subprocess.
     *
     * Историческая справка: до v0.9.5 этот метод делал полный
     * `SingboxBridge.stop() → start()` с новым TUN на каждый switch.
     * После 4-х таких циклов в течение 10 минут libbox/gVisor стек
     * залипал (TUN reader переставал читать пакеты, при этом clash-api
     * продолжал отвечать), и весь туннель тихо «вис» — приложения
     * получали `DNS_PROBE_FINISHED_NO_INTERNET`. Лечилось только
     * disconnect/reconnect через UI. Корень — баг в libbox lifecycle
     * на множественных stop/start в коротком окне.
     *
     * Теперь: убиваем только dnstt-client subprocess, поднимаем заново
     * на тот же 127.0.0.1:cfg.localPort с новым DoH-аргументом. Sing-box
     * остаётся живым — TUN, gVisor stack, clash-api всё работает без
     * перерыва. Существующие SOCKS5-соединения sing-box → 127.0.0.1:1080
     * получают TCP RST когда dnstt-client умирает, sing-box re-dial'ит
     * к новому dnstt-client при следующем пакете из приложения.
     * Cтавит ~0.3-0.5с перерыв вместо ~3-5с full restart, и без риска
     * для libbox-state.
     */
    private suspend fun restartWithDoh(newDoh: String) {
        if (!running) return
        if (newDoh == currentDoh) return
        currentDoh = newDoh
        val cfg = ConfigRepository(this@TunnelService).current()

        runCatching { DnsttBridge.stop() }
        try {
            DnsttBridge.startProxy(this@TunnelService, cfg, newDoh)
        } catch (t: Throwable) {
            Log.e(TAG, "dnstt restart-doh fail", t)
            shutdown(stopSelf = true); return
        }
        Log.i(TAG, "watchdog: dnstt перезапущен на DoH=$newDoh, sing-box не трогали")

        // Сбросить счётчики watchdog'а: clash-api totals не обнулились
        // (sing-box-то живой), но «качество» текущего DoH хочется
        // мерить с нуля — поэтому resetCounters в setDohList:
        //   * lastBytesUp/Down = 0  → первая tick'а после рестарта
        //     зачтёт текущие totals как «download grew» и обновит
        //     lastDownGrowthAt; это эквивалентно «начали отсчёт».
        //   * armedAt = now + 60с → даём время на новый KCP+TLS+smux
        //     handshake через новый DoH.
        watchdog?.setDohList(dohList, newDoh)
    }

    override fun onRevoke() {
        Log.i(TAG, "onRevoke()")
        scope.launch { mutex.withLock { shutdown(stopSelf = true) } }
    }

    override fun onDestroy() {
        runBlocking { shutdown(stopSelf = false) }
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun shutdown(stopSelf: Boolean) {
        running = false
        connectivityCallback?.let { cb ->
            runCatching {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
            }
        }
        connectivityCallback = null

        watchdog?.stop()
        watchdog = null

        runCatching { SingboxBridge.stop() }
        platform?.closeTun()
        platform = null
        runCatching { DnsttBridge.stop() }

        stopForeground(STOP_FOREGROUND_REMOVE)
        if (stopSelf) stopSelf()
    }
}
