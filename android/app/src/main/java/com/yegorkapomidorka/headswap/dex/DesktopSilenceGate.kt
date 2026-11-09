package com.yegorkapomidorka.headswap.dex

/**
 * Захват звука рабочего стола идёт непрерывно, даже когда телефон молчит. Тишину на ГУ не шлём (h913):
 * первый MEDIA_DATA даёт MEDIA INIT + MODULE_STATUS MUSIC=1, и VW BT 3816 при подключении уходит с радио
 * на источник CarLife. Канал MEDIA открываем по первому слышимому буферу и закрываем (MEDIA PAUSE + MUSIC=0)
 * после [holdMs] тишины; паузы между треками короче и канал не роняют.
 */
internal class DesktopSilenceGate(private val holdMs: Long = HOLD_MS) {
    enum class Step { DROP, START, SEND, STOP }

    var active = false
        private set
    private var lastSoundMs = 0L

    fun step(audible: Boolean, nowMs: Long): Step {
        if (audible) {
            lastSoundMs = nowMs
            if (active) return Step.SEND
            active = true
            return Step.START
        }
        if (!active) return Step.DROP
        if (nowMs - lastSoundMs >= holdMs) {
            active = false
            return Step.STOP
        }
        return Step.SEND
    }

    companion object {
        const val HOLD_MS = 5_000L
        /** Порог слышимости, около -66 дБFS: нули и дизеринг тишиной считаем. */
        const val AUDIBLE_LEVEL = 16

        /** Есть ли в PCM 16 бит little-endian хоть один сэмпл громче [AUDIBLE_LEVEL]. */
        fun audible(pcm: ByteArray): Boolean {
            var i = 0
            while (i + 1 < pcm.size) {
                val s = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
                if (s > AUDIBLE_LEVEL || s < -AUDIBLE_LEVEL) return true
                i += 2
            }
            return false
        }
    }
}
