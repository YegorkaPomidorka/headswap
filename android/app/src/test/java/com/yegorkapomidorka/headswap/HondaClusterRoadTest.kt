package com.yegorkapomidorka.headswap

import com.yegorkapomidorka.headswap.aa.AaBridgeCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HondaClusterRoadTest {

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size

    @Test
    fun hondaChannelsByPrefix() {
        for (ch in listOf("20772115", "20772121", "20772124", "20772126", "20832109", "20832120", "20832125")) {
            assertTrue(ch, AaBridgeCore.isHondaChannel(ch))
        }
        for (ch in listOf("20622116", "20082122", "21520001", "2077", "207721150", "", null)) {
            assertTrue("$ch", !AaBridgeCore.isHondaChannel(ch))
        }
    }

    @Test
    fun longCyrillicCutByCells() {
        val r = HondaClusterRoad.fit("ул. Октябрьская")
        assertEquals("ул. Октябрьск" + " ".repeat(4), r)
        assertEquals(28, bytes(r))
    }

    @Test
    fun shortStreetPadded() {
        val r = HondaClusterRoad.fit("Мира")
        assertEquals("Мира  ", r)
        assertEquals(10, bytes(r))
    }

    @Test
    fun softHyphenDroppedAndCut() {
        assertEquals("Направляйтес  ", HondaClusterRoad.fit("Направляй­тесь на север"))
    }

    @Test
    fun directionPrefixStripped() {
        val r = HondaClusterRoad.fit("В сторону ул. Генерала Павлова")
        assertTrue(r, r.startsWith("ул. Генерала"))
        assertTrue(bytes(r) <= HondaClusterRoad.MAX_BYTES)
        assertEquals("мира  ".length, HondaClusterRoad.fit("в сторону мира").length)
        assertEquals("В сторону", HondaClusterRoad.fit("В сторону").trim())
    }

    @Test
    fun asciiPadAndCharLimit() {
        assertEquals("H123456789" + " ".repeat(12), HondaClusterRoad.fit("H123456789"))
        val r = HondaClusterRoad.fit("A".repeat(20))
        assertEquals("A".repeat(14) + " ".repeat(16), r)
        assertEquals(30, bytes(r))
    }

    @Test
    fun cjkNoPadding() {
        assertEquals("人民路", HondaClusterRoad.fit("人民路"))
    }

    @Test
    fun emptyStaysEmpty() {
        assertEquals("", HondaClusterRoad.fit(""))
        assertEquals("", HondaClusterRoad.fit(" ­\n "))
    }

    @Test
    fun trailingSpaceOfPrefixTrimmed() {
        // префикс из 14 символов кончается на пробеле: пробел отрезается, S пересчитывается по 13 символам
        val r = HondaClusterRoad.fit("abcdefghijklm nop")
        assertEquals("abcdefghijklm" + " ".repeat(15), r)
        assertEquals(28, bytes(r))
        assertEquals("Спасо-Тушинс" + "   ", HondaClusterRoad.fit("Спасо-Тушинский бул."))
    }

    @Test
    fun invariantOnSamples() {
        val samples = listOf(
            "А191 ул. Тургенева", "ул. Дзержинского", "Выезжайте с круга", "Спасо-Тушинский бул.",
            "В сторону ул. Софьи Ковалевской", "ул. Октябрьская", "Мира", "目的地", "人民路", "H123456789", "A".repeat(40),
            "😀😀😀😀😀😀😀😀😀😀😀😀😀",
            "Направляй­тесь на север", "Ленинградское шоссе", "  много   пробелов  между   словами ", "a\tb\nc",
            "Проспект имени газеты Пионерская правда", "Ленина 😀 улица",
        )
        for (s in samples) {
            val r = HondaClusterRoad.fit(s)
            val total = bytes(r)
            assertTrue("S<=31: \"$s\" -> \"$r\" ($total)", total <= HondaClusterRoad.MAX_BYTES)
            val visible = r.trimEnd(' ')
            val cps = visible.codePointCount(0, visible.length)
            assertTrue("видно floor(S/2)-1 >= $cps: \"$s\" -> \"$r\"", total / 2 - 1 >= cps)
            val cells = visible.codePoints().map { if (it < 0x80) 1 else 2 }.sum()
            assertTrue("ширина $cells: \"$s\" -> \"$r\"", cells <= HondaClusterRoad.MAX_CELLS)
            val norm = s.replace("­", "").replace(Regex("\\s+"), " ").trim()
            val src = if (norm.length > 10 && norm.startsWith("В сторону ")) norm.substring(10) else norm
            assertTrue("префикс: \"$s\" -> \"$r\"", src.startsWith(visible))
            assertTrue("без хвостового пробела в видимой части", visible.isEmpty() || !visible.endsWith(" "))
        }
    }
}
