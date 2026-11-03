package com.yegorkapomidorka.headswap.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import com.yegorkapomidorka.headswap.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** px экрана ГУ -> dp/sp в одном месте: остальной код живёт в px из [LauncherSpec]. */
class LauncherMetrics(val spec: LauncherSpec, private val density: Density) {
    fun dp(px: Int): Dp = with(density) { px.toDp() }
    fun sp(px: Int): TextUnit = with(density) { px.toSp() }
}

val LocalMetrics = staticCompositionLocalOf<LauncherMetrics> { error("LauncherMetrics not provided") }
val LocalAccent = staticCompositionLocalOf { Pal.GOLD }

/** Шрифты макета: Golos Text и JetBrains Mono (OFL, вариативные, уже лежат в res/font для интерфейса телефона). */
object LauncherFonts {
    private val cache = java.util.concurrent.ConcurrentHashMap<Int, FontFamily>()

    /**
     * Семейство под один вес: Compose у вариативного шрифта из ресурсов не выставляет wght (всё рисовалось 400),
     * поэтому берём font-family XML (golos.xml / jetbrains_mono.xml с fontVariationSettings) и выбираем вес системой.
     */
    fun family(ctx: android.content.Context, mono: Boolean, weight: FontWeight): FontFamily {
        val key = (if (mono) 10_000 else 0) + weight.weight
        return cache.getOrPut(key) {
            runCatching {
                val base = ctx.resources.getFont(if (mono) R.font.jetbrains_mono else R.font.golos)
                FontFamily(androidx.compose.ui.text.font.Typeface(android.graphics.Typeface.create(base, weight.weight, false)))
            }.getOrElse { if (mono) FontFamily.Monospace else FontFamily.SansSerif }
        }
    }
}

/** Палитра темы лаунчера: THEMES из Teyes.dc.html и Settings.dc.html. Обложка музыки и чип плееров тёмные в обеих. */
class LauncherPalette(
    val light: Boolean,
    /** Фон 118deg: стопы градиента и альфа левого и правого золотого свечения. */
    val bgStops: List<Pair<Float, Color>>, val glowLeft: Float, val glowRight: Float,
    val fg: Color, val fg2: Color, val fg3: Color,
    val surf: Color, val btn2: Color, val pressed: Color,
    /** Кнопки вариантов и трек выключенного переключателя в настройках (в тёмной на .02 тише кнопок главного). */
    val btnSet: Color,
    /** Часы, лого и активный «Назад» на рейке: в светлой приглушены, чёрный выбивается. */
    val railFg: Color,
    val missed: Color, val backOff: Color, val tagBg: Color, val mapBg: Color,
    /** Сплошной фон под двойным кольцом выбранной иконки и под окнами поверх экрана. */
    val bgSolid: Color,
    /** Подложка миниатюр «Карта на главном». */
    val mini: Color,
)

