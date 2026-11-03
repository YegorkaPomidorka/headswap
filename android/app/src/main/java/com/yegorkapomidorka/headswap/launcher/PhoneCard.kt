package com.yegorkapomidorka.headswap.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.yegorkapomidorka.headswap.R

@Composable
private fun typeLabel(type: CallType): String = stringResource(when (type) {
    CallType.MISSED -> R.string.l_call_missed
    CallType.OUTGOING -> R.string.l_call_outgoing
    else -> R.string.l_call_incoming
})

@Composable
private fun whenText(timeMs: Long): String =
    PhoneLogic.whenLabel(System.currentTimeMillis(), timeMs, yesterday = stringResource(R.string.l_yesterday))

/** Кнопки карточки «Телефон» (PhoneStates.dc.html): все размером T. */
private enum class PhoneBtn { CALL, RECALL, ACCEPT, DECLINE, END, CANCEL, CONTACTS, MUTE, ALLOW }

@Composable
private fun PhoneButton(b: PhoneBtn, number: String?) {
    val s = LocalMetrics.current.spec
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
        PhoneBtn.CALL, PhoneBtn.RECALL, PhoneBtn.ACCEPT -> RoundButton(s.t, desc, onTap, background = Pal.GREEN) {
            SvgIcon(Ico.PHONE, s.ic, Pal.ON_GREEN, stroke = 2.4f)
        }
        PhoneBtn.DECLINE, PhoneBtn.END, PhoneBtn.CANCEL -> RoundButton(s.t, desc, onTap, background = Pal.RED) {
            SvgIcon(Ico.PHONE, s.ic, Pal.WHITE, stroke = 2.4f, modifier = Modifier.rotate(135f))
        }
        else -> RoundButton(s.t, desc, onTap, background = Pal.SURFACE_HI) {
            SvgIcon(when (b) { PhoneBtn.MUTE -> Ico.MIC_OFF; PhoneBtn.ALLOW -> Ico.DEVICE; else -> Ico.CONTACT }, s.ic, Pal.TEXT)
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
    /** Входящий в компактном виде: значок звонка вместо текста. */
    val ringIcon: Boolean = false,
    val number: String? = null,
)

/** Карточка «Телефон»: состояния А-Ж из PhoneStates.dc.html, полный и компактный вид. Тап по телу - экран «Вызовы». */
@Composable
fun PhoneCard(modifier: Modifier, shape: Shape? = null) {
    val model = LauncherModel
    val m = LocalMetrics.current
    val s = m.spec
    val phone = PhoneLogic.model(
        PhoneController.logPermitted, PhoneController.callState, PhoneController.callTitle, PhoneController.ringed,
        PhoneController.offhookSec, PhoneController.calls, model.missedSeenMs,
    )
    val accent = LocalAccent.current
    val incoming = phone is PhoneCardModel.Incoming
    val ongoing = phone is PhoneCardModel.Incoming || phone is PhoneCardModel.InCall || phone is PhoneCardModel.Outgoing
    val cardShape = shape ?: RoundedCornerShape(m.dp(s.radius))
    val v = phoneView(phone, accent)
    CardBox(modifier, background = if (incoming) accent.copy(alpha = 0.14f) else Pal.SURFACE, shape = cardShape) {
        if (!ongoing) Pressable(Modifier.fillMaxSize(), description = stringResource(R.string.l_phone), onTap = { LauncherActions.openCalls() }) {}
        // рамка входящего: inset 4px акцентом
        if (incoming) Box(Modifier.fillMaxSize().border(m.dp(4), accent, cardShape))
        Row(
            Modifier.fillMaxSize().padding(horizontal = m.dp(s.g), vertical = m.dp(s.g8)),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(s.g)),
        ) {
            val buttons = when {
                s.phoneCompact -> v.compact
                s.wide || incoming -> v.full
                else -> v.full.take(1)
            }
            when {
                s.phoneCompact && v.ringIcon -> {
                    SvgIcon(Ico.PHONE_RING, (s.t * 0.65f).toInt(), accent)
                    Spacer(Modifier.weight(1f))
                }
                s.phoneCompact -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.dp(s.g4))) {
                    Txt(v.name, if (v.small) s.f2 else s.f1, color = v.nameColor ?: Pal.TEXT, weight = FontWeight.Bold, ellipsis = false)
                    if (v.line2.isNotEmpty()) Txt(v.line2, s.f2, color = v.line2Color ?: Pal.SEC, weight = FontWeight.SemiBold, mono = true, ellipsis = false)
                }
                else -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.dp(s.g4))) {
                    Txt(v.head, s.f2, color = v.headColor, weight = if (v.headMono) FontWeight.SemiBold else FontWeight.Normal, mono = v.headMono)
                    Row(horizontalArrangement = Arrangement.spacedBy(m.dp(s.g8))) {
                        Txt(v.name, s.f1, Modifier.weight(1f, fill = false).alignByBaseline(), weight = FontWeight.Bold)
                        if (v.sub.isNotEmpty()) Txt(v.sub, s.f2, Modifier.alignByBaseline(), color = Pal.SEC,
                            weight = FontWeight.SemiBold, mono = true)
                    }
                }
            }
            for (b in buttons) PhoneButton(b, v.number)
        }
    }
}

