package net.jivenet.client

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import net.jivenet.client.config.TunnelConfig
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * Управление приоритетом DoH-резолверов и автоматический failover.
 *
 *   primary  = `udp://<dns-cellular>:53` — DNS оператора активной SIM
 *              (детектится через `SystemDns.cellularIPv4Resolver`).
 *              На мобильной сети РФ DPI часто пропускает запросы к
 *              собственному резолверу, не глядя в payload — нам этого
 *              хватает для KCP-over-DNS.
 *
 *   fallback = `cfg.doh` — то, что юзер прописал в настройках (обычно
 *              публичный DoH типа `https://1.1.1.1/dns-query`).
 *              Используется когда primary не отвечает (Wi-Fi default-route,
 *              нет SIM, оператор положил свой DNS, и т.п.).
 *
 * При старте делаем быстрый пробинг каждой записи в очереди (UDP/TCP
 * connect), чтобы не входить в туннель с заведомо мёртвой DoH. Если ни одна
 * не пингуется — берём первую как «лучше что-то, чем ничего» (вдруг сеть
 * пингов режет, а DNS-payload пропускает).
 *
 * После старта раз в 5 секунд опрашиваем clash-api и применяем два
 * сигнала «DoH сломалась»:
 *
 *   * **STALL**: `uploadTotal` рос за последние 15с (юзер шлёт запросы),
 *     `downloadTotal` за это время не двигался → запросы пропадают в
 *     чёрную дыру, переключаем. Сигнал актуален и сразу после старта,
 *     и через несколько минут жизни.
 *
 *   * **DEAD-FROM-START**: `downloadTotal == 0` после полного 60-секундного
 *     initial-deadline И есть активные соединения → DoH не работает на
 *     этом носителе с самого начала, переключаем. 60с — окно на холодный
 *     KCP+DoH+TLS handshake, иначе медленные сети ловят false-positive.
 *
 * Idle (никаких запросов от приложений: ни upload, ни download) не считаем
 * за поломку — туннель просто простаивает. Без этого юзер открывал бы
 * страницу, она грузилась, потом сидел на ней — а через 15с watchdog
 * флипал бы DoH «потому что ничего не идёт». Поэтому сигнал на STALL
 * требует подтверждённого upload.
 *
 * Цикл бесконечный: cellular → fallback → cellular → ... пока пользователь
 * не нажмёт «Отключить». Это ровно то, что хотел юзер: «если от фоллбека
 * нет ответа — переключаемся обратно на сотовый ДНС».
 */
