package com.yegorkapomidorka.headswap.launcher

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.yegorkapomidorka.headswap.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val MusicBase = Color(0xFF211C10)
/** Стеклянная подложка чипа плееров (и кнопки «Вид карточки» на карте в ленте): rgba(14,20,26,.33), обводка белым .16. */
val GlassBg = Color(14, 20, 26, 84)
val GlassBorder = Color(1f, 1f, 1f, 0.16f)

/** Фон без обложки (макет v2): диагональный градиент 150deg, поверх золотое и красно-оранжевое пятна. */
private fun Modifier.musicBackground(): Modifier = drawBehind {
    val w = size.width
    val h = size.height
    drawRect(MusicBase)
    val rad = Math.toRadians(150.0)
    val dx = sin(rad).toFloat()
    val dy = (-cos(rad)).toFloat()
    val len = abs(w * dx) + abs(h * dy)
    val c = Offset(w / 2, h / 2)
    drawRect(Brush.linearGradient(
        0f to Color(0xFF6B5420), 1f to Color(0xFF3A2440),
        start = Offset(c.x - dx * len / 2, c.y - dy * len / 2), end = Offset(c.x + dx * len / 2, c.y + dy * len / 2)))
    // CSS circle: радиус до дальнего угла, пятно гаснет на stop этого радиуса
    fun spot(fx: Float, fy: Float, color: Color, alpha: Float, stop: Float) {
        val cx = w * fx
        val cy = h * fy
        val r = hypot(max(cx, w - cx), max(cy, h - cy))
        drawRect(Brush.radialGradient(
            0f to color.copy(alpha = alpha), stop to color.copy(alpha = 0f), 1f to color.copy(alpha = 0f),
            center = Offset(cx, cy), radius = r))
    }
    spot(0.28f, 0.22f, Color(0xFFE8C65A), 0.9f, 0.42f)
    spot(0.82f, 0.68f, Color(0xFFC45434), 0.85f, 0.48f)
}

/**
 * Карточка музыки: фон или обложка, справа сверху чип плееров, внизу название и исполнитель,
 * под ними prev / play-pause / next. Без прогресса.
 */
@Composable
fun MusicCard(modifier: Modifier) {
    val model = LauncherModel
    val m = LocalMetrics.current
    val s = m.spec
    val ctx = model.context
    LaunchedEffect(model.musicPlayer) {
        while (true) {
            withContext(Dispatchers.Default) { MusicController.refresh(ctx, model.musicPlayer) }
            delay(1000)
        }
    }
    val st = MusicController.state
    val player = st.pkg ?: model.musicPlayer
    // играет онлайн-плеер - он и становится вторым слотом чипа
    LaunchedEffect(st.pkg) { model.noteOnlinePlayer(st.pkg) }
    CardBox(modifier, background = Color.Transparent) {
        Box(Modifier.fillMaxSize().musicBackground())
        if (st.art == null) {
            // нота лежит у золотого пятна фона, а оно нарисовано физически: Ltr, чтобы в RTL нота не уезжала от него
            Ltr {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    SvgIcon(Ico.MUSIC, s.cover, Pal.WHITE.copy(alpha = 0.3f), stroke = 1.2f,
                        modifier = Modifier.offset(maxWidth * 0.32f, maxHeight * 0.22f))
                }
            }
        }
        st.art?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        // затемнение под текст: сверху .55, к 38% .25, внизу .82
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color(8, 11, 15, 140), 0.38f to Color(8, 11, 15, 64), 1f to Color(8, 11, 15, 209))))
        // тап по телу открывает приложение плеера на весь экран справа от рейки
        Pressable(Modifier.fillMaxSize(), description = stringResource(R.string.l_music_players), onTap = { LauncherActions.openMusicApp() }) {}
        PlayerChipColumn(Modifier.align(Alignment.TopEnd).padding(end = m.dp(s.g8), top = m.dp(s.g8)))
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = m.dp(s.g), end = m.dp(s.g), bottom = m.dp(s.titleB)),
            verticalArrangement = Arrangement.spacedBy(m.dp(s.g4)),
        ) {
            when {
                !st.access -> Txt(stringResource(R.string.l_music_no_access), s.fs, color = Pal.SEC2, maxLines = 4)
                st.hasSession -> {
                    Txt(st.title, s.f1, weight = FontWeight.Bold)
                    Txt(st.artist, s.f2, color = Color(0xFFD9DEF5))
                }
                else -> {
                    Txt(stringResource(R.string.l_music_idle), s.f1, weight = FontWeight.Bold)
                    player?.let { Txt(LauncherModel.packageLabel(it), s.f2, color = Color(0xFFD9DEF5)) }
                }
            }
        }
        // prev / play / next всегда слева направо, как значки плеера: ряд создаётся внутри Ltr
        Ltr {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = m.dp(s.g8)).height(m.dp(s.t)),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                if (st.hasSession) {
                    RoundButton(s.tp, stringResource(R.string.l_prev), onTap = { MusicController.previous() }) {
                        SvgIcon(Ico.PREV, s.tpIc, Pal.WHITE, fill = true)
                    }
                }
                if (st.hasSession || (st.access && player != null)) {
                    RoundButton(s.t, stringResource(if (st.playing) R.string.l_pause else R.string.l_play),
                        onTap = { MusicController.playPause(ctx, player) }, background = Color(0x29FFFFFF)) {
                        SvgIcon(if (st.playing) Ico.PAUSE else Ico.PLAY, s.ic, Pal.WHITE, fill = true)
                    }
                }
                if (st.hasSession) {
                    RoundButton(s.tp, stringResource(R.string.l_next), onTap = { MusicController.next() }) {
                        SvgIcon(Ico.NEXT, s.tpIc, Pal.WHITE, fill = true)
                    }
                }
            }
        }
    }
}

