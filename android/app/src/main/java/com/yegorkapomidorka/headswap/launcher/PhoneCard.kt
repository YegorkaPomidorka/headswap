package com.yegorkapomidorka.headswap.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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

@Composable
private fun CallButton(sizePx: Int, desc: String, onTap: () -> Unit) {
    val s = LocalMetrics.current.spec
    RoundButton(sizePx, desc, onTap, background = Pal.GREEN) { SvgIcon(Ico.PHONE, s.ic, Pal.ON_GREEN, stroke = 2.4f) }
}

@Composable
private fun HangupButton(sizePx: Int, desc: String, onTap: () -> Unit) {
    val s = LocalMetrics.current.spec
    RoundButton(sizePx, desc, onTap, background = Pal.RED) {
        SvgIcon(Ico.PHONE, s.ic, Pal.WHITE, stroke = 2.4f, modifier = Modifier.rotate(135f))
    }
}

@Composable
private fun ContactsButton(sizePx: Int, onTap: () -> Unit) {
    val s = LocalMetrics.current.spec
    RoundButton(sizePx, stringResource(R.string.l_calls_contacts), onTap, background = Pal.SURFACE_HI) {
        SvgIcon(Ico.CONTACT, s.ic, Pal.WHITE)
    }
}

/** Карточка «Телефон»: состояния А-Е из PhoneStates.dc.html. Тап по телу - экран «Вызовы». */
@Composable
fun PhoneCard(modifier: Modifier) {
    val model = LauncherModel
    val m = LocalMetrics.current
    val s = m.spec
    val ctx = model.context
    val phone = PhoneLogic.model(
        PhoneController.logPermitted, PhoneController.callState, PhoneController.callTitle, PhoneController.ringed,
        PhoneController.offhookSec, PhoneController.calls, model.missedSeenMs,
    )
    val accent = LocalAccent.current
    val incoming = phone is PhoneCardModel.Incoming
    val ongoing = phone is PhoneCardModel.Incoming || phone is PhoneCardModel.InCall || phone is PhoneCardModel.Outgoing
    CardBox(modifier, background = if (incoming) accent.copy(alpha = 0.14f) else Pal.SURFACE) {
        if (!ongoing) Pressable(Modifier.fillMaxSize(), description = stringResource(R.string.l_phone), onTap = { LauncherActions.openCalls() }) {}
        if (incoming) Box(Modifier.fillMaxSize().border(m.dp(s.b4), accent, RoundedCornerShape(m.dp(s.radius))))
        Column(
            Modifier.fillMaxSize().padding(horizontal = m.dp(if (incoming) s.g - s.b4 else s.g), vertical = m.dp(if (incoming) s.g8 - s.b4 else s.g8)),
            verticalArrangement = Arrangement.spacedBy(m.dp(s.g4)),
        ) {
            when (phone) {
                is PhoneCardModel.Missed -> {
                    Txt(stringResource(R.string.l_phone_missed, phone.count), s.fs, color = Pal.MISSED)
                    Body(phone.last.title, whenText(phone.last.timeMs), mono = true) {
                        CallButton(s.t, stringResource(R.string.l_call_back)) { LauncherActions.callBack(phone.last.number) }
                        if (s.wide) ContactsButton(s.t) { LauncherActions.openCalls() }
                    }
                }
                is PhoneCardModel.LastCall -> {
                    Txt(stringResource(R.string.l_phone), s.fs, color = Pal.SEC)
                    Body(phone.last.title, "${typeLabel(phone.last.type)} · ${whenText(phone.last.timeMs)}") {
                        CallButton(s.t, stringResource(R.string.l_call)) { LauncherActions.callBack(phone.last.number) }
                        if (s.wide) ContactsButton(s.t) { LauncherActions.openCalls() }
                    }
                }
                is PhoneCardModel.Empty -> {
                    Txt(stringResource(R.string.l_phone), s.fs, color = Pal.SEC)
                    Body(stringResource(R.string.l_phone_none), if (phone.needPermission) stringResource(R.string.l_phone_grant) else "") {
                        ContactsButton(s.t) { LauncherActions.openCalls() }
                    }
                }
                is PhoneCardModel.Incoming -> {
                    Txt(stringResource(R.string.l_phone_incoming), s.fs, color = accent)
                    Body(phone.title.ifEmpty { stringResource(R.string.l_phone) }, "") {
                        CallButton(s.t, stringResource(R.string.l_accept)) { PhoneController.accept(ctx) }
                        HangupButton(s.t, stringResource(R.string.l_decline)) { PhoneController.end(ctx) }
                    }
                }
                is PhoneCardModel.InCall -> {
                    Txt(stringResource(R.string.l_phone_talk, PhoneLogic.timer(phone.elapsedSec)), s.fs, color = Pal.GREEN,
                        weight = FontWeight.SemiBold, mono = true)
                    Body(phone.title.ifEmpty { stringResource(R.string.l_phone) }, "") {
                        RoundButton(s.t, stringResource(R.string.l_mute), { PhoneController.toggleMute(ctx) }, background = Pal.SURFACE_HI) {
                            SvgIcon(Ico.MIC_OFF, s.ic, Pal.WHITE)
                        }
                        HangupButton(s.t, stringResource(R.string.l_hangup)) { PhoneController.end(ctx) }
                    }
                }
                is PhoneCardModel.Outgoing -> {
                    Txt(stringResource(R.string.l_phone_calling), s.fs, color = Pal.SEC)
                    Body(phone.title.ifEmpty { stringResource(R.string.l_phone) }, "") {
                        HangupButton(s.t, stringResource(R.string.l_cancel_call)) { PhoneController.end(ctx) }
                    }
                }
            }
        }
    }
}

