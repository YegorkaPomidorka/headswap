package com.yegorkapomidorka.headswap

/**
 * Подгонка roadName (NAVI_NEXT_TURN_INFO, поле 3) под приборку Honda (ГУ CarLife 1.1.0, STATISTIC_INFO channel 2077xxxx/2083xxxx,
 * HU_INFO пустой). Формулы найдены тестами на машине:
 * - S = длина строки в байтах UTF-8 до первого NUL; ГУ считает эти байты за UTF-16, поэтому видны только
 *   первые floor(S/2)-1 символов. При S >= 32 текст пустой или мусор, при S <= 31 работает.
 * - Кириллица и прочее не-ASCII рисуется полноширинной (2 клетки), ASCII - 1 клетка. Если видимая часть шире
 *   ~24 клеток, приборка отбрасывает строку целиком (остаётся прежний текст); 24 проходит, 26 и больше - нет.
 * - Хвостовые пробелы работают как паддинг (NUL не годится): «Мира» + 2 пробела показывается как «Мира».
 *
 * Поэтому берём самый длинный префикс, у которого ширина <= [MAX_CELLS], символов <= [MAX_CHARS] и
 * max(байты, 2*символы+2) <= [MAX_BYTES], и добиваем пробелами до S = max(байты, 2*символы+2): тогда видимы все символы префикса.
 */
object HondaClusterRoad {
    /** Максимум клеток видимой части: шире приборка строку отбрасывает целиком. */
    const val MAX_CELLS = 24

    /** Максимум видимых символов: при S = 2c+2 и S <= 31 выходит c <= 14. */
    const val MAX_CHARS = 14

    /** Максимум S (байт строки до NUL): с 32 текст пустой или мусор. */
    const val MAX_BYTES = 31

    private const val ASCII_LIMIT = 0x80
    private const val WIDE_CELLS = 2
    private const val UTF16_OVERHEAD = 2        // видно floor(S/2)-1 символов, значит S >= 2c+2
    private const val DIRECTION_PREFIX = "В сторону "

    fun fit(s: String): String {
        var text = normalize(s)
        if (text.length > DIRECTION_PREFIX.length && text.regionMatches(0, DIRECTION_PREFIX, 0, DIRECTION_PREFIX.length, ignoreCase = true)) {
            text = text.substring(DIRECTION_PREFIX.length).trimStart()
        }
        if (text.isEmpty()) return ""

        var end = 0
        var cells = 0
        var chars = 0
        var bytes = 0
        while (end < text.length) {
            val cp = text.codePointAt(end)
            val nCells = cells + cellWidth(cp)
            val nChars = chars + 1
            val nBytes = bytes + utf8Len(cp)
            if (nCells > MAX_CELLS || nChars > MAX_CHARS || maxOf(nBytes, UTF16_OVERHEAD * nChars + UTF16_OVERHEAD) > MAX_BYTES) break
            cells = nCells
            chars = nChars
            bytes = nBytes
            end += Character.charCount(cp)
        }
        val head = text.substring(0, end).trimEnd(' ')
        if (head.isEmpty()) return ""
        val headChars = head.codePointCount(0, head.length)
        val headBytes = head.toByteArray(Charsets.UTF_8).size
        val padTo = maxOf(headBytes, UTF16_OVERHEAD * headChars + UTF16_OVERHEAD)
        return head + " ".repeat(padTo - headBytes)
    }

    /** Пробельные символы - в обычный пробел, управляющие и форматные (U+00AD и т.п.) - выбросить, пробелы схлопнуть, trim. */
    private fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            when {
                Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                Character.getType(cp) == Character.CONTROL.toInt() || Character.getType(cp) == Character.FORMAT.toInt() -> Unit
                else -> sb.appendCodePoint(cp)
            }
        }
        return sb.toString().trim()
    }

    private fun cellWidth(cp: Int) = if (cp < ASCII_LIMIT) 1 else WIDE_CELLS

    private fun utf8Len(cp: Int) = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }
}
