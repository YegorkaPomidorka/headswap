package com.yegorkapomidorka.headswap.launcher

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.absoluteOffset
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.carapp.CarAppHost
import com.yegorkapomidorka.headswap.carapp.CarAreas
import com.yegorkapomidorka.headswap.carapp.Box as CarBox
import kotlin.math.roundToInt


/** Ключ «пакет/класс» для запуска пакета: первый вход из каталога лаунчерных приложений. */
private fun keyFor(pkg: String?): String? =
    pkg?.let { p -> LauncherModel.catalog.firstOrNull { it.packageName == p }?.key }

/**
 * Живые окна чужих приложений поверх содержимого. Лежат в корне, а не внутри экранов: TextureView должен пережить
 * уход на главный, иначе текстура умрёт под работающим приложением.
 */
@Composable
fun EmbedLayers() {
    val model = LauncherModel
    val area = model.area
    val areaRect = model.areaRect
    val cardRect = model.navCardRect
    val navKey = keyFor(model.navigatorPkg)

    // навигатор: размер карточки, на весь экран справа от рейки - в режиме «Навигатор»
    val navTarget = if (area == Area.Navigator) areaRect else cardRect
    LaunchedEffect(navKey, navTarget?.width, navTarget?.height, areaRect?.width, areaRect?.height) {
        EmbedHost.want(EmbedHost.nav, navKey, navTarget?.width?.roundToInt() ?: 0, navTarget?.height?.roundToInt() ?: 0,
            areaRect?.width?.roundToInt() ?: 0, areaRect?.height?.roundToInt() ?: 0)
    }
    // свой хост Car App: поверхность на весь экран, в карточке - её центр 1:1, туда же видимая область навигатора
    val carNav = EmbedHost.nav.carHost
    val navCrop = if (carNav && area != Area.Navigator && cardRect != null && areaRect != null)
        CarAreas.crop(areaRect.width.roundToInt(), areaRect.height.roundToInt(), cardRect.width.roundToInt(), cardRect.height.roundToInt())
    else null
    LaunchedEffect(carNav, navCrop) { if (carNav) CarAppHost.setCrop(navCrop) }
    // приложение: одно окно на все приложения, смена приложения = launch в тот же дисплей.
    // Плашка-приложение на главном (YouTube или любое из «Слоёв») занимает это же окно размером с плашку: открыть
    // другое приложение - вытеснить его, вернуться на главный - запустить снова (слотов всего два, второй - навигатор).
    val tiles = model.tiles
    val tileKey = model.tileAppKey
    val tileOnHome = area == Area.Home && tileKey != null && CardView.APP in listOf(tiles.big, tiles.small)
    // Плашка запомнила приложение как текущее: «Слои» увели его с плашки - окно остаётся за ним
    // (скрыто, того же размера) и при возврате продолжает, а не запускается заново (Егор 05.11)
    LaunchedEffect(tileOnHome, tileKey) { if (tileOnHome) model.appKey = tileKey }
    val appKey = if (tileOnHome) tileKey else model.appKey
    val appTarget = if (area == Area.Home && appKey != null && appKey == tileKey) model.appCardRect ?: areaRect else areaRect
    LaunchedEffect(appKey, appTarget?.width, appTarget?.height) {
        EmbedHost.want(EmbedHost.app, appKey, appTarget?.width?.roundToInt() ?: 0, appTarget?.height?.roundToInt() ?: 0)
    }
    LaunchedEffect(area) {
        when (area) {
            Area.Navigator -> EmbedHost.refresh(EmbedHost.nav)
            is Area.App -> EmbedHost.refresh(EmbedHost.app)
            else -> Unit
        }
    }

    val navShown = navKey != null &&
        (area == Area.Navigator || (area == Area.Home && CardView.NAVIGATOR in listOf(tiles.big, tiles.small)))
    // подпись окна навигатора в углу карточки (макет v2); на весь экран не нужна
    val navPkg = model.navigatorPkg
    val navTag = if (area != Area.Navigator && navPkg != null) remember(navPkg) { LauncherModel.packageLabel(navPkg) } else null
    EmbedLayer(EmbedHost.nav, navTarget, shown = navShown, interactive = area == Area.Navigator,
        rounded = area != Area.Navigator, background = LocalPalette.current.mapBg,
        starting = stringResource(R.string.l_nav_starting), onCardTap = { LauncherActions.openNavigator() }, tag = navTag,
        crop = navCrop, buffer = if (carNav && areaRect != null) areaRect.width.roundToInt() to areaRect.height.roundToInt() else null)
    if (appKey != null) {
        if (tileOnHome) {
            // плашка-приложение: касания идут в приложение - окно размером с плашку, и полноэкранный режим самого YouTube
            // растягивает видео ровно на плашку. На весь экран - кнопкой «Развернуть» в углу (HomeTileButtons).
            // Некликабельная плашка не годилась: YouTube в окне показывает ленту и сворачивает видео в мини-плеер (Егор 05.11)
            val tileLabel = remember(model.tileApp) { model.packageLabel(model.tileApp) }
            EmbedLayer(EmbedHost.app, appTarget, shown = true, interactive = true, rounded = true, background = LocalPalette.current.mapBg,
                starting = stringResource(R.string.l_starting, tileLabel), onCardTap = null)
        } else {
            val label = model.starting ?: ""
            EmbedLayer(EmbedHost.app, areaRect, shown = area is Area.App, interactive = true, rounded = false, background = LocalPalette.current.bgSolid,
                starting = stringResource(R.string.l_starting, label), onCardTap = null)
        }
    }

    // панель Алисы: у рейки (слева; в RTL справа), на всю высоту, шириной 0,8 высоты; с другой стороны затемнение, тап по нему закрывает
    val assistOpen = model.assistantOpen
    // Gemini окна не занимает: открывается клавишей помощника на экране телефона (LauncherActions.voiceOnly)
    val aliceKey = remember(assistOpen) { if (assistOpen && !LauncherActions.gemini()) LauncherActions.aliceKey() else null }
    val rtl = localeRtl()
    val aliceRect = areaRect?.let {
        val w = (it.height * 0.8f).roundToInt()
        if (rtl) Rect(it.right - w, it.top, it.right, it.bottom) else Rect(it.left, it.top, it.left + w, it.bottom)
    }
    LaunchedEffect(aliceKey, aliceRect?.width, aliceRect?.height) {
        if (aliceKey != null && aliceRect != null) {
            val w = aliceRect.width.roundToInt()
            val h = aliceRect.height.roundToInt()
            AppLog.i("ASSIST панель ${w}x$h, окно $aliceKey", false)
            EmbedHost.want(EmbedHost.alice, aliceKey, w, h)
        } else EmbedHost.want(EmbedHost.alice, null, 0, 0)
    }
    val aliceHidden = model.assistantHidden
    if (assistOpen && !aliceHidden && aliceRect != null) {
        // слой лежит в Ltr-корне: padding(start) и width - от левого края экрана
        val scrim = if (rtl) {
            val widthDp = with(LocalDensity.current) { aliceRect.left.roundToInt().toDp() }
            Modifier.fillMaxHeight().width(widthDp)
        } else {
            val startDp = with(LocalDensity.current) { aliceRect.right.roundToInt().toDp() }
            Modifier.fillMaxSize().padding(start = startDp)
        }
        Pressable(scrim.background(Color(0xAA000000)),
            onTap = { LauncherActions.closeAssistant("тап по затемнению") }) {}
    }
    // слой живёт и после закрытия (как у приложения): TextureView должен пережить панель
    var aliceUsed by remember { mutableStateOf(false) }
    if (assistOpen) aliceUsed = true
    if (aliceUsed) {
        EmbedLayer(EmbedHost.alice, aliceRect, shown = assistOpen && !aliceHidden, interactive = true, rounded = false,
            background = LocalPalette.current.bgSolid, starting = stringResource(R.string.l_alice_starting), onCardTap = null,
            hidden = assistOpen && aliceHidden)
    }
}

