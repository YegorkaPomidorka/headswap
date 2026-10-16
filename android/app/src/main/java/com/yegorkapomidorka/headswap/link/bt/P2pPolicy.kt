package com.yegorkapomidorka.headswap.link.bt

/** Что делать с очередным TARGET_INFO от ГУ. */
enum class P2pOnTarget { START, RESTART, SKIP }

/** Чистая логика входа в P2P-группу ГУ, без android.*: проверяется JVM-тестами. */
object P2pPolicy {
    /** Повторов discoverPeers после BUSY и пауза между ними (укладываются в JOIN_TIMEOUT_MS). */
    const val BUSY_RETRIES = 3
    const val BUSY_RETRY_MS = 2_000L

    /**
     * Новый TARGET_INFO (ГУ шлёт его на каждое подключение Bluetooth): в группе уже - пропускаем, IP переотдаётся по
     * IP_REQUEST; попытка идёт, но не вошли (BUSY, пир не нашёлся, Wi-Fi включили позже) - начинаем заново.
     */
    fun onTarget(active: Boolean, joined: Boolean): P2pOnTarget = when {
        joined -> P2pOnTarget.SKIP
        active -> P2pOnTarget.RESTART
        else -> P2pOnTarget.START
    }

    /** Повторять ли discoverPeers: только BUSY (P2P занят или ещё поднимается), не больше [BUSY_RETRIES] раз. */
    fun retryDiscover(busy: Boolean, retriesDone: Int): Boolean = busy && retriesDone < BUSY_RETRIES
}
