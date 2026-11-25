package com.yegorkapomidorka.headswap.launcher

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import com.yegorkapomidorka.headswap.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
private fun typeLabel(type: CallType): String = stringResource(when (type) {
    CallType.MISSED -> R.string.l_call_missed
    CallType.OUTGOING -> R.string.l_call_outgoing
    else -> R.string.l_call_incoming
})

@Composable
private fun whenText(timeMs: Long): String =
    PhoneLogic.whenLabel(System.currentTimeMillis(), timeMs, yesterday = stringResource(R.string.l_yesterday),
        twelveHour = LauncherModel.clock12h)

/** Кнопки карточки «Телефон» (PhoneStates.dc.html): в самой карточке 0,8 T, на экране «Вызовы» T. */
private enum class PhoneBtn { CALL, RECALL, ACCEPT, DECLINE, END, CANCEL, CONTACTS, MUTE, ALLOW }

/** Диаметр кнопок самой карточки «Телефон»: 0,8 T (на экране «Вызовы» кнопки остаются размером T). */
private fun cardBtn(t: Int): Int = (t * 0.8f).roundToInt()

/** [size] - диаметр кнопки, значок внутри 0,5 от него; без него кнопка размером T со значком [LauncherSpec.ic] (экран «Вызовы»). */
@Composable
private fun PhoneButton(b: PhoneBtn, number: String?, size: Int? = null) {
    val s = LocalMetrics.current.spec
    val d = size ?: s.t
    val ic = if (size == null) s.ic else (size * 0.5f).roundToInt()
    val ctx = LauncherModel.context
    val desc = stringResource(when (b) {
        PhoneBtn.CALL -> R.string.l_call
        PhoneBtn.RECALL -> R.string.l_call_back
        PhoneBtn.ACCEPT -> R.string.l_accept
        PhoneBtn.DECLINE -> R.string.l_decline
        PhoneBtn.END -> R.string.l_hangup
        PhoneBtn.CANCEL -> R.string.l_cancel_call
        PhoneBtn.CONTACTS -> R.string.l_calls_contacts
        PhoneBtn.MUTE -> R.string.l_mute
        PhoneBtn.ALLOW -> R.string.l_phone_open_request
    })
    val onTap: () -> Unit = when (b) {
        PhoneBtn.CALL, PhoneBtn.RECALL -> ({ number?.let { LauncherActions.callBack(it) } })
        PhoneBtn.ACCEPT -> ({ PhoneController.accept(ctx) })
        PhoneBtn.DECLINE, PhoneBtn.END, PhoneBtn.CANCEL -> ({ PhoneController.end(ctx) })
        PhoneBtn.CONTACTS -> ({ LauncherActions.openCalls() })
        PhoneBtn.MUTE -> ({ PhoneController.toggleMute(ctx) })
        PhoneBtn.ALLOW -> ({ LauncherActions.openPhoneRequest() })
    }
    when (b) {
        PhoneBtn.CALL, PhoneBtn.RECALL, PhoneBtn.ACCEPT -> TintedPhoneButton(desc, onTap, LocalAccent.current, Ico.CALL, d, ic)
        PhoneBtn.DECLINE, PhoneBtn.END, PhoneBtn.CANCEL -> TintedPhoneButton(desc, onTap, DECLINE_RED, Ico.CALL_END, d, ic)
        else -> RoundButton(d, desc, onTap, background = Pal.SURFACE_HI) {
            SvgIcon(when (b) { PhoneBtn.MUTE -> Ico.MIC_OFF; PhoneBtn.ALLOW -> Ico.DEVICE; else -> Ico.CONTACT }, ic, Pal.WHITE)
        }
    }
}

/** Красный «нет» карточки «Телефон» (Pal.RED здесь темнее макета): фиксированный, от акцента темы не зависит. */
private val DECLINE_RED = Color(0xFFE56054)