private val OFFSCREEN = IntOffset(-100_000, -100_000)

@Composable
internal fun EmbedLayer(
    slot: EmbedSlot, target: Rect?, shown: Boolean, interactive: Boolean, rounded: Boolean, background: Color,
    starting: String, onCardTap: (() -> Unit)?, tag: String? = null,
    /** Буфер текстуры [buffer] (px) больше окна: показать его часть [crop] 1:1; crop null - буфер на всё окно. */
    crop: CarBox? = null, buffer: Pair<Int, Int>? = null,
    /**
     * Дисплей живёт без показа (скрытая Алиса): окно 1x1 px в углу. За экраном TextureView не рисуется и не отдаёт
     * SurfaceTexture, дочерний дисплей не создаётся (стенд 07.11: слот завис в CONNECTING).
     */
    hidden: Boolean = false,
) {
    var last by remember(slot) { mutableStateOf<Rect?>(null) }
    if (target != null) last = target
    val rect = last ?: return
    val m = LocalMetrics.current
    val density = LocalDensity.current
    SideEffect { slot.interactive = shown && interactive }
    val status = slot.status
    if (shown && status == EmbedStatus.READY && slot === EmbedHost.app) {
        LaunchedEffect(Unit) { LauncherModel.launchLock.finish(); LauncherModel.starting = null }
    }
    val shape = RoundedCornerShape(if (rounded) m.dp(m.spec.radius) else m.dp(0))
    val w = with(density) { rect.width.roundToInt().toDp() }
    val h = with(density) { rect.height.roundToInt().toDp() }
    // Скрытый слой уводим за экран: AndroidView в Compose забирает касания в своих границах даже с INVISIBLE,
    // и спрятанная карточка навигатора глушила стрелки «Все приложения». Сам TextureView при этом живёт.
    val onePx = with(density) { 1.toDp() }
    Box(
        Modifier.absoluteOffset { when { shown -> IntOffset(rect.left.roundToInt(), rect.top.roundToInt()); hidden -> IntOffset.Zero; else -> OFFSCREEN } }
            .size(if (hidden && !shown) onePx else w, if (hidden && !shown) onePx else h).clip(shape),
    ) {
        // textureGen: кнопка перезапуска просит новый TextureView (свежая текстура без старого продюсера)
        androidx.compose.runtime.key(slot.textureGen) { AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    isOpaque = true
                    EmbedHost.adoptable(slot)?.let { runCatching { setSurfaceTexture(it) } }
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = EmbedHost.textureAvailable(slot, st)
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                        // текстура принадлежит слоту: под ней работает продюсер дочернего дисплея
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = synchronized(slot.lock) { slot.texture !== st }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                    setOnTouchListener { v, ev ->
                        if (!slot.interactive) false else { EmbedHost.touch(slot, ev, v.width, v.height); true }
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { v ->
                v.visibility = if (shown || hidden) android.view.View.VISIBLE else android.view.View.INVISIBLE
                // TextureView растягивает буфер на окно; матрица возвращает масштаб 1:1 и сдвигает на вырезку
                val b = buffer
                v.setTransform(if (crop == null || b == null) null else android.graphics.Matrix().apply {
                    setScale(b.first / crop.width.toFloat(), b.second / crop.height.toFloat())
                    postTranslate(-crop.left.toFloat(), -crop.top.toFloat())
                })
            },
        ) }
        if (shown) {
            if (status != EmbedStatus.READY) {
                Box(Modifier.fillMaxSize().background(background), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (status == EmbedStatus.FAILED) {
                            Pressable(Modifier.fillMaxSize(), onTap = { EmbedHost.retry(slot) }) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Txt(stringResource(R.string.l_nav_failed), m.spec.f2, color = Pal.SEC2, maxLines = 3, align = TextAlign.Center)
                                }
                            }
                        } else Txt(starting, if (rounded) m.spec.fs else m.spec.f1, color = Pal.SEC2, weight = FontWeight.Medium,
                            maxLines = 2, align = TextAlign.Center)
                    }
                }
            } else if (onCardTap != null && !interactive) {
                // карточка навигатора: тап разворачивает окно, касания в приложение не идут
                Pressable(Modifier.fillMaxSize(), onTap = onCardTap) {}
            }
            if (tag != null && status == EmbedStatus.READY) {
                // подпись без обработки касаний: тап проходит к карточке под ней
                Box(
                    Modifier.align(Alignment.BottomStart).padding(start = m.dp(m.spec.g8), bottom = m.dp(m.spec.g8))
                        .clip(RoundedCornerShape(m.dp(m.spec.radius))).background(LocalPalette.current.tagBg)
                        .padding(horizontal = m.dp(m.spec.g8), vertical = m.dp(m.spec.b4)),
                ) { Txt(tag, m.spec.tag, color = Pal.SEC2) }
            }
        }
    }
}