@Composable
private fun phoneView(phone: PhoneCardModel, accent: Color): PhoneView {
    val unknown = stringResource(R.string.l_phone)
    return when (phone) {
        is PhoneCardModel.Missed -> {
            val time = whenText(phone.last.timeMs)
            PhoneView(stringResource(R.string.l_phone_missed, phone.count), Pal.MISSED, name = phone.last.title, sub = time,
                nameColor = Pal.MISSED, line2 = "$time · ${phone.count}",
                full = listOf(PhoneBtn.RECALL, PhoneBtn.CONTACTS), number = phone.last.number)
        }
        is PhoneCardModel.LastCall -> {
            val time = whenText(phone.last.timeMs)
            PhoneView(typeLabel(phone.last.type), Pal.SEC, name = phone.last.title, sub = time, line2 = time,
                full = listOf(PhoneBtn.CALL, PhoneBtn.CONTACTS), number = phone.last.number)
        }
        PhoneCardModel.Empty -> PhoneView(unknown, Pal.SEC, name = stringResource(R.string.l_phone_none), small = true,
            full = listOf(PhoneBtn.CONTACTS))
        PhoneCardModel.NoAccess -> PhoneView(stringResource(R.string.l_phone_grant), Pal.SEC,
            name = stringResource(R.string.l_phone_no_access), small = true, full = listOf(PhoneBtn.ALLOW))
        is PhoneCardModel.Incoming -> PhoneView(stringResource(R.string.l_phone_incoming), accent, name = phone.title.ifEmpty { unknown },
            full = listOf(PhoneBtn.ACCEPT, PhoneBtn.DECLINE), compact = listOf(PhoneBtn.ACCEPT, PhoneBtn.DECLINE), ringIcon = true)
        is PhoneCardModel.InCall -> {
            val timer = PhoneLogic.timer(phone.elapsedSec)
            PhoneView(stringResource(R.string.l_phone_talk, timer), Pal.GREEN, headMono = true, name = phone.title.ifEmpty { unknown },
                line2 = timer, line2Color = Pal.GREEN, full = listOf(PhoneBtn.MUTE, PhoneBtn.END), compact = listOf(PhoneBtn.END))
        }
        is PhoneCardModel.Outgoing -> PhoneView(stringResource(R.string.l_phone_calling), Pal.SEC, name = phone.title.ifEmpty { unknown },
            line2 = stringResource(R.string.l_phone_calling), full = listOf(PhoneBtn.CANCEL))
    }
}

/** Экран «Вызовы» (Calls.dc.html): статус, вкладки «Недавние» и «Контакты», 2 колонки x 4 строки, страницы стрелками. */
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
        CallsRow(it.title, PhoneLogic.whenLabel(System.currentTimeMillis(), it.timeMs, yesterday = yesterday),
            if (it.type == CallType.MISSED) Pal.MISSED else Pal.TEXT, it.number)
    } else PhoneController.contacts.map { CallsRow(it.name.ifEmpty { it.number }, "", Pal.TEXT, it.number) }
    val perPage = 8
    val pages = Pager.pageCount(rows.size, perPage)
    val current = Pager.clamp(page, rows.size, perPage)
    val shown = Pager.slice(rows, current, perPage)
    Column(
        Modifier.fillMaxSize().padding(start = m.dp(s.g), top = m.dp(s.g8), end = m.dp(s.m), bottom = m.dp(s.m)),
        verticalArrangement = Arrangement.spacedBy(m.dp(s.g8)),
    ) {
        StatusBar()
        PagerHeader(current, pages, onPage = { page = it }, leading = {
            TabPill(stringResource(R.string.l_recent), selected = tab == 0) { tab = 0; page = 0 }
            TabPill(stringResource(R.string.l_contacts), selected = tab == 1) { tab = 1; page = 0 }
        })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Txt(
                        stringResource(when {
                            !permitted -> R.string.l_calls_no_permission
                            tab == 0 -> R.string.l_calls_empty
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