object Palettes {
    val DARK = LauncherPalette(
        light = false,
        bgStops = listOf(0f to Color(0xFF0A0F14), 0.42f to Color(0xFF111A21), 0.70f to Color(0xFF1A232B), 1f to Color(0xFF121920)),
        glowLeft = 0.22f, glowRight = 0.12f,
        fg = Color(0xFFF2F5F7), fg2 = Color(0xFFC8D1F0), fg3 = Color(0xFFA9B4D6),
        surf = Color(1f, 1f, 1f, 0.07f), btn2 = Color(1f, 1f, 1f, 0.12f), pressed = Color(1f, 1f, 1f, 0.20f),
        btnSet = Color(1f, 1f, 1f, 0.10f), railFg = Color(0xFFF2F5F7),
        missed = Color(0xFFFFB4A9), backOff = Color(0xFF5F6A80), tagBg = Color(14, 20, 26, 153), mapBg = Color(0xFF172026),
        bgSolid = Color(0xFF1A232B), mini = Color(0xFF0E151B),
    )
    private val BROWN = Color(90, 70, 20)
    val LIGHT = LauncherPalette(
        light = true,
        bgStops = listOf(0f to Color(0xFFFBF7EC), 0.5f to Color(0xFFF4EDDB), 1f to Color(0xFFEDE3CB)),
        glowLeft = 0.30f, glowRight = 0.18f,
        // основной текст #463D2E и на главном, и в настройках (решение Егора 03.11)
        fg = Color(0xFF463D2E), fg2 = Color(0xFF5C5340), fg3 = Color(0xFF6E6450),
        surf = BROWN.copy(alpha = 0.08f), btn2 = BROWN.copy(alpha = 0.12f), pressed = BROWN.copy(alpha = 0.20f),
        btnSet = BROWN.copy(alpha = 0.12f), railFg = Color(0xFF5C5340),
        missed = Color(0xFFB3361F), backOff = Color(0xFFA89F88), tagBg = Color(1f, 1f, 1f, 0.88f), mapBg = Color(0xFFEEE9DC),
        bgSolid = Color(0xFFF4EDDB), mini = Color(0xFFFBF7EC),
    )
}

val LocalPalette = staticCompositionLocalOf { Palettes.DARK }

object Pal {
    val GOLD = Color(0xFFD4AF37)
    val ON_GOLD = Color(0xFF201A05)
    val GREEN = Color(0xFF22C55E)
    val ON_GREEN = Color(0xFF06230F)
    val RED = Color(0xFFE03A3A)
    /** Белый на обложке, на зелёной и красной кнопках - в обеих темах. */
    val WHITE = Color(0xFFFFFFFF)

    // цвета темы: из текущей палитры
    val TEXT: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.fg
    val SEC: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.fg3
    val SEC2: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.fg2
    val SURFACE: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surf
    val SURFACE_HI: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.btn2
    val SURFACE_PRESSED: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.pressed
    val MISSED: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.missed
    val RAIL: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.railFg

    /** Тёмный или светлый текст на акценте: порог яркости как в макете. */
    fun onAccent(c: Color): Color {
        val lum = 0.299 * c.red * 255 + 0.587 * c.green * 255 + 0.114 * c.blue * 255
        return if (lum > 140) ON_GOLD else WHITE
    }
}

/** Фон под углом 118deg и золотое свечение слева и справа по палитре темы. Статичный. */
fun Modifier.launcherBackground(pal: LauncherPalette): Modifier = drawBehind {
    val w = size.width
    val h = size.height
    val rad = Math.toRadians(118.0)
    val dx = sin(rad).toFloat()
    val dy = (-cos(rad)).toFloat()
    val len = abs(w * dx) + abs(h * dy)
    val c = Offset(w / 2, h / 2)
    drawRect(Brush.linearGradient(*pal.bgStops.toTypedArray(),
        start = Offset(c.x - dx * len / 2, c.y - dy * len / 2), end = Offset(c.x + dx * len / 2, c.y + dy * len / 2)))
    fun glowAt(cx: Float, cy: Float, rx: Float, ry: Float, color: Color) {
        withTransform({
            translate(cx, cy)
            scale(1f, ry / rx, pivot = Offset.Zero)
        }) {
            drawCircle(Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center = Offset.Zero, radius = rx),
                radius = rx, center = Offset.Zero)
        }
    }
    // два приглушённых свечения по бокам: ellipse 26% 70% at 0% 55% и 22% 70% at 100% 55%
    val gold = Color(0xFFD4AF37)
    glowAt(0f, h * 0.55f, w * 0.26f, h * 0.70f, gold.copy(alpha = pal.glowLeft))
    glowAt(w, h * 0.55f, w * 0.22f, h * 0.70f, gold.copy(alpha = pal.glowRight))
}