/** Кнопка «да»/«нет» диаметром [size]: заливка [tint] с альфой .32 и сплошная обводка 2 dp, значок [iconSize] залитый белый. */
@Composable
private fun TintedPhoneButton(desc: String, onTap: () -> Unit, tint: Color, icon: List<String>, size: Int, iconSize: Int) {
    val m = LocalMetrics.current
    RoundButton(size, desc, onTap, Modifier.border(m.dp(2), tint, CircleShape), background = tint.copy(alpha = 0.32f)) {
        SvgIcon(icon, iconSize, Pal.WHITE, fill = true)
    }
}

/** Линейный градиент под 170° (как у стекла рейки, [glassRail]) на всю область рисования. */
internal fun DrawScope.brush170(vararg stops: Pair<Float, Color>): Brush {
    val rad = Math.toRadians(170.0)
    val dx = sin(rad).toFloat()
    val dy = (-cos(rad)).toFloat()
    val len = abs(size.width * dx) + abs(size.height * dy)
    val c = Offset(size.width / 2, size.height / 2)
    return Brush.linearGradient(*stops, start = Offset(c.x - dx * len / 2, c.y - dy * len / 2),
        end = Offset(c.x + dx * len / 2, c.y + dy * len / 2))
}

/** Стеклянный ли нижний ряд главного: общий «Стиль» из настроек ([RailStyle.GLASS]). */
internal val homeGlassStyle: Boolean get() = LauncherModel.railStyle == RailStyle.GLASS

/** Подложка карточки нижнего ряда: [GlassBg] под стеклом или сплошная [Pal.SURFACE]. */
@Composable
internal fun homeCardBg(): Color = if (homeGlassStyle) GlassBg else Pal.SURFACE

/**
 * Стекло в покое поверх [GlassBg]: белый градиент 170° (.18 -> .06 -> .03 -> .10), свечение сверху на 10 px, кромка .18
 * по форме и верхняя линия .5. Общее для карточки «Телефон» и панели быстрых приложений. [gold] - золотой градиент 170°
 * поверх (входящий и разговор). Рисуется слоем внутри CardBox: внешняя половина обводки обрезается формой карточки.
 * [k] - px макета в px экрана (s.t / 96, как у рейки). [base] - своя подложка под стеклом (панели поверх плиток).
 */
private fun DrawScope.drawHomeGlass(shape: Shape, k: Float, gold: Color? = null, base: Color? = null) {
    if (base != null) drawRect(base)
    drawRect(brush170(0f to Color.White.copy(alpha = 0.18f), 0.28f to Color.White.copy(alpha = 0.06f),
        0.72f to Color.White.copy(alpha = 0.03f), 1f to Color.White.copy(alpha = 0.10f)))
    if (gold != null) drawRect(brush170(0f to gold.copy(alpha = 0.20f), 0.45f to gold.copy(alpha = 0.05f),
        1f to gold.copy(alpha = 0.12f)))
    drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0f)), endY = 10f * k),
        size = Size(size.width, 10f * k))
    drawHomeGlassEdge(shape, k)
}

/** Кромка стекла: обводка .18 по форме (внешняя половина обрезается формой плитки) и верхняя линия .5, по 1.5 px макета. */
private fun DrawScope.drawHomeGlassEdge(shape: Shape, k: Float) {
    drawOutline(shape.createOutline(size, layoutDirection, this), Color.White.copy(alpha = 0.18f), style = Stroke(width = 2f * 1.5f * k))
    drawRect(Color.White.copy(alpha = 0.5f), Offset.Zero, Size(size.width, 1.5f * k))
}

/** Стекло в покое для панели быстрых приложений: тот же слой, что у карточки «Телефон» ([drawHomeGlass]). */
internal fun Modifier.homeGlass(shape: Shape, k: Float): Modifier = drawBehind { drawHomeGlass(shape, k) }

/**
 * Только кромка стекла ([drawHomeGlassEdge]) поверх содержимого плитки: обложка и карта не закрашиваются, линия не
 * перекрывается ими. Ставить до внутренних clip/background плитки; [enabled] по умолчанию - стиль «Стекло» ([homeGlassStyle]).
 */
