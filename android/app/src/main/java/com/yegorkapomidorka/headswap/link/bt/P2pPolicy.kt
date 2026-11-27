package com.yegorkapomidorka.headswap.link.bt

/** Что делать с очередным TARGET_INFO от ГУ. */
enum class P2pOnTarget { START, RESTART, SKIP }

/** Чистая логика входа в P2P-группу ГУ, без android.*: проверяется JVM-тестами. */
object P2pPolicy {
    /**
     * Новый TARGET_INFO (ГУ шлёт его на каждое подключение Bluetooth): в группе уже - пропускаем, IP переотдаётся по
     * IP_REQUEST; попытка идёт, но не вошли (BUSY, пир не нашёлся, Wi-Fi включили позже, ГУ сменила имя) - начинаем
     * заново с новыми реквизитами (h126). Те же реквизиты, а попытке меньше [freshMs] - не трогаем: rejoin спрашивает
     * TARGET_INFO раз в 5-10 с, и рестарт на каждый ответ рвал поиск и висящее приглашение (h211, Равиль 21.10).
     */
    fun onTarget(active: Boolean, joined: Boolean, same: Boolean = false, ageMs: Long = Long.MAX_VALUE, freshMs: Long = 30_000L): P2pOnTarget = when {
        joined -> P2pOnTarget.SKIP
        active && same && ageMs < freshMs -> P2pOnTarget.SKIP
        active -> P2pOnTarget.RESTART
        else -> P2pOnTarget.START
    }

    /**
     * Группа ГУ распалась, а Bluetooth жив (h211, Mazda G6S: задний ход дольше ~25 с): сама ГУ TARGET_INFO не шлёт,
     * пока не переподключит Bluetooth, а это бывает только после сна машины. Просим его сами: через 2 с, 5 с, дальше
     * раз в 10 с; null - хватит (~10 мин без входа, ГУ явно не хочет).
     */
    fun rejoinDelayMs(attempt: Int): Long? = when {
        attempt < 0 -> null
        attempt == 0 -> 2_000L
        attempt == 1 -> 5_000L
        attempt < REJOIN_ATTEMPTS -> 10_000L
        else -> null
    }

    /**
     * Запросы TARGET_INFO на 2-й, 7-й, 17-й и 27-й секундах ГУ отвечает, но пир в эфире так и не появился (первая 30-секундная
     * попытка входа кончилась на ~32 с): Desay G6S поднимает Wi-Fi Direct только на новом подключении Bluetooth (h211,
     * S23 23.10: 2 мин rejoin без пира, после переподключения BT пир за 1,5 с; h185). На ~37 с рвём RFCOMM.
     */
    fun rejoinDropBt(attempt: Int): Boolean = attempt >= REJOIN_DROP_BT_AFTER

    const val REJOIN_ATTEMPTS = 60
    const val REJOIN_DROP_BT_AFTER = 4
}

/**
 * Вошли в группу ГУ, отдали ей адрес, а TCP так и нет (#111, MG: ГУ раз за разом повторяет Bluetooth-обмен, пока группа
 * не распадётся). Тогда Wi-Fi Direct в этом ожидании бросаем и ждём машину через её Wi-Fi или хотспот телефона.
 * Условие: с первого входа прошло [noTcpMs], MD_WIFI_IP ушёл хотя бы [minIpSends] раз, с последнего прошло [settleMs]
 * (медленной ГУ даём время открыть TCP), и в этом ожидании TCP ни разу не поднимался. Живёт одно ожидание: [newWait]
 * зовёт BridgeService на новом ожидании; перезапуск пробника посреди ожидания счётчики не трогает. После 6/6 ([tcpUp])
 * до нового ожидания молчит: Wi-Fi Direct у этой машины работает, а rejoin после разрыва сессии рвать нельзя (h211).
 * Без android.*, вызовы с любых нитей.
 */
class NoTcpTracker(
    private val noTcpMs: Long = 25_000L,
    private val minIpSends: Int = 2,
    private val settleMs: Long = 6_000L,
) {
    private var firstJoinAt: Long? = null
    private var lastIpAt: Long? = null
    private var tcpProven = false
    var ipSends = 0
        @Synchronized get
        private set
    var fired = false
        @Synchronized get
        private set

    @Synchronized fun newWait() {
        firstJoinAt = null
        lastIpAt = null
        ipSends = 0
        tcpProven = false
        fired = false
    }

    /** Вошли в группу; true - первый вход в этом ожидании. */
    @Synchronized fun joined(now: Long): Boolean {
        if (firstJoinAt != null) return false
        firstJoinAt = now
        return true
    }

    @Synchronized fun ipSent(now: Long) {
        ipSends++
        lastIpAt = now
    }

    @Synchronized fun tcpUp() {
        tcpProven = true
    }

    @Synchronized fun sinceJoinMs(now: Long): Long? = firstJoinAt?.let { now - it }

    @Synchronized fun shouldFallback(now: Long, tcpUpNow: Boolean): Boolean {
        val first = firstJoinAt ?: return false
        val last = lastIpAt ?: return false
        return !fired && !tcpProven && !tcpUpNow && now - first >= noTcpMs && ipSends >= minIpSends && now - last >= settleMs
    }

    @Synchronized fun markFired() {
        fired = true
    }

    /** Когда проверить снова (мс от [now]); null - ждать нечего (не входили, уже сработало или TCP был). */
    @Synchronized fun nextCheckMs(now: Long): Long? {
        val first = firstJoinAt ?: return null
        if (fired || tcpProven) return null
        val byJoin = first + noTcpMs - now
        if (ipSends < minIpSends) return maxOf(byJoin, IDLE_RECHECK_MS)      // ждём IP_REQUEST от ГУ; ipSent перевзведёт
        val byIp = lastIpAt?.let { it + settleMs - now } ?: 0L
        return maxOf(byJoin, byIp, MIN_RECHECK_MS)
    }

    private companion object {
        const val MIN_RECHECK_MS = 1_000L
        const val IDLE_RECHECK_MS = 5_000L
    }
}