/** Иконки из макета (viewBox 24x24); пути в SVG-синтаксисе, рисуем без библиотеки иконок. */
object Ico {
    private fun circle(cx: Int, cy: Int, r: Int) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0 $r $r 0 1 0 ${-2 * r} 0"
    val GEAR = listOf(circle(12, 12, 3), "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z")
    val LAYERS = listOf("M12 3 3 8l9 5 9-5z", "m3 13 9 5 9-5")
    val NAV = listOf("m3 11 19-9-9 19-2-8z")
    val PREV = listOf("M6 5h3v14H6z", "M20 5 9 12l11 7z")
    val NEXT = listOf("M15 5h3v14h-3z", "M4 5l11 7-11 7z")
    val PAUSE = listOf("M5 4h5v16H5z", "M14 4h5v16h-5z")
    val PLAY = listOf("M8 5v14l11-7z")
    val HEART = listOf("M12 21s-7.5-4.6-9.5-9A5.5 5.5 0 0 1 12 6a5.5 5.5 0 0 1 9.5 6c-2 4.4-9.5 9-9.5 9z")
    val BT = listOf("m7 7 10 10-5 5V2l5 5L7 17")
    val WIFI = listOf("M2 8.5a16 16 0 0 1 20 0", "M5 12.5a11 11 0 0 1 14 0", "M8.5 16.5a6 6 0 0 1 7 0", circle(12, 20, 1))
    val VOLUME = listOf("M11 5 6 9H2v6h4l5 4z", "M15.5 8.5a5 5 0 0 1 0 7")
    val PHONE = listOf("M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2")
    val CONTACT = listOf(circle(12, 8, 4), "M4 21a8 8 0 0 1 16 0")
    /** Входящий в компактной карточке «Телефон»: трубка с волнами. */
    val PHONE_RING = PHONE + listOf("M15 3a6 6 0 0 1 6 6", "M15 7a2 2 0 0 1 2 2")
    /** «Открыть запрос на телефоне»: силуэт телефона. */
    val DEVICE = listOf(roundRectPath(6.5f, 2.5f, 11f, 19f, 2.5f), "M11 18.5h2")
    val MIC_OFF = listOf("M9 9v2a3 3 0 0 0 5 2.2", "M15 9.3V5a3 3 0 0 0-5.7-1.3", "M19 11a7 7 0 0 1-1.2 3.9",
        "M5 11a7 7 0 0 0 11.6 5.3", "M12 18v3", "m3 3 18 18")
    val MIC = listOf("M12 3a3 3 0 0 0-3 3v5a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3z", "M19 11a7 7 0 0 1-14 0", "M12 18v3")
    val PLUS = listOf("M12 5v14", "M5 12h14")
    val CHEV_L = listOf("M15 5 8 12l7 7")
    /** Шеврон «Назад» на рейке (v2): шире и ниже обычной стрелки страниц. */
    val CHEV_BACK = listOf("M15 6 7 12l8 6")
    val CHEV_R = listOf("m9 5 7 7-7 7")
    val CLOSE = listOf("M6 6l12 12", "M18 6 6 18")
    val MUSIC = listOf("M9 18V5l12-2v13", circle(6, 18, 3), circle(18, 16, 3))
    val HOME = listOf("m3 11 9-8 9 8", "M5 10v10h14V10")
    val CHECK = listOf("m5 12 5 5 9-10")
    /** Разделы настроек: «Главный экран» (раскладка) и «Система» (экран ГУ). */
    val LAYOUT = listOf(roundRectPath(3f, 4f, 18f, 16f, 2f), "M8 4v16", "M8 14h13")
    val SCREEN = listOf(roundRectPath(2f, 5f, 20f, 13f, 2f), "M8 21h8")
    /** Панель навигации чужого лаунчера, как три кнопки Android: контурные треугольник, круг и квадрат со скруглением. */
    val NAV_BACK = listOf("M17 4 6 12l11 8z")
    val NAV_HOME = listOf(circle(12, 12, 8))
    val NAV_RECENTS = listOf(roundRectPath(5f, 5f, 14f, 14f, 3f))
    /** «Последний» во втором слоте плеера. */
    val REPEAT = listOf("M20 11a8 8 0 0 0-14.5-4.5L4 8", "M4 3v5h5", "M4 13a8 8 0 0 0 14.5 4.5L20 16", "M20 21v-5h-5")
}