internal fun Modifier.homeGlassEdge(shape: Shape, k: Float, enabled: Boolean = homeGlassStyle): Modifier =
    if (!enabled) this else clip(shape).drawWithContent { drawContent(); drawHomeGlassEdge(shape, k) }

/** Подложка стеклянных панелей поверх карты и плиток (шторка и язычок уведомлений): темнее [GlassBg], чтобы читался текст. */
internal val GlassPanelBg = Color(14, 20, 26).copy(alpha = 0.86f)

/** Стекло панели поверх карты и плиток: [GlassPanelBg] (alpha .86) и тот же слой, что у [homeGlass]. Рисуется внутри clip по форме. */
internal fun Modifier.homeGlassPanel(shape: Shape, k: Float): Modifier = drawBehind { drawHomeGlass(shape, k, base = GlassPanelBg) }

/**
 * Слой карточки «Телефон» по стилю: [glass] - [drawHomeGlass] и, у входящего, пульсация (700 мс туда, 700 обратно, цикл
 * 1400 мс): рамка 3 px акцентом 1 <-> .2 и внутреннее свечение акцентом глубиной 48 px, .55 <-> .05 у края. Внешнего ореола
 * нет: CardBox обрезает его формой карточки. Сплошной (не [glass]): без стекла и свечения, входящий и разговор - фон
 * акцентом .16 поверх [Pal.SURFACE]; рамка 3 px акцентом, у входящего пульсирует 1 <-> .2 в том же цикле.
 */
@Composable
private fun Modifier.phoneSurface(shape: Shape, glass: Boolean, incoming: Boolean, accent: Color, k: Float, active: Boolean = false): Modifier {
    val pulse = if (incoming) rememberInfiniteTransition(label = "ring")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(700, easing = EaseInOut), RepeatMode.Reverse), label = "p") else null
    // [active]: разговор и исходящий - то же золото, но без пульсации: видно, что звонок идёт (Егор 20.11)
    val gold = incoming || active
    if (!glass) return drawBehind {
        if (gold) drawRect(accent.copy(alpha = 0.16f))
        val border = if (pulse != null) 0.2f + 0.8f * pulse.value else if (active) 1f else return@drawBehind
        drawOutline(shape.createOutline(size, layoutDirection, this), accent.copy(alpha = border), style = Stroke(width = 2f * 3f * k))
    }
    return drawBehind {
        drawHomeGlass(shape, k, if (gold) accent else null)
        val outline = shape.createOutline(size, layoutDirection, this)
        if (pulse != null) {
            val p = pulse.value
            val layers = 12
            val glow = 48f * k
            // вложенные обводки копят альфу к краю: на краю все слои, итог 1 - (1 - a)^layers = нужной альфе края
            val edge = 0.05f + 0.5f * p
            val a = 1f - (1f - edge).pow(1f / layers)
            for (i in 1..layers) drawOutline(outline, accent.copy(alpha = a), style = Stroke(width = 2f * glow * i / layers))
            drawOutline(outline, accent.copy(alpha = 0.2f + 0.8f * p), style = Stroke(width = 2f * 3f * k))
        } else if (active) {
            val layers = 12
            val a = 1f - (1f - 0.25f).pow(1f / layers)
            for (i in 1..layers) drawOutline(outline, accent.copy(alpha = a), style = Stroke(width = 2f * 32f * k * i / layers))
            drawOutline(outline, accent.copy(alpha = 0.9f), style = Stroke(width = 2f * 3f * k))
        }
    }
}

/**
 * Что нарисовать в карточке. Полный вид: шапка цветом состояния, имя и время в строку. Компактный (узкая карточка):
 * имя цветом состояния, ниже [line2], без многоточий; [small] - длинная фраза вместо имени, шрифт F2.
 */
private class PhoneView(
    val head: String, val headColor: Color, val headMono: Boolean = false,
    val name: String, val sub: String = "",
    val nameColor: Color? = null, val small: Boolean = false, val line2: String = "", val line2Color: Color? = null,
    val full: List<PhoneBtn>, val compact: List<PhoneBtn> = full.take(1),
    val number: String? = null,
)