/**
 * Чип плееров вертикально в правом верхнем углу: свой плеер («нота») и последний онлайн-плеер. Выбранный слот -
 * полупрозрачное золото, остальные - белый на 55%. Долгий тап по «ноте» - список всех плееров.
 */
@Composable
private fun PlayerChipColumn(modifier: Modifier) {
    val model = LauncherModel
    val m = LocalMetrics.current
    val s = m.spec
    val accent = LocalAccent.current
    val slots = PlayerChip.slots(model.installedPackages, model.musicPlayer, model.lastOnlinePlayer, model.localPlayer, model.onlinePlayer)
    val shape = RoundedCornerShape(m.dp(s.pcB))
    // backdrop-blur макета в Compose не повторить (blur размывает своё содержимое, а не фон) - только стекло и обводка
    Column(
        modifier.clip(shape).background(GlassBg).border(m.dp(1), GlassBorder, shape).padding(m.dp(s.b4)),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(m.dp(s.b4)),
    ) {
        for (slot in slots) {
            val fg = Pal.WHITE.copy(alpha = if (slot.selected) 0.9f else 0.55f)
            val bg = if (slot.selected) accent.copy(alpha = 0.32f) else Color.Transparent
            val desc = when (slot.kind) {
                PlayerChip.Kind.PHONE -> stringResource(R.string.l_player_mine)
                PlayerChip.Kind.YANDEX -> stringResource(R.string.l_player_yandex)
                PlayerChip.Kind.YT_MUSIC -> stringResource(R.string.l_player_ytm)
                PlayerChip.Kind.VK -> stringResource(R.string.l_player_vk)
                PlayerChip.Kind.APP -> LauncherModel.packageLabel(slot.pkg!!)
            }
            RoundButton(
                s.pcB, desc, background = bg,
                onTap = { if (slot.pkg == null) LauncherActions.choosePhonePlayer() else LauncherActions.choosePlayer(slot.pkg) },
                onLong = if (slot.kind == PlayerChip.Kind.PHONE) ({ LauncherActions.openPlayers() }) else null,
            ) {
                // иконка выбранного приложения; «любой плеер» без закрепления - последний свой, что выбирали или слушали
                val iconPkg = slot.pkg ?: model.lastLocalPlayer
                val icon = iconPkg?.let { rememberPackageIcon(it) }
                if (icon != null) {
                    Image(icon, null, Modifier.size(m.dp(s.pcApp)).clip(CircleShape).alpha(if (slot.selected) 1f else 0.55f))
                } else when (slot.kind) {
                    PlayerChip.Kind.PHONE, PlayerChip.Kind.APP -> SvgIcon(Ico.MUSIC, s.pcIc, fg, stroke = 2.4f)
                    PlayerChip.Kind.YANDEX -> Txt("Я", s.pcIc, color = fg, weight = FontWeight.Bold)
                    PlayerChip.Kind.YT_MUSIC -> VectorIcon(VIco.YT_MUSIC, 24f, s.pcIc, fg)
                    PlayerChip.Kind.VK -> Txt("VK", s.pcVk, color = fg, weight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun rememberPackageIcon(pkg: String): ImageBitmap? {
    val icon by produceState<ImageBitmap?>(null, pkg) {
        value = withContext(Dispatchers.IO) { LauncherModel.loadPackageIcon(pkg) }
    }
    return icon
}

/** Список медиаприложений: тап делает плеер текущим и запускает его; выбор запоминается. */
@Composable
fun PlayersScreen() {
    val model = LauncherModel
    var page by remember { mutableIntStateOf(0) }
    val f1 = LocalMetrics.current.spec.f1
    val players = remember { MusicController.players(model.context) }
    PagedApps(
        players, page, onPage = { page = it },
        leading = {
            Txt(stringResource(if (players.isEmpty()) R.string.l_music_no_players else R.string.l_music_choose), f1, color = Pal.SEC2)
        },
    ) { pkg, iconPx -> PackageTile(pkg, iconPx, selected = pkg == model.musicPlayer, onTap = { LauncherActions.choosePlayer(pkg) }) }
}

@Composable
private fun PackageTile(pkg: String, iconPx: Int, selected: Boolean, onTap: () -> Unit) {
    val m = LocalMetrics.current
    val s = m.spec
    val label = LauncherModel.packageLabel(pkg)
    val icon = rememberPackageIcon(pkg)
    Pressable(
        Modifier.clip(RoundedCornerShape(m.dp(s.radius)))
            .then(if (selected) Modifier.border(m.dp(s.b4), LocalAccent.current, RoundedCornerShape(m.dp(s.radius))) else Modifier),
        description = label, onTap = onTap,
    ) { pressed ->
        if (pressed) Box(Modifier.matchParentSize().background(Pal.SURFACE_PRESSED))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(m.dp(s.g4)),
            modifier = Modifier.padding(m.dp(s.g4))) {
            Box(Modifier.size(m.dp(iconPx)).clip(CircleShape).background(Pal.SURFACE_HI)) {
                if (icon != null) Image(icon, null, Modifier.fillMaxSize())
            }
            Txt(label, s.appLbl, color = Pal.TEXT, weight = FontWeight.Medium, align = TextAlign.Center)
        }
    }
}
