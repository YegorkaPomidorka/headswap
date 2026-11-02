package com.yegorkapomidorka.headswap.launcher

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.yegorkapomidorka.headswap.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val MusicBg = Brush.linearGradient(
    0f to Color(0xFF5C4A1C), 0.6f to Color(0xFF352B14), 1f to Color(0xFF211C10),
    start = androidx.compose.ui.geometry.Offset(0f, 0f), end = androidx.compose.ui.geometry.Offset(300f, 900f),
)

/** Карточка музыки: обложка фоном, название и исполнитель, внизу prev / play-pause / next. Без прогресса. */
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
    CardBox(modifier, background = Color.Transparent) {
        Box(Modifier.fillMaxSize().background(MusicBg))
        st.art?.let {
            Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Color(0x73000000)))
        }
        // тап по телу открывает приложение плеера на весь экран справа от рейки
        Pressable(Modifier.fillMaxSize(), description = stringResource(R.string.l_music_players), onTap = { LauncherActions.openMusicApp() }) {}
        Column(
            Modifier.padding(start = m.dp(s.g), top = m.dp(s.g), end = m.dp(s.g + s.t * 7 / 10 + s.g4)),
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
        // переключатель плееров: маленькая кнопка в углу
        RoundButton(
            s.t * 7 / 10, stringResource(R.string.l_music_players), onTap = { LauncherActions.openPlayers() },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = m.dp(s.g4), end = m.dp(s.g4)),
            background = Color(0x40000000),
        ) { SvgIcon(Ico.MUSIC, s.ic * 7 / 10, Pal.WHITE, stroke = 2f) }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(m.dp(s.t)).background(Color(0x8C0E141A)),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            val like = st.like
            val btn = if (like != null) s.t * 85 / 100 else s.t
            if (st.hasSession) {
                if (like != null) RoundButton(btn, stringResource(R.string.l_like), onTap = { MusicController.like() }) {
                    SvgIcon(Ico.HEART, s.ic, if (like.liked) LocalAccent.current else Pal.WHITE, fill = like.liked, stroke = 2f)
                }
                RoundButton(btn, stringResource(R.string.l_prev), onTap = { MusicController.previous() }) {
                    SvgIcon(Ico.PREV, s.ic, Pal.WHITE, fill = true)
                }
            }
            if (st.hasSession || (st.access && player != null)) {
                RoundButton(btn, stringResource(if (st.playing) R.string.l_pause else R.string.l_play),
                    onTap = { MusicController.playPause(ctx, player) }, background = Color(0x24FFFFFF)) {
                    SvgIcon(if (st.playing) Ico.PAUSE else Ico.PLAY, s.ic, Pal.WHITE, fill = true)
                }
            }
            if (st.hasSession) {
                RoundButton(btn, stringResource(R.string.l_next), onTap = { MusicController.next() }) {
                    SvgIcon(Ico.NEXT, s.ic, Pal.WHITE, fill = true)
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
            PillButton(stringResource(R.string.l_back), { LauncherActions.goHome() }, icon = Ico.CHEV_L, filled = false)
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
            Box(Modifier.size(m.dp(iconPx)).clip(CircleShape).background(Color(0xFF2E3C45))) {
                if (icon != null) Image(icon, null, Modifier.fillMaxSize())
            }
            Txt(label, s.appLbl, color = Pal.TEXT, weight = FontWeight.Medium, align = TextAlign.Center)
        }
    }
}