/** Состояние карточки «Телефон» по текущим данным контроллера. */
@Composable
fun currentPhoneModel(): PhoneCardModel = PhoneLogic.model(
    PhoneController.logPermitted, PhoneController.callState, PhoneController.callTitle, PhoneController.ringed,
    PhoneController.offhookSec, PhoneController.calls, LauncherModel.missedSeenMs,
)

/**
 * Карточка «Телефон»: состояния А-Ж из PhoneStates.dc.html, полный и компактный вид. Тап по телу - экран «Вызовы».
 * В портрете в покое - без текста: одна кнопка-иконка по центру (пропущенные - бейдж в углу); звонок там идёт
 * полосой [PortraitCallBar] вместо всего нижнего ряда, а в карточке остаётся только на оверлее входящего.
 */
@Composable
fun PhoneCard(modifier: Modifier, shape: Shape? = null, compact: Boolean = LocalMetrics.current.spec.phoneCompact) {
    val m = LocalMetrics.current
    val s = m.spec
    val phone = currentPhoneModel()
    val accent = LocalAccent.current
    val incoming = phone is PhoneCardModel.Incoming
    val ongoing = phone.ongoing
    val cardShape = shape ?: RoundedCornerShape(m.dp(s.radius))
    val v = phoneView(phone, accent)
    CardBox(modifier, background = homeCardBg(), shape = cardShape) {
        Box(Modifier.fillMaxSize().phoneSurface(cardShape, homeGlassStyle, incoming, accent, s.t / 96f,
            active = phone is PhoneCardModel.InCall || phone is PhoneCardModel.Outgoing))
        if (s.portrait && !ongoing) {
            val open = { LauncherActions.openCalls() }
            Pressable(Modifier.fillMaxSize(), description = stringResource(R.string.l_phone), onTap = open) {}
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val d = cardBtn(s.t)
                TintedPhoneButton(stringResource(R.string.l_phone), open, accent, Ico.CALL, d, (d * 0.5f).roundToInt())
            }
            if (phone is PhoneCardModel.Missed) {
                val badge = m.dp((s.f2 * 1.4f).toInt())
                Box(
                    Modifier.align(Alignment.TopEnd).padding(top = m.dp(s.g4), end = m.dp(s.g4))
                        .defaultMinSize(minWidth = badge, minHeight = badge).clip(RoundedCornerShape(badge / 2))
                        .background(Pal.MISSED).padding(horizontal = m.dp(s.g4)),
                    contentAlignment = Alignment.Center,
                ) { Txt(phone.count.toString(), s.f2, color = Pal.WHITE, weight = FontWeight.Bold) }
            }
            return@CardBox
        }
        if (!ongoing) Pressable(Modifier.fillMaxSize(), description = stringResource(R.string.l_phone), onTap = { LauncherActions.openCalls() }) {}
        // входящий в узкой карточке: без шапки, имя в две строки, кнопки ближе друг к другу и к правому краю (ширина не меняется)
        val tightIncoming = compact && incoming
        val btn = cardBtn(s.t)
        Row(
            Modifier.fillMaxSize().padding(start = m.dp(s.g), end = m.dp(if (tightIncoming) s.g / 2 else s.g), top = m.dp(s.g8),
                bottom = m.dp(s.g8)),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(s.g)),
        ) {
            val buttons = when {
                compact -> v.compact
                s.wide || incoming -> v.full
                else -> v.full.take(1)
            }
            if (tightIncoming) {
                Column(Modifier.weight(1f)) { FitName(v.name, s.f1, (s.f1 * 0.5f).toInt()) }
            } else {
                // узкая карточка (1280x720 и меньше) текстом как широкая: шапка и имя с многоточием (Егор 19.11)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.dp((s.g * 0.9f).roundToInt()))) {
                    val head = v.head
                    val headMono = v.headMono
                    // узкая карточка: шапка мельче (0,75 F2), чтобы имя было крупнее неё и номер влезал (макет 20.11)
                    val headPx = if (compact) (s.f2 * 0.75f).toInt() else s.f2
                    Txt(head, headPx, color = v.headColor, weight = if (headMono) FontWeight.SemiBold else FontWeight.Normal, mono = headMono,
                        minSizePx = (headPx * 0.8f).toInt())
                    Row(horizontalArrangement = Arrangement.spacedBy(m.dp(s.g8))) {
                        // номер или длинное имя не влезает - сначала мельче (до 0,6, но имя всегда крупнее шапки), многоточие только после
                        Txt(v.name, s.f1, Modifier.weight(1f, fill = false).alignByBaseline(), weight = FontWeight.Bold,
                            minSizePx = maxOf((s.f1 * 0.6f).toInt(), headPx).coerceAtMost(s.f1))
                        if (v.sub.isNotEmpty()) Txt(v.sub, s.f2, Modifier.alignByBaseline(), color = Pal.SEC,
                            weight = FontWeight.SemiBold, mono = true)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(m.dp(if (tightIncoming) s.g / 2 else s.g))) {
                for (b in buttons) PhoneButton(b, v.number, btn)
            }
        }
    }
}