class DohWatchdog(
    private val onSwitchTo: (newDoh: String) -> Unit,
) {
    companion object {
        private const val TAG = "DohWatchdog"
        private const val POLL_INTERVAL_MS = 5_000L
        private const val STALL_THRESHOLD_MS = 15_000L
        // Initial-deadline: если за это время через туннель не прошёл ни
        // один байт, считаем DoH сломанной и переключаемся (даже если
        // никакого «провисания после прогресса» не было). 60с — потому что
        // KCP+DoH+smux handshake на медленной/высоколатентной сети может
        // занять до ~40с (uTLS негоциация, TLS handshake через DoH, KCP
        // retries при packet loss). 30с триггерил false-positive ping-pong
        // на нашем тесте — sing-box просто не успевал прокинуть байты до
        // первого тика watchdog'а.
        private const val INITIAL_DEADLINE_MS = 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var dohList: List<String> = emptyList()
    @Volatile
    private var dohIdx: Int = 0

    val current: String? get() = dohList.getOrNull(dohIdx)

    // Разделяем upload и download — это нужно чтобы отличать **stall**
    // (запросы уходят, ответы не приходят = DoH мертва на пути return) от
    // **idle** (приложения молчат = ни запросов, ни ответов; watchdog не
    // должен переключать). Без этого юзер заходит на ifconfig.co, видит
    // ответ, потом просто смотрит экран — и через 15с туннель неоправданно
    // флипает на cellular.
    @Volatile private var lastBytesUp: Long = 0L
    @Volatile private var lastBytesDown: Long = 0L
    @Volatile private var lastUpGrowthAt: Long = 0L
    @Volatile private var lastDownGrowthAt: Long = 0L
    /**
     * До этой timestamp watchdog игнорирует «нулевой download + есть
     * соединения»: это нормальная картина первых секунд жизни туннеля
     * (sing-box создаёт idle DoH probe ещё до пользовательских запросов,
     * KCP+TLS handshake занимает время). Без этого окна watchdog ошибочно
     * срабатывает на стартовых connection'ах без байт.
     */
    @Volatile private var armedAt: Long = 0L

    private var loopJob: Job? = null

    /** Стартует тикер. Вызывается из TunnelService после успешного старта sing-box. */
    fun start(initialList: List<String>, currentDoh: String) {
        dohList = initialList
        dohIdx = initialList.indexOf(currentDoh).coerceAtLeast(0)
        resetCounters()
        loopJob?.cancel()
        loopJob = scope.launch { runLoop() }
        Log.i(TAG, "started: список=$initialList текущий=$currentDoh idx=$dohIdx")
    }

    /**
     * Обновить список после auto-reconnect (cellular DNS мог поменяться,
     * либо SIM пропала). Сохраняем `currentDoh` если он есть в новом списке.
     */
    fun setDohList(newList: List<String>, currentDoh: String) {
        dohList = newList
        dohIdx = newList.indexOf(currentDoh).coerceAtLeast(0)
        resetCounters()
        Log.i(TAG, "обновлён список=$newList текущий=$currentDoh idx=$dohIdx")
    }

    /**
     * Сброс счётчиков stall'а после рестарта sing-box: clash-api теперь
     * отдаёт 0/0, и без сброса watchdog увидел бы «ничего не растёт» сразу.
     */
    private fun resetCounters() {
        lastBytesUp = 0L
        lastBytesDown = 0L
        val now = System.currentTimeMillis()
        lastUpGrowthAt = 0L      // 0 = «ни разу не было» (используем как условие)
        lastDownGrowthAt = 0L
        armedAt = now + INITIAL_DEADLINE_MS
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        scope.cancel()
    }

    private suspend fun runLoop() {
        while (currentCoroutineContext().isActive) {
            delay(POLL_INTERVAL_MS)
            try {
                tick()
            } catch (t: Throwable) {
                Log.w(TAG, "tick error", t)
            }
        }
    }

    private suspend fun tick() {
        if (dohList.size <= 1) return  // нечего на что переключать
        val snap = SingboxStats.fetch() ?: return
        val now = System.currentTimeMillis()

        if (snap.bytesDown > lastBytesDown) {
            lastBytesDown = snap.bytesDown
            lastDownGrowthAt = now
        }
        if (snap.bytesUp > lastBytesUp) {
            lastBytesUp = snap.bytesUp
            lastUpGrowthAt = now
        }

        // Download «свежий» (рос за последние 15с) → DoH живой, exit.
        if (lastDownGrowthAt > 0L && now - lastDownGrowthAt < STALL_THRESHOLD_MS) return

        val downStaleMs = if (lastDownGrowthAt > 0L) now - lastDownGrowthAt else now - armedAt + INITIAL_DEADLINE_MS

        // Условия переключения:
        //
        //   (A) **STALL**: upload растёт сейчас (запросы уходят), а download
        //       не двигался ≥15с → запросы уходят в чёрную дыру, свитч.
        //       Это срабатывает и до, и после initial-deadline.
        if (lastUpGrowthAt > 0L && now - lastUpGrowthAt < STALL_THRESHOLD_MS) {
            switchToNext(snap, "upload без download ${downStaleMs}ms")
            return
        }

        //   (B) **DEAD-FROM-START**: download НИКОГДА не приходил, есть
        //       активные соединения, прошёл initial-deadline → DoH вообще
        //       не работает на этом носителе. Это нужно чтобы туннель
        //       автоматически слез с заведомо мёртвой DoH даже когда юзер
        //       и не пробовал ничего открыть.
        if (lastDownGrowthAt == 0L && now >= armedAt && snap.activeConns > 0) {
            switchToNext(snap, "${INITIAL_DEADLINE_MS / 1000}с без download")
            return
        }

        // Иначе — туннель здоров и просто idle (юзер ничего не запрашивает).
        // Не трогаем; даже если 5 минут тишины — это не повод флипать,
        // download просто не нужен сейчас.
    }

    private fun switchToNext(snap: SingboxStats.Snapshot, reason: String) {
        val newIdx = (dohIdx + 1) % dohList.size
        val oldDoh = dohList[dohIdx]
        val newDoh = dohList[newIdx]
        Log.i(TAG, "$reason при ${snap.activeConns} активных — переключаемся $oldDoh → $newDoh")
        dohIdx = newIdx
        resetCounters()
        onSwitchTo(newDoh)
    }
}

/**
 * Утилиты для DoH-цепочки: построение списка приоритетов и быстрый пробинг
 * достижимости. Вынесены из DohWatchdog чтобы TunnelService мог пользоваться
 * ими напрямую при старте/рестарте, не создавая инстанс watchdog'а.
 */
object DohChain {

    private const val TAG = "DohChain"
    // 5с — TCP-connect к Cloudflare через Wi-Fi сразу после `switch`
    // занимает 3-4с (свежее соединение, без keep-alive). 3с давало
    // false-negative «не пингуется» на здоровой сети.
    private const val PROBE_TIMEOUT_MS = 5_000

    /**
     * Список DoH в порядке приоритета:
     *   1) DNS оператора в udp:// (если cellular доступен и его DNS известен)
     *   2) пользовательский cfg.doh (если он не совпал с пунктом 1)
     */
    fun buildList(ctx: Context, cfg: TunnelConfig): List<String> {
        val cellular = SystemDns.cellularUdpResolverUrl(ctx)
        val user = cfg.doh.trim()
        return listOfNotNull(cellular, user.takeIf { it.isNotEmpty() }).distinct()
    }

    /**
     * Перебирает список и возвращает первую DoH, которая отвечает на пробинг
     * за `PROBE_TIMEOUT_MS`. Если ни одна не отвечает — возвращает первый
     * элемент (лучше что-то, чем ничего: иногда сеть режет ICMP/SYN извне,
     * но DNS-payload пропускает).
     */
    suspend fun pickReachable(list: List<String>): String? {
        if (list.isEmpty()) return null
        for (doh in list) {
            if (probe(doh, PROBE_TIMEOUT_MS)) {
                Log.i(TAG, "пингуется: $doh")
                return doh
            }
            Log.w(TAG, "не пингуется: $doh — пробуем следующий")
        }
        Log.w(TAG, "никто не пингуется — берём первый: ${list.first()}")
        return list.first()
    }

    /**
     * Проверка достижимости DoH-эндпоинта.
     *   * `udp://host:port` — отправляем минимальный DNS-запрос (`. NS IN`),
     *     ждём ЛЮБОГО UDP-ответа. Если хост недосягаем — `SocketTimeoutException`.
     *   * `https://host/...` — TCP-connect к host:443. Не делаем настоящий
     *     HTTPS-запрос, только проверяем TCP-достижимость — этого достаточно,
     *     т.к. dnstt-client в DoH-режиме поднимет TLS сам, а нам важна только
     *     маршрутизация.
     *   * `dot://host:port` — TCP-connect к host:port (обычно 853).
     */
    suspend fun probe(doh: String, timeoutMs: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            when {
                doh.startsWith("udp://") -> probeUdp(doh.removePrefix("udp://"), timeoutMs)
                doh.startsWith("dot://") -> probeTcp(doh.removePrefix("dot://"), 853, timeoutMs)
                doh.startsWith("https://") -> probeHttps(doh, timeoutMs)
                else -> false
            }
        }.onFailure { Log.d(TAG, "probe $doh: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrDefault(false)
    }

    private fun probeUdp(hostPort: String, timeoutMs: Int): Boolean {
        val (host, port) = parseHostPort(hostPort, defaultPort = 53)
        DatagramSocket().use { s ->
            s.soTimeout = timeoutMs
            // Минимальный валидный DNS-query: id=0x1234, RD=1, QDCOUNT=1,
            // QNAME = "" (root), QTYPE = NS (2), QCLASS = IN (1).
            // 17 байт. Любой работающий резолвер ответит, даже если NS root
            // у него не закеширован — вернёт REFUSED или SERVFAIL.
            val q = byteArrayOf(
                0x12, 0x34,  // tx id
                0x01, 0x00,  // flags: standard query, RD=1
                0x00, 0x01,  // QDCOUNT=1
                0x00, 0x00,  // ANCOUNT
                0x00, 0x00,  // NSCOUNT
                0x00, 0x00,  // ARCOUNT
                0x00,        // QNAME = "." (одна метка нулевой длины)
                0x00, 0x02,  // QTYPE = NS
                0x00, 0x01,  // QCLASS = IN
            )
            s.send(DatagramPacket(q, q.size, InetAddress.getByName(host), port))
            val buf = ByteArray(512)
            s.receive(DatagramPacket(buf, buf.size))
            return true
        }
    }

    private fun probeHttps(url: String, timeoutMs: Int): Boolean {
        val u = URI(url)
        val host = u.host ?: return false
        val port = if (u.port > 0) u.port else 443
        return probeTcp("$host:$port", port, timeoutMs)
    }

    private fun probeTcp(hostPort: String, defaultPort: Int, timeoutMs: Int): Boolean {
        val (host, port) = parseHostPort(hostPort, defaultPort)
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            return true
        }
    }

    private fun parseHostPort(hostPort: String, defaultPort: Int): Pair<String, Int> {
        val idx = hostPort.lastIndexOf(':')
        return if (idx >= 0 && !hostPort.endsWith(']')) {
            val host = hostPort.substring(0, idx)
            val port = hostPort.substring(idx + 1).toIntOrNull() ?: defaultPort
            host to port
        } else hostPort to defaultPort
    }
}
// 1778422350