/** Прямоугольник со скруглением в SVG-синтаксисе (rect rx из макета рисуется как path). */
fun roundRectPath(x: Float, y: Float, w: Float, h: Float, r: Float): String =
    "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r $r v${h - 2 * r}a$r $r 0 0 1 ${-r} $r h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r} v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z"

/** Путь иконки со своим стилем: заливка, обводка (толщина в единицах viewBox) и прозрачность. */
class VPath(val d: String, val fill: Boolean = false, val strokeW: Float? = null, val alpha: Float = 1f)

/** Иконки с разными стилями путей и своим viewBox: логотип Headswap, столбики сигнала, значок YouTube Music. */
object VIco {
    /** Логотип Headswap из Calls.dc.html / Teyes.dc.html: две дуговые стрелки и прямоугольник, viewBox 48. */
    val LOGO_VIEWPORT = 48f
    val LOGO = listOf(
        VPath("M9.51 20.12A15 15 0 0 1 35.49 14.36", strokeW = 4.4f),
        VPath("M38.49 27.88A15 15 0 0 1 12.51 33.64", strokeW = 4.4f),
        VPath("M39.32 11.15 39.99 19.72 31.66 17.58z", fill = true, strokeW = 1.5f),
        VPath("M8.68 36.86 8.01 28.28 16.34 30.43z", fill = true, strokeW = 1.5f),
        VPath("M18.4 20h11.2A2.4 2.4 0 0 1 32 22.4v4.7a2.4 2.4 0 0 1-2.4 2.4H18.4A2.4 2.4 0 0 1 16 27.1v-4.7A2.4 2.4 0 0 1 18.4 20z", fill = true),
    )

    /** Четыре столбика сигнала (viewBox 24): первые [level] залиты, остальные бледные (.35). */
    fun signal(level: Int): List<VPath> = listOf(
        roundRectPath(2f, 16f, 4f, 5f, 1f), roundRectPath(7.5f, 12f, 4f, 9f, 1f),
        roundRectPath(13f, 8f, 4f, 13f, 1f), roundRectPath(18.5f, 3f, 4f, 18f, 1f),
    ).mapIndexed { i, d -> VPath(d, fill = true, alpha = if (i < level) 1f else 0.35f) }

    /** Раздел «Вид»: круг, закрашенный наполовину. */
    val HALF_CIRCLE = listOf(
        VPath("M3 12a9 9 0 1 0 18 0 9 9 0 1 0-18 0", strokeW = 2f),
        VPath("M12 3a9 9 0 0 0 0 18z", fill = true, strokeW = 2f),
    )

    /** YouTube Music на чипе плееров: круг с треугольником. */
    val YT_MUSIC = listOf(
        VPath("M2.5 12a9.5 9.5 0 1 0 19 0 9.5 9.5 0 1 0-19 0", strokeW = 2.2f),
        VPath("M10 8.5v7l6-3.5z", fill = true, strokeW = 2.2f),
    )
}