/**
 * Полоса звонка портретного главного: заменяет весь нижний ряд (приложения и «Телефон»). Слева имя и состояние или таймер,
 * справа кнопки; рамка 2 px цветом состояния. Данные и действия те же, что у [PhoneCard].
 */
@Composable
fun PortraitCallBar(modifier: Modifier) {
    val m = LocalMetrics.current
    val s = m.spec
    val accent = LocalAccent.current
    val phone = currentPhoneModel()
    val v = phoneView(phone, accent)
    val ring = when (phone) {
        is PhoneCardModel.Incoming -> accent
        is PhoneCardModel.InCall -> Pal.WHITE.copy(alpha = 0.28f)
        else -> Pal.SEC
    }
    val shape = RoundedCornerShape(m.dp(s.radius))
    val incoming = phone is PhoneCardModel.Incoming
    // входящий: «Отклонить» слева, «Принять» с краю под большой палец
    val buttons = if (incoming) listOf(PhoneBtn.DECLINE, PhoneBtn.ACCEPT) else v.full
    CardBox(modifier, background = homeCardBg(), shape = shape) {
        val active = phone is PhoneCardModel.InCall || phone is PhoneCardModel.Outgoing
        Box(Modifier.fillMaxSize().phoneSurface(shape, homeGlassStyle, incoming, accent, s.t / 96f, active = active))
        // у входящего рамка - пульсация слоя, у разговора и исходящего - золотая рамка слоя
        if (!incoming && !active) Box(Modifier.fillMaxSize().border(m.dp(2), ring, shape))
        Row(
            Modifier.fillMaxSize().padding(horizontal = m.dp(s.g)),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(s.g)),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.dp(s.g4))) {
                Txt(v.name, s.f1, weight = FontWeight.Bold)
                Txt(v.line2.ifEmpty { v.head }, s.f2, color = v.line2Color ?: v.headColor, weight = FontWeight.SemiBold,
                    mono = phone is PhoneCardModel.InCall)
            }
            for (b in buttons) PhoneButton(b, v.number, cardBtn(s.t))
        }
    }
}

/**
 * Имя входящего в узкой карточке: две строки, шрифт от [sizePx] вниз до [minSizePx] так, чтобы самое длинное слово влезло
 * целиком (автоподбор Compose слово посреди не защищает: перенос режет его по буквам), многоточие только если и так не влезает.
 */
@Composable
private fun FitName(text: String, sizePx: Int, minSizePx: Int) {
    val m = LocalMetrics.current
    val measurer = rememberTextMeasurer()
    val family = LauncherFonts.family(LocalContext.current, false, FontWeight.Bold)
    BoxWithConstraints {
        val style = TextStyle(fontSize = m.sp(sizePx), fontWeight = FontWeight.Bold, fontFamily = family)
        val widest = text.split(Regex("\\s+")).maxOf { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width }
        val fit = if (widest <= constraints.maxWidth) sizePx else (sizePx * 0.98f * constraints.maxWidth / widest).toInt()
        Txt(text, fit.coerceIn(minOf(minSizePx, sizePx), sizePx), weight = FontWeight.Bold, maxLines = 2, minSizePx = minSizePx)
    }
}

