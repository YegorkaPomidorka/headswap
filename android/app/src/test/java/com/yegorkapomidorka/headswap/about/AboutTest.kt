package com.yegorkapomidorka.headswap.about

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutTest {
    @Test fun everyLicenseHasItsTextInAssets() {
        for (lic in About.licenses) {
            val f = File("src/main/assets/licenses/${lic.asset}")
            assertTrue("нет ${lic.asset} для ${lic.name}", f.isFile && f.length() > 500)
            assertTrue("адрес проекта ${lic.name}", lic.url.startsWith("https://"))
        }
    }

    @Test fun reflowJoinsParagraphLinesButKeepsListItemsAndBlankLines() {
        val raw = "First line of a\nparagraph.\n\n   1. Item one\n      continues here.\n   2. Item two\n-----\nTitle\n"
        assertEquals("First line of a paragraph.\n\n1. Item one continues here.\n2. Item two\n———\nTitle", About.reflow(raw))
    }

    @Test fun reflowJoinsCenteredHeading() {
        assertEquals("GNU GENERAL PUBLIC LICENSE Version 3, 29 June 2007",
            About.reflow("                    GNU GENERAL PUBLIC LICENSE\n                       Version 3, 29 June 2007\n"))
    }

    @Test fun titleShowsVersionOnlyWhenKnown() {
        assertEquals("Kadb 2.1.5", About.licenses.first { it.name == "Kadb" }.title)
        assertEquals("Golos Text", About.licenses.first { it.name == "Golos Text" }.title)
    }
}