@Composable
fun VectorIcon(paths: List<VPath>, viewport: Float, sizePx: Int, color: Color, modifier: Modifier = Modifier) {
    val m = LocalMetrics.current
    val parsed = remember(paths) { paths.map { PathParser().parsePathString(it.d).toPath() } }
    Canvas(modifier.size(m.dp(sizePx))) {
        val sc = size.minDimension / viewport
        scale(sc, sc, pivot = Offset.Zero) {
            for ((i, p) in paths.withIndex()) {
                val c = color.copy(alpha = color.alpha * p.alpha)
                if (p.fill) drawPath(parsed[i], c, style = Fill)
                if (p.strokeW != null) {
                    drawPath(parsed[i], c, style = Stroke(width = p.strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
    }
}

@Composable
fun SvgIcon(paths: List<String>, sizePx: Int, color: Color, modifier: Modifier = Modifier, stroke: Float = 2.2f, fill: Boolean = false) {
    val m = LocalMetrics.current
    val parsed = remember(paths) { paths.map { PathParser().parsePathString(it).toPath() } }
    Canvas(modifier.size(m.dp(sizePx))) {
        val sc = size.minDimension / 24f
        scale(sc, sc, pivot = Offset.Zero) {
            for (p in parsed) {
                if (fill) drawPath(p, color, style = Fill)
                else drawPath(p, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/**
 * Кнопка под тач ГУ: нажатое состояние сразу на DOWN, действие на UP, долгий тап считается по времени
 * между DOWN и UP ([PressLogic]), сдвиг пальца отмену не вызывает (clickable с touch slop тут не подходит).
 */
@Composable
fun Pressable(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    description: String? = null,
    onLong: (() -> Unit)? = null,
    onPress: (() -> Unit)? = null,
    onTap: () -> Unit,
    content: @Composable BoxScope.(pressed: Boolean) -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val tap = rememberUpdatedState(onTap)
    val long = rememberUpdatedState(onLong)
    val press = rememberUpdatedState(onPress)
    val on = rememberUpdatedState(enabled)
    Box(
        modifier
            .semantics {
                role = Role.Button
                if (description != null) contentDescription = description
                onClick { tap.value(); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    // потреблённый DOWN - чужой (вложенная кнопка уже забрала касание)
                    val down = awaitFirstDown()
                    if (!on.value) return@awaitEachGesture
                    down.consume()
                    pressed = true
                    // анимация нажатия стартует по DOWN: UP с ГУ приходит на 200-500 мс позже
                    press.value?.invoke()
                    var up: PointerInputChange? = null
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) { up = change; break }
                    }
                    pressed = false
                    up?.let {
                        val kind = PressLogic.classify(down.uptimeMillis, it.uptimeMillis, long.value != null)
                        if (kind == Press.LONG) long.value?.invoke() else tap.value()
                    }
                }
            },
        content = { content(pressed) },
    )
}

/** Круглая кнопка цели касания T. */
@Composable
fun RoundButton(
    sizePx: Int, description: String, onTap: () -> Unit, modifier: Modifier = Modifier,
    background: Color = Color.Transparent, enabled: Boolean = true, onLong: (() -> Unit)? = null,
    onPress: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val m = LocalMetrics.current
    Pressable(
        modifier.size(m.dp(sizePx)).clip(CircleShape)
            .background(background), enabled = enabled, description = description, onTap = onTap, onLong = onLong,
        onPress = onPress,
    ) { pressed ->
        if (pressed) Box(Modifier.matchParentSize().background(Pal.SURFACE_PRESSED))
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center, content = content)
    }
}

@Composable
fun Txt(
    text: String, sizePx: Int, modifier: Modifier = Modifier, color: Color = Pal.TEXT,
    weight: FontWeight = FontWeight.Normal, mono: Boolean = false, maxLines: Int = 1, align: TextAlign = TextAlign.Start,
    ellipsis: Boolean = true,
) {
    val m = LocalMetrics.current
    BasicText(
        text, modifier,
        style = TextStyle(
            color = color, fontSize = m.sp(sizePx), fontWeight = weight,
            fontFamily = LauncherFonts.family(LocalContext.current, mono, weight),
            lineHeight = m.sp((sizePx * 1.15f).toInt()), textAlign = align,
        ),
        maxLines = maxLines, softWrap = maxLines > 1, overflow = if (ellipsis) TextOverflow.Ellipsis else TextOverflow.Clip,
    )
}

/** Содержимое карточек всегда слева направо, даже при зеркальной раскладке правого руля. */
@Composable
fun Ltr(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
}

fun cardShape(r: Dp): Shape = androidx.compose.foundation.shape.RoundedCornerShape(r)