@Composable
private fun phoneView(phone: PhoneCardModel, accent: Color): PhoneView {
    val unknown = stringResource(R.string.l_phone)
    // «Скрывать номера» (h1431): вместо имени звонящего - общая подпись; number для перезвона остаётся
    val hide = LauncherModel.hideCaller
    val callName = stringResource(R.string.l_call_hidden)
    return when (phone) {
        is PhoneCardModel.Missed -> {
            val time = whenText(phone.last.timeMs)
            // шапки одним словом, без счётчика и времени (Егор 19.11): «Пропущенный», «Исходящий», «Входящий», таймер, «Вызов…»
            PhoneView(typeLabel(CallType.MISSED), Pal.MISSED,
                name = if (hide) pluralStringResource(R.plurals.l_missed_hidden, phone.count, phone.count) else phone.last.title,
                nameColor = Pal.MISSED, line2 = "$time · ${phone.count}",
                full = listOf(PhoneBtn.RECALL), number = phone.last.number)
        }
        is PhoneCardModel.LastCall -> {
            val time = whenText(phone.last.timeMs)
            PhoneView(typeLabel(phone.last.type), Pal.SEC, name = if (hide) unknown else phone.last.title, line2 = time,
                full = listOf(PhoneBtn.CALL), number = phone.last.number)
        }
        PhoneCardModel.Empty -> PhoneView(unknown, Pal.SEC, name = stringResource(R.string.l_phone_none), small = true,
            full = listOf(PhoneBtn.CONTACTS))
        PhoneCardModel.NoAccess -> PhoneView(stringResource(R.string.l_phone_grant), Pal.SEC,
            name = stringResource(R.string.l_phone_no_access), small = true, full = listOf(PhoneBtn.ALLOW))
        is PhoneCardModel.Incoming -> PhoneView(typeLabel(CallType.INCOMING), accent, name = if (hide) callName else phone.title.ifEmpty { unknown },
            full = listOf(PhoneBtn.ACCEPT, PhoneBtn.DECLINE), compact = listOf(PhoneBtn.ACCEPT, PhoneBtn.DECLINE))
        is PhoneCardModel.InCall -> {
            val timer = PhoneLogic.timer(phone.elapsedSec)
            PhoneView(timer, Pal.TEXT, headMono = true, name = if (hide) callName else phone.title.ifEmpty { unknown },
                line2 = timer, line2Color = Pal.TEXT, full = listOf(PhoneBtn.END), compact = listOf(PhoneBtn.END))
        }
        is PhoneCardModel.Outgoing -> PhoneView(stringResource(R.string.l_phone_calling), Pal.SEC, name = if (hide) callName else phone.title.ifEmpty { unknown },
            line2 = stringResource(R.string.l_phone_calling), full = listOf(PhoneBtn.CANCEL))
    }
}

/**
 * Экран «Вызовы» (Calls.dc.html): статус, вкладки «Недавние», «Избранные» (звёздочка в телефонной книге) и «Контакты»,
 * 2 колонки x 4 строки, страницы стрелками. tab: 0 недавние, 1 контакты, 2 избранные.
 */
