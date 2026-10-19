package com.yegorkapomidorka.headswap

/**
 * Прореживание частых строк лога (кадры видео, аудио-пакеты, ACK): по каждому ключу в лог идут первые [first]
 * событий и дальше каждое [every]-е. Строка на каждый кадр стоила String.format, запись в logcat и системный вызов
 * записи в файл под общей блокировкой AppLog на нити, через которую идёт видео. Счёт ведётся всегда, лог только по
 * разрешению. Чистая JVM-логика, потокобезопасна.
 */
class LogThrottle(private val first: Long = 3, private val every: Long = 200) {
    private val counts = HashMap<Int, Long>()

    /** Учесть событие ключа; возвращает его номер (с 1), если строку писать, иначе 0. */
    @Synchronized
    fun next(key: Int): Long {
        val n = (counts[key] ?: 0L) + 1
        counts[key] = n
        return if (n <= first || n % every == 0L) n else 0L
    }

    /** Сколько событий ключа учтено. */
    @Synchronized
    fun count(key: Int): Long = counts[key] ?: 0L

    @Synchronized
    fun reset() = counts.clear()
}