/** Плашка главного по её содержимому; [small] - малая плашка (часы мельче). */
@Composable
fun TileCard(view: CardView, modifier: Modifier, small: Boolean = false) {
    when (view) {
        CardView.NAVIGATOR -> NavCard(modifier)
        CardView.APP -> AppTileCard(modifier)
        CardView.MUSIC -> MusicCard(modifier)
        CardView.CLOCK -> CardBox(modifier, background = LocalPalette.current.mapBg) { ClockFace(small) }
    }
}

/** Плашка-приложение: окно рисует слой app по [LauncherModel.appCardRect]; здесь фон и «не установлено». */
@Composable
private fun AppTileCard(modifier: Modifier) {
    val model = LauncherModel
    val s = LocalMetrics.current.spec
    CardBox(modifier.onGloballyPositioned { model.appCardRect = it.boundsInRoot() }, background = LocalPalette.current.mapBg) {
        if (model.catalogLoaded && model.tileAppKey == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Txt(stringResource(R.string.l_app_not_installed), s.f1, color = Pal.SEC2, weight = FontWeight.Medium, align = TextAlign.Center)
        }
    }
}

/** Карточка навигатора: живое окно навигатора (рисует слой по [LauncherModel.navCardRect]) или выбор навигатора. */
@Composable
fun NavCard(modifier: Modifier) {
    val model = LauncherModel
    val m = LocalMetrics.current
    val s = m.spec
    CardBox(modifier.onGloballyPositioned { model.navCardRect = it.boundsInRoot() }, background = LocalPalette.current.mapBg) {
        when {
            model.navigatorPkg == null -> Pressable(Modifier.fillMaxSize(), onTap = { LauncherActions.openNavigatorChoice() }) { pressed ->
                if (pressed) Box(Modifier.matchParentSize().background(Pal.SURFACE_PRESSED))
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        SvgIcon(Ico.NAV, s.icBig, Pal.SEC, fill = true)
                        val text = if (!model.catalogLoaded || model.installedNavigators.isNotEmpty())
                            stringResource(R.string.l_nav_choose) else stringResource(R.string.l_nav_not_installed)
                        Txt(text, s.f1, color = Pal.SEC2, weight = FontWeight.Medium, align = TextAlign.Center)
                    }
                }
            }
            else -> Unit
        }
    }
}

