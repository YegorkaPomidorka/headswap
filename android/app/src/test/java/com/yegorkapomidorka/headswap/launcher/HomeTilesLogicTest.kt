package com.yegorkapomidorka.headswap.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeTilesLogicTest {
    private val yt = FullscreenApp.YOUTUBE
    private val tg = "org.telegram.messenger"
    private val all: (String) -> Boolean = { true }
    private fun nav() = Layer.View(CardView.NAVIGATOR)
    private fun music() = Layer.View(CardView.MUSIC)
    private fun clock() = Layer.View(CardView.CLOCK)

    @Test fun oldYoutubePrefIsAppTile() {
        assertEquals(CardView.APP, CardView.restore("YOUTUBE"))
        assertEquals(CardView.CLOCK, CardView.restore("CLOCK"))
        assertEquals(CardView.NAVIGATOR, CardView.restore("junk"))
    }

    @Test fun defaultLayersCycleLikeBefore() {
        // четыре плашки: навигатор, YouTube, часы (музыка - своя плашка)
        var t = HomeTiles(CardView.NAVIGATOR, CardView.MUSIC)
        val seen = List(3) { t = HomeTilesLogic.nextBig(t, HomeLayout.QUAD, all); t.big }
        assertEquals(listOf(CardView.APP, CardView.CLOCK, CardView.NAVIGATOR), seen)
        // без YouTube - навигатор и часы
        assertEquals(CardView.CLOCK, HomeTilesLogic.nextBig(HomeTiles(CardView.NAVIGATOR, CardView.MUSIC), HomeLayout.QUAD, { it != yt }).big)
    }

    @Test fun anyAppIsALayer() {
        val layers = listOf(nav(), Layer.App(tg), clock())
        var t = HomeTiles(CardView.NAVIGATOR, CardView.MUSIC)
        t = HomeTilesLogic.nextBig(t, HomeLayout.QUAD, all, layers)
        assertEquals(HomeTiles(CardView.APP, CardView.MUSIC, tg), t)
        t = HomeTilesLogic.nextBig(t, HomeLayout.QUAD, all, layers)
        assertEquals(CardView.CLOCK, t.big)
    }

    @Test fun twoAppLayersSwitchTheApp() {
        val layers = listOf(Layer.App(yt), Layer.App(tg))
        val t = HomeTilesLogic.nextBig(HomeTiles(CardView.APP, CardView.MUSIC, yt), HomeLayout.QUAD, all, layers)
        assertEquals(HomeTiles(CardView.APP, CardView.MUSIC, tg), t)
    }

    @Test fun appLayersSkippedWhileSmallTileIsApp() {
        // окно приложения одно: в малой плашке приложение - в большую приложение не ставится
        val layers = listOf(nav(), Layer.App(tg), clock())
        val t = HomeTilesLogic.nextBig(HomeTiles(CardView.NAVIGATOR, CardView.APP, yt), HomeLayout.WIDE_LEFT, all, layers)
        assertEquals(HomeTiles(CardView.CLOCK, CardView.APP, yt), t)
    }

    @Test fun uninstalledAppsAreSkipped() {
        val layers = listOf(nav(), Layer.App(tg), clock())
        val t = HomeTilesLogic.nextBig(HomeTiles(CardView.NAVIGATOR, CardView.MUSIC), HomeLayout.QUAD, { it != tg }, layers)
        assertEquals(CardView.CLOCK, t.big)
    }

    @Test fun layersInTwoTilesSkipSmallTile() {
        val layers = listOf(nav(), music(), clock())
        var t = HomeTiles(CardView.CLOCK, CardView.NAVIGATOR)
        val seen = List(2) { t = HomeTilesLogic.nextBig(t, HomeLayout.WIDE_LEFT, all, layers); t.big }
        assertEquals(listOf(CardView.MUSIC, CardView.CLOCK), seen)
        // в списке только малая плашка - большая не меняется
        assertEquals(HomeTiles(CardView.NAVIGATOR, CardView.MUSIC),
            HomeTilesLogic.nextBig(HomeTiles(CardView.NAVIGATOR, CardView.MUSIC), HomeLayout.WIDE_LEFT, all, listOf(music())))
    }

    @Test fun swapAndNormalize() {
        val t = HomeTiles(CardView.NAVIGATOR, CardView.MUSIC)
        assertEquals(HomeTiles(CardView.MUSIC, CardView.NAVIGATOR), HomeTilesLogic.swap(t, HomeLayout.WIDE_RIGHT, appOk = true))
        assertEquals(t, HomeTilesLogic.swap(t, HomeLayout.QUAD, appOk = true))
        assertEquals(HomeTiles(CardView.NAVIGATOR, CardView.MUSIC),
            HomeTilesLogic.normalize(HomeTiles(CardView.MUSIC, CardView.NAVIGATOR), HomeLayout.QUAD, appOk = true))
        assertEquals(HomeTiles(CardView.CLOCK, CardView.MUSIC),
            HomeTilesLogic.normalize(HomeTiles(CardView.CLOCK, CardView.CLOCK), HomeLayout.WIDE_LEFT, appOk = true))
        // приложение удалили - плашки возвращаются к навигатору и музыке
        assertEquals(HomeTiles(CardView.NAVIGATOR, CardView.MUSIC),
            HomeTilesLogic.normalize(HomeTiles(CardView.APP, CardView.APP), HomeLayout.WIDE_LEFT, appOk = false))
    }

    @Test fun layerListEncodingAndLimits() {
        assertEquals(LayerList.DEFAULT, LayerList.decode(null))
        assertEquals(LayerList.DEFAULT, LayerList.decode("junk"))
        assertEquals(listOf(clock(), Layer.App(yt), Layer.App(tg)), LayerList.decode("CLOCK\nYOUTUBE\napp:$tg\nCLOCK"))
        val list = listOf(nav(), Layer.App(tg))
        assertEquals(list, LayerList.decode(LayerList.encode(list)))
        assertEquals(listOf(nav()), LayerList.remove(listOf(nav()), nav()))
        assertEquals(LayerList.DEFAULT, LayerList.add(LayerList.DEFAULT, Layer.App(tg)))
        assertEquals(emptyList<CardView>(), LayerList.freeBuiltins(LayerList.DEFAULT))
        assertEquals(listOf(CardView.MUSIC, CardView.CLOCK), LayerList.freeBuiltins(list))
    }

    @Test fun pickerHidesChosenAndNavigator() {
        val list = listOf(nav(), Layer.App(tg))
        assertEquals(listOf(yt), LayerList.pickableApps(listOf(tg, yt, "ru.yandex.yandexnavi", yt), list, "ru.yandex.yandexnavi"))
        assertEquals(emptyList<String>(), LayerList.pickableApps(listOf(yt), LayerList.DEFAULT, null))
    }
}