/** Имя и подпись слева, кнопки справа. */
@Composable
private fun Body(name: String, sub: String, mono: Boolean = false, buttons: @Composable () -> Unit) {
    val m = LocalMetrics.current
    val s = m.spec
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(s.g))) {
        Column(Modifier.weight(1f)) {
            Txt(name, s.f1, weight = FontWeight.Bold)
            if (sub.isNotEmpty()) Txt(sub, s.fs, color = Pal.SEC, weight = if (mono) FontWeight.SemiBold else FontWeight.Normal, mono = mono)
        }
        buttons()
    }
}

/** Экран «Вызовы» (Calls.dc.html): вкладки «Недавние» и «Контакты», 2 колонки x 4 строки, страницы стрелками. */
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
    val missedLabel = stringResource(R.string.l_call_missed)
    val incomingLabel = stringResource(R.string.l_call_incoming)
    val outgoingLabel = stringResource(R.string.l_call_outgoing)
    val rows: List<CallsRow> = if (tab == 0) PhoneController.calls.map {
        val label = when (it.type) { CallType.MISSED -> missedLabel; CallType.OUTGOING -> outgoingLabel; else -> incomingLabel }
        CallsRow(it.title, "$label · ${PhoneLogic.whenLabel(System.currentTimeMillis(), it.timeMs, yesterday = yesterday)}",
            if (it.type == CallType.MISSED) Pal.MISSED else Pal.SEC, it.number)
    } else PhoneController.contacts.map { CallsRow(it.name.ifEmpty { it.number }, it.number, Pal.SEC, it.number) }
    val perPage = 8
    val pages = Pager.pageCount(rows.size, perPage)
    val current = Pager.clamp(page, rows.size, perPage)
    val shown = Pager.slice(rows, current, perPage)
    Column(
        Modifier.fillMaxSize().padding(start = m.dp(s.g), top = m.dp(s.m), end = m.dp(s.m), bottom = m.dp(s.m)),
        verticalArrangement = Arrangement.spacedBy(m.dp(s.g)),
    ) {
        PagerHeader(current, pages, onPage = { page = it }, leading = {
            RoundButton(s.t, stringResource(R.string.l_home), { LauncherActions.goHome() }, background = Pal.SURFACE_HI) {
                SvgIcon(Ico.CHEV_L, s.ic, Pal.WHITE, stroke = 2.6f)
            }
            PillButton(stringResource(R.string.l_recent), { tab = 0; page = 0 }, filled = tab == 0)
            PillButton(stringResource(R.string.l_contacts), { tab = 1; page = 0 }, filled = tab == 1)
        })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Txt(
                        stringResource(when {
                            !permitted -> R.string.l_calls_no_permission
                            tab == 0 -> R.string.l_calls_empty
                            else -> R.string.l_contacts_empty
                        }), s.f1, color = Pal.SEC, maxLines = 3, align = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(m.dp(s.g))) {
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

private data class CallsRow(val title: String, val sub: String, val subColor: Color, val number: String)

@Composable
private fun CallsRowCell(row: CallsRow) {
    val m = LocalMetrics.current
    val s = m.spec
    Row(
        Modifier.fillMaxSize().clip(RoundedCornerShape(m.dp(s.radius))).background(Pal.SURFACE)
            .padding(start = m.dp(s.g), end = m.dp(s.g8)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(s.g)),
    ) {
        Column(Modifier.weight(1f)) {
            Txt(row.title, s.f1, weight = FontWeight.Bold)
            Txt(row.sub, s.fs, color = row.subColor)
        }
        CallButton(s.t, stringResource(R.string.l_call)) { LauncherActions.callBack(row.number) }
    }
}