/**
 * Кнопки главного поверх живых окон (слои встраивания лежат над экраном и забирают касания в своих границах):
 * ⇄ на стыке двух плашек и стеклянные «Слои» в углу большой плашки в ленте 1280x480 (в её рейке места нет).
 */
@Composable
fun HomeTileButtons() {
    val model = LauncherModel
    if (model.area != Area.Home) return
    val m = LocalMetrics.current
    val s = m.spec
    val big = model.bigTileRect
    val small = model.smallTileRect
    if (model.homeLayout.two && big != null && small != null) {
        val size = Math.round(s.t * 0.8f)
        val cx = Math.round(if (big.left < small.left) (big.right + small.left) / 2 else (small.right + big.left) / 2)
        val cy = Math.round(big.center.y)
        Pressable(
            Modifier.absoluteOffset { IntOffset(cx - size / 2, cy - size / 2) }.size(m.dp(size)).clip(CircleShape)
                .background(GlassBg).border(m.dp(1), GlassBorder, CircleShape),
            description = stringResource(R.string.l_swap_tiles), onTap = { LauncherActions.swapTiles() }, focusShape = CircleShape,
        ) { pressed ->
            if (pressed) Box(Modifier.matchParentSize().background(Pal.SURFACE_PRESSED))
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                SvgIcon(Ico.SWAP, Math.round(size * 0.5f), Pal.WHITE.copy(alpha = 0.8f), stroke = 2.4f)
            }
        }
    }
    // «Развернуть» плашку-приложение на весь экран: касания плашки уходят в приложение, поэтому отдельная кнопка в углу
    val tiles = model.tiles
    val yt = model.appCardRect
    if (yt != null && model.tileAppKey != null && CardView.APP in listOf(tiles.big, tiles.small)) {
        Pressable(
            Modifier.absoluteOffset { IntOffset(Math.round(yt.left) + s.g8, Math.round(yt.bottom) - s.pcB - s.g8) }.size(m.dp(s.pcB))
                .clip(CircleShape).background(GlassBg).border(m.dp(1), GlassBorder, CircleShape),
            description = stringResource(R.string.l_expand), onTap = { LauncherActions.openTileApp() }, focusShape = CircleShape,
        ) { pressed ->
            if (pressed) Box(Modifier.matchParentSize().background(Pal.SURFACE_PRESSED))
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                SvgIcon(Ico.EXPAND, s.pcIc, Pal.WHITE.copy(alpha = 0.8f), stroke = 2.4f)
            }
        }
    }
    if (!s.cardViewInRail && big != null) {
        Box(Modifier.absoluteOffset { IntOffset(Math.round(big.right) - s.pcB - s.g8, Math.round(big.top) + s.g8) }) {
            CardViewButton(s.pcB, s.pcIc, GlassBg, Pal.WHITE.copy(alpha = 0.75f), Modifier.border(m.dp(1), GlassBorder, CircleShape))
        }
    }
}

/** Вид «Часы»: крупные часы и дата; в малой плашке мельче, чтобы время влезло по ширине. */
@Composable
private fun ClockFace(small: Boolean) {
    val m = LocalMetrics.current
    val s = m.spec
    val st = LauncherModel.status
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Txt(st.time, if (small) s.t else s.t * 2, weight = FontWeight.Bold, mono = true)
            Txt(st.date, if (small) s.f2 else s.f1, color = Pal.SEC2)
        }
    }
}

/** Область под встроенным приложением: слой рисуется поверх, здесь только фон. */
@Composable
fun EmbedAreaBackdrop() {
    Box(Modifier.fillMaxSize().background(LocalPalette.current.bgSolid))
}