@Composable
fun CallsScreen() {
    val model = LauncherModel
    val m = LocalMetrics.current
    val s = m.spec
    val ctx = model.context
    var tab by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { PhoneController.loadAsync(ctx) }
    val permitted = if (tab == 0) PhoneController.logPermitted else PhoneController.contactsPermitted
    val yesterday = stringResource(R.string.l_yesterday)
    // тип вызова не пишем: пропущенные - имя цветом пропущенного, справа только время
    val rows: List<CallsRow> = if (tab == 0) PhoneController.calls.map {
        CallsRow(it.title, PhoneLogic.whenLabel(System.currentTimeMillis(), it.timeMs, yesterday = yesterday,
            twelveHour = LauncherModel.clock12h),
            if (it.type == CallType.MISSED) Pal.MISSED else Pal.TEXT, it.number)
    } else (if (tab == 2) PhoneController.favorites else PhoneController.contacts)
        .map { CallsRow(it.name.ifEmpty { it.number }, "", Pal.TEXT, it.number) }
    val perPage = 8
    val pages = Pager.pageCount(rows.size, perPage)
    val current = Pager.clamp(page, rows.size, perPage)
    val shown = Pager.slice(rows, current, perPage)
    Column(
        Modifier.fillMaxSize().padding(start = m.dp(s.g), top = m.dp(s.g8), end = m.dp(s.padEnd), bottom = m.dp(s.padBottom)),
        verticalArrangement = Arrangement.spacedBy(m.dp(s.g8)),
    ) {
        StatusBar()
        PagerHeader(current, pages, onPage = { page = it }, leading = {
            TabPill(stringResource(R.string.l_recent), selected = tab == 0) { tab = 0; page = 0 }
            TabPill(stringResource(R.string.l_favorites), selected = tab == 2) { tab = 2; page = 0 }
            TabPill(stringResource(R.string.l_contacts), selected = tab == 1) { tab = 1; page = 0 }
        })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Txt(
                        stringResource(when {
                            !permitted -> R.string.l_calls_no_permission
                            tab == 0 -> R.string.l_calls_empty
                            tab == 2 -> R.string.l_favorites_empty
                            else -> R.string.l_contacts_empty
                        }), s.f1, color = Pal.SEC, maxLines = 3, align = TextAlign.Center,
                    )
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(m.dp(s.g8))) {
                    for (r in 0 until 4) {
                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(m.dp(s.g))) {
                            for (c in 0 until 2) {
                                Box(Modifier.weight(1f).fillMaxHeight()) {
                                    shown.getOrNull(r * 2 + c)?.let { CallsRowCell(it) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Вкладка экрана «Вызовы»: выбранная - золото .32 с обводкой 2px, остальные - белый .10. */
@Composable
private fun TabPill(text: String, selected: Boolean, onTap: () -> Unit) {
    val m = LocalMetrics.current
    val s = m.spec
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(m.dp(s.t / 2))
    Pressable(
        Modifier.height(m.dp(s.t)).clip(shape)
            .background(if (selected) accent.copy(alpha = 0.32f) else LocalPalette.current.btnSet)
            .then(if (selected) Modifier.border(m.dp(2), accent, shape) else Modifier),
        description = text, onTap = onTap,
    ) { pressed ->
        if (pressed) Box(Modifier.matchParentSize().background(Pal.SURFACE_PRESSED))
        Box(Modifier.fillMaxHeight().padding(horizontal = m.dp((s.t * 0.4f).toInt())), contentAlignment = Alignment.Center) {
            Txt(text, s.f1, color = Pal.TEXT, weight = if (selected) FontWeight.Bold else FontWeight.SemiBold)
        }
    }
}

private data class CallsRow(val title: String, val time: String, val titleColor: Color, val number: String)

@Composable
private fun CallsRowCell(row: CallsRow) {
    val m = LocalMetrics.current
    val s = m.spec
    Row(
        Modifier.fillMaxSize().clip(RoundedCornerShape(m.dp(s.radius))).background(Pal.SURFACE)
            .padding(start = m.dp(s.g), end = m.dp(s.g8)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(s.g)),
    ) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(m.dp(s.g8))) {
            Txt(row.title, s.f1, Modifier.weight(1f, fill = false).alignByBaseline(), color = row.titleColor, weight = FontWeight.Bold)
            if (row.time.isNotEmpty()) Txt(row.time, s.f2, Modifier.alignByBaseline(), color = Pal.SEC,
                weight = FontWeight.SemiBold, mono = true)
        }
        PhoneButton(PhoneBtn.CALL, row.number)
    }
}
