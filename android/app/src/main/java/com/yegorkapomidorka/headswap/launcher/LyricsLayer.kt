package com.yegorkapomidorka.headswap.launcher

import android.graphics.LinearGradient
import com.yegorkapomidorka.headswap.R
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.Log
import com.yegorkapomidorka.headswap.AppLog
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val TAG = "HeadSwapLauncher"

/** 斜切 8°：字向右上倾，底边向左收。改符号方向即翻。 */
private const val SKEW_K = -0.1405f

/** 字到屏幕上下的最小距离：3mm ≈ 屏高 2.64%（1280×720 上 19px）。 */
private const val EDGE_V = 0.0264f

/** 字到屏幕左右的最小距离：10mm ≈ 屏宽 5%（1280×720 上 64px）。 */
private const val EDGE_H = 0.05f

/** 行距固定 1.3 个字号：上下两句的间距不做成可调项。 */
private const val FIXED_GAP_EM = 1.3f

/** Единая точка取词: сначала готовый текст от接收器, иначе TITLE. */
/** artist 可能是「歌名-歌手」（车载歌词模式），取前半段作为这首歌的稳定标识。 */
private fun carSongName(artist: String): String {
    val a = artist.trim()
    for (sep in listOf('-', '－', '–')) {
        val i = a.lastIndexOf(sep)
        if (i > 0 && i < a.length - 1) return a.substring(0, i).trim()
    }
    return a
}

private fun lyricTextOf(s: MusicState): String = s.lyrics.trim()

/** 上一次被拒收的原文，只用来压日志（同一句不重复刷）。 */
private var lastRejectedText: String = ""


/**
 * 判断这段文本是不是"歌手名"而不是歌词。
 * 只做严格比对（去空格、忽略大小写），不做模糊匹配——宁可漏掉，也不能误杀真歌词。
 * 覆盖三种写法：`小咪`、`小咪-我走后`（车载）、`A/B` 或 `A、B`（多人合唱）。
 */
private fun looksLikeArtistName(text: String): Boolean {
    val artist = MusicController.state.artist.trim()
    if (artist.isEmpty()) return false
    val t = text.replace(" ", "").lowercase()
    if (t.isEmpty()) return false
    val a = artist.replace(" ", "").lowercase()
    if (t == a) return true
    // 车载模式 `歌名-歌手`：歌手名单独出现时也要认出来
    carPartsOf(artist)?.let { (song, who) ->
        if (t == who.replace(" ", "").lowercase() || t == song.replace(" ", "").lowercase()) return true
    }
    // `A/B`、`A、B`、`A&B` 这类组合歌手，任一位单独出现都算
    for (sep in listOf('/', '、', '&', ',')) {
        if (a.contains(sep)) {
            val parts = a.split(sep).map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.any { it == t }) return true
        }
    }
    return false
}

private fun carPartsOf(artist: String): Pair<String, String>? {
    val a = artist.trim()
    for (sep in listOf('-', '－', '–')) {
        val i = a.lastIndexOf(sep)
        if (i > 0 && i < a.length - 1) {
            val song = a.substring(0, i).trim()
            val who = a.substring(i + 1).trim()
            if (song.isNotEmpty() && who.isNotEmpty()) return song to who
        }
    }
    return null
}

/** 时间戳残片：00:12 / 00:12.34 / [00:12.34] 之类，画布永远不该出现。 */
private val TIMED_TOKEN = Regex("""\[?\d{1,2}:\d{2}(?:[.:]\d{1,3})?]?""")

/** 解析播放器自己甩过来的整首 LRC（带 [mm:ss] 时间轴），返回「毫秒 → 文本」。 */
private fun parseLrc(raw: String, artist: String, songName: String = ""): List<Pair<Long, String>> {
    if (raw.isBlank()) return emptyList()
    val stamp = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    if (stamp.findAll(raw).count() < 2) return emptyList()
    val metaTag = Regex("""^\[(ti|ar|al|by|offset|length|re|ve|au|kana|tool):""", RegexOption.IGNORE_CASE)
    val out = ArrayList<Pair<Long, String>>()
    for (line in raw.lineSequence()) {
        val trimmed = line.trim()
        // [ti:歌名] [ar:歌手] 这类头信息不是歌词
        if (metaTag.containsMatchIn(trimmed)) continue
        val tags = stamp.findAll(trimmed).toList()
        if (tags.isEmpty()) continue
        for ((i, m) in tags.withIndex()) {
            val mm = m.groupValues[1].toIntOrNull() ?: continue
            val ss = m.groupValues[2].toIntOrNull() ?: continue
            val fracRaw = m.groupValues[3]
            val frac = if (fracRaw.isBlank()) 0L else fracRaw.padEnd(3, '0').toLongOrNull() ?: 0L
            val from = m.range.last + 1
            val to = if (i + 1 < tags.size) tags[i + 1].range.first else trimmed.length
            if (to <= from) continue
            val txt = trimmed.substring(from, to).trim()
            if (txt.isEmpty()) continue
            out.add((mm * 60_000L + ss * 1_000L + frac) to txt)
        }
    }
    // 「歌名 - 歌手」这种标题行、版权声明行，不是歌词本身
    val names = listOfNotNull(carSongName(artist), songName.trim().takeIf { it.isNotEmpty() })
    out.removeAll { (_, txt) ->
        val t = txt.trim()
        txt.contains("未经授权") || txt.contains("不得翻唱") || txt.contains("翻唱翻录") ||
            (t.contains(" - ") && names.any { n -> t.startsWith(n) })
    }

    val sorted = out.sortedBy { it.first }
    // 换歌后 QQ 会把上一首的整份 LRC 再发一次：t=0 的标题行对不上当前歌名，就整份作废
    val t0 = sorted.firstOrNull()?.second?.trim().orEmpty()
    if (t0.isNotEmpty() && names.isNotEmpty() && t0.contains(" - ")) {
        val head = t0.substringBefore(" - ").trim()
        if (names.none { it == head || head == it }) {
            AppLog.i("LYRIC lrc stale title=\"" + head.take(24) + "\" expect=\"" + names.joinToString("/").take(24) + "\"")
            return emptyList()
        }
    }
    // 有效性：行数够、时间戳严格递增、开头不晚于 60s
    if (sorted.size < 4) return emptyList()
    var prev = -1L
    for (l in sorted) {
        if (l.first <= prev) return emptyList()
        prev = l.first
    }
    if (sorted.first().first > 60_000L) return emptyList()
    return sorted
}

/** 整首 LRC 的解析缓存：同一首歌唱多久都只解析一次。 */
private val lrcMemo = object : LinkedHashMap<String, List<Pair<Long, String>>>(8, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Pair<Long, String>>>?): Boolean =
        size > 4
}

private fun cachedLrc(raw: String, artist: String, key: String, songName: String = ""): List<Pair<Long, String>> {
    lrcMemo[key]?.let { return it }
    val lines = parseLrc(raw, artist, songName)
    if (lines.isNotEmpty()) lrcMemo[key] = lines
    return lines
}

/** 制作信息行、以及「歌名 - 歌手」这种标题行，不算歌词。 */
private fun isCreditOrName(text: String, artist: String): Boolean {
    val t = text.trim()
    val creditPrefix = listOf("词：", "词:", "曲：", "曲:", "编曲：", "编曲:", "演唱：", "演唱:",
        "作词", "作曲", "编曲", "制作人", "监制", "出品", "混音", "录音", "和声", "吉他", "贝斯", "弦乐")
    if (creditPrefix.any { t.startsWith(it) }) return true
    if (t.length > 40) return false
    for (sep in listOf(" - ", " — ", "-", "—")) {
        val i = t.indexOf(sep)
        if (i <= 0) continue
        val l = t.substring(0, i).trim()
        val r = t.substring(i + sep.length).trim()
        if (l.isEmpty() || r.isEmpty()) continue
        val a = artist.trim()
        val tokens = a.split('/', '、', '&', ',').map { it.trim() }.filter { it.isNotEmpty() }
        if (a.isNotEmpty() && (l == a || r == a)) return true
        if (tokens.any { it == l || it == r }) return true
    }
    return false
}

/** 整首 LRC 落盘缓存：按「播放器+歌名+时长」存，同曲复用。 */
private fun lrcCacheDir(ctx: android.content.Context): java.io.File =
    java.io.File(ctx.filesDir, "lrc").apply { mkdirs() }

private fun lrcCacheFile(ctx: android.content.Context, key: String): java.io.File {
    val md5 = java.security.MessageDigest.getInstance("MD5")
        .digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
    return java.io.File(lrcCacheDir(ctx), "$md5.lrc")
}

private fun readLrcCache(ctx: android.content.Context, key: String): String = runCatching {
    val f = lrcCacheFile(ctx, key)
    if (f.exists() && f.length() in 1..400_000L) f.readText() else ""
}.getOrDefault("")

private fun writeLrcCache(ctx: android.content.Context, key: String, raw: String) {
    runCatching {
        lrcCacheFile(ctx, key).writeText(raw)
        val dir = lrcCacheDir(ctx)
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return@runCatching
        files.drop(200).forEach { it.delete() }
    }
}

/** 没有歌词：本会话只出现一个 title 且这么久没动，就判为没有歌词，直接隐身。 */
private const val SILENT_MS = 10_000L

/** 播放器没开车载歌词时，TITLE 就是歌名，不会变，判得更快一点。 */
private const val SILENT_PLAIN_MS = 5_000L

/** 车载歌词模式：一句能停十几秒，但几十秒不动也说明歌词流断了（暂停、断流、纯音乐段落）。 */
private const val SILENT_CAR_MS = 30_000L

/** 旧句压到这个比例，不消失：两句同时在，最新的一句最亮。 */
private const val DIM = 0.32f

/** 同一句停留到"正常句长的 1.5 倍"，就当又来了一句（副歌重复这种），最多补 3 次。 */
private const val REPEAT_MAX = 3

/** 两句提示：文案在 strings_launcher.xml，随系统语言走。 */

/** TITLE 其实是歌名（播放器没把歌词写进元数据）时，别让歌名冒充歌词。 */
private fun titleIsSongName(title: String, artist: String): Boolean {
    val t = title.trim()
    if (t.isEmpty()) return false
    val a = artist.trim()
    if (a.isEmpty()) return false
    if (a == t) return true
    // 车载歌词模式下播放器把 artist 写成「歌名-歌手」；此时若 TITLE 与歌名相同，说明没有歌词在写
    val dash = a.lastIndexOf('-')
    return dash > 0 && a.substring(0, dash).trim() == t
}

/** 一句歌词：最多同时两条，一条常亮、一条淡出。 */
private class LyricSlot(val text: String, val top: Boolean, val bornAt: Long) {
    val alpha = Animatable(0f)
}

/** 排好版的一行：字号可能被缩过，文本可能被截过。 */
private class Line(
    val text: String,
    val sizePx: Float,
    val width: Float,
    val ascent: Float,
    val descent: Float,
    val left: Float,
    val baseline: Float,
    val alpha: () -> Float,
) {
    val top: Float get() = baseline + ascent
    val bottom: Float get() = baseline + descent
}

/**
 * Текст песни на рабочем столе: две строки по диагонали, стык в центре, рамки нет.
 * Пока открыт экран «Настройки › Музыка», строки можно двигать пальцем (если не стоит замок).
 * Рисуется обычным android.graphics.Canvas: нужен skew, а Compose его не умеет.
 */
@Composable
fun LyricsLayer(modifier: Modifier = Modifier) {
    val model = LauncherModel
    if (!model.lyricsOn) return

    val ctx = model.context
    val preferred = model.localPlayer ?: model.onlinePlayer
    val scope = rememberCoroutineScope()
    val slots = remember { mutableStateListOf<LyricSlot>() }
    var seq by remember { mutableStateOf(0L) }
    var lastPush by remember { mutableStateOf(0L) }
    // 换句节奏：用来判断"同一句停留过久"是不是又来了一句
    val gaps = remember { mutableListOf<Long>() }
    var lastLineAt by remember { mutableStateOf(0L) }
    var repeats by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val rainbowColors = remember {
        IntArray(8) { i ->
            android.graphics.Color.HSVToColor(floatArrayOf((i * 360f / 8f) % 360f, 1f, 1f))
        }
    }
    val paint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.LEFT
        }
    }

    fun clearAll() {
        // 直接丢掉槽位即可：淡出协程持有的是旧对象，列表里没有就不画
        slots.clear()
    }

    fun medianGap(): Long {
        if (gaps.size < 3) return 0L
        val s = gaps.sorted()
        return s[s.size / 2]
    }

    fun push(raw: String, force: Boolean) {
        val text = raw.trim()
        if (text.isEmpty()) { clearAll(); return }
        if (looksLikeArtistName(text)) return                     // 歌手名不是歌词

        // 最后一道闸：任何带时间戳/方括号的内容都不许进画布（QQ 会把这些原样甩过来）
        if (text.contains('[') || text.contains(']') || TIMED_TOKEN.containsMatchIn(text)) {
            if (text != lastRejectedText) {
                lastRejectedText = text
                AppLog.i("LYRIC reject timed text=\"" + text.take(50) + "\"")
            }
            return
        }
        val now = SystemClock.uptimeMillis()
        val newest = slots.maxByOrNull { it.bornAt }
        // 同一句 2.5s 内又报一次：不是新句，多半是播放器重复写同一份元数据（重绘会闪）
        if (newest?.text == text && now - newest.bornAt < 1_200L) return
        if (now - lastPush < 120L) return                   // 一次换句写两遍的抖动
        if (!force) {
            val gap = now - lastLineAt
            if (lastLineAt > 0L && gap in 200L..12_000L) {
                gaps.add(gap)
                while (gaps.size > 8) gaps.removeAt(0)
            }
            lastLineAt = now
            repeats = 0
        }
        lastPush = now
        seq += 1L
        val top = seq % 2L == 1L                          // 1、3、5 左上；2、4、6 右下
        slots.removeAll { it.top == top }                  // 同槽旧条直接消失（第三句顶掉第一句）
        val slot = LyricSlot(text, top, now)
        slots.add(slot)
        scope.launch { slot.alpha.animateTo(model.lyricsAlpha, tween(120, easing = LinearOutSlowInEasing)) }
        // 另一侧那句只是压暗，不淡出：两句同屏，最新最亮
        slots.firstOrNull { it !== slot }?.let { old ->
            // 不消失：从全亮开始肉眼可见地变暗，第三句上来时直接顶掉（不淡出）
            scope.launch {
                old.alpha.animateTo(model.lyricsAlpha * DIM, tween(1600, easing = LinearEasing))
            }
        }
        AppLog.i("LYRIC show=\"$text\"")
    }

    // 兜底轮询：卡片不在屏幕上时 MusicState 也要新鲜；顺手补"同句重复"
    LaunchedEffect(preferred) {
        while (true) {
            withContext(Dispatchers.Default) { MusicController.refresh(ctx, preferred) }
            delay(500)
            val s = MusicController.state
            val live = slots.maxByOrNull { it.bornAt }
            if (!s.hasSession || !s.playing || live == null) continue
            if (lyricTextOf(s) != live.text) continue
            if (repeats >= REPEAT_MAX) continue
            val period = medianGap()
            if (period <= 0L) continue                      // 节奏还没建立，不乱补
            val now = SystemClock.uptimeMillis()
            // 同一句顶掉一个半句长的时间，几乎只能是"又唱了一遍同一句"
            if (now - live.bornAt >= period + period / 2 && now - lastPush >= period) {
                push(live.text, force = true)
                repeats += 1
                AppLog.i("LYRIC repeat=\"${live.text}\" n=$repeats period=$period")
            }
        }
    }

    // 事件：同词重复也算新的一句
    LaunchedEffect(Unit) {
        LyricTap.events.collect {
            withContext(Dispatchers.Default) { MusicController.refresh(ctx, preferred) }
            val s = MusicController.state
            val ev = lyricTextOf(s)
            if (ev.isNotBlank() && !titleIsSongName(ev, s.artist)) push(ev, force = true)
        }
    }

    val hintTop = stringResource(R.string.l_s_lyrics_hint1)
    val hintBottom = stringResource(R.string.l_s_lyrics_hint2)
    val st = MusicController.state
    var seen by remember { mutableStateOf(emptySet<String>()) }
    var lrcIdx by remember { mutableStateOf(-1) }
    var diskLrc by remember { mutableStateOf("") }
    var silent by remember { mutableStateOf(false) }

    // 换歌：按「播放器 + 歌手 + 时长」认歌（TITLE 里可能是歌词，不能用它认）
    val trackKey = if (st.durationMs > 0) "${st.pkg}|${st.durationMs / 1000L}" else "${st.pkg}|${st.title}"
    LaunchedEffect(trackKey) {
        // 媒体会话在切歌/焦点切换的瞬间会短暂丢字段（pkg 空、时长 0），
        // 停 400ms 再看：键还在变就不清，避免 0.2 秒内连清两次、从头再来。
        delay(400)
        if (MusicController.state.pkg.orEmpty() + "|" + (MusicController.state.durationMs / 1000L) != trackKey &&
            MusicController.state.durationMs > 0
        ) return@LaunchedEffect
        // 换歌清空上一首的残留：新歌第一句重新从左上开始
        clearAll()
        seq = 0L
        repeats = 0
        lastLineAt = 0L
        gaps.clear()
        seen = emptySet()
        silent = false
        lrcIdx = -1
        diskLrc = readLrcCache(ctx, "${st.pkg}|${carSongName(st.artist)}|${st.durationMs / 1000L}")
        if (diskLrc.isNotBlank()) AppLog.i("LYRIC disk cache hit ${diskLrc.length} bytes", false)
        AppLog.i("LYRIC track changed -> cleared")
    }

    // 播到结尾就清空：这是唯一可靠的"这首歌完了"，比数着秒数猜可靠
    LaunchedEffect(st.pkg, st.playing, st.positionMs, st.atMs, st.durationMs) {
        if (!st.playing || st.durationMs <= 0L) return@LaunchedEffect
        while (true) {
            val pos = st.positionMs + (SystemClock.elapsedRealtime() - st.atMs)
            if (pos >= st.durationMs - 1_500L) {
                clearAll()
                AppLog.i("LYRIC ended pos=$pos dur=${st.durationMs} -> cleared")
                break
            }
            delay(1_000)
        }
    }

    // 整首带时间轴的歌词：按播放进度推句，不再靠"元数据变没变"
    LaunchedEffect(st.lyrics, st.pkg, st.durationMs) {
        val lrcRaw = st.lyrics.trim().ifBlank { diskLrc }
        val lrcKey = "${st.pkg}|${st.durationMs}|${lrcRaw.hashCode()}"
        val lines = cachedLrc(lrcRaw, st.artist, lrcKey, st.title)
        if (st.lyrics.trim().isNotEmpty() && lines.size >= 5) {
            writeLrcCache(ctx, "${st.pkg}|${carSongName(st.artist)}|${st.durationMs / 1000L}", st.lyrics)
        }
        if (lines.isEmpty()) {
            // 看起来是整首 LRC 但解析不出有效时间轴：撤掉 rank1，让缓存的单句立刻接管
            if (st.lyrics.contains("\n") || st.lyrics.contains('[')) {
                AppLog.i("LYRIC lrc invalid raw len=${st.lyrics.length} src=${st.lyricSource}")
                LyricsReceiver.rejectFullLrc()
            }
            return@LaunchedEffect
        }
        AppLog.i("LYRIC lrc ready lines=${lines.size} src=${st.lyricSource}")
        while (true) {
            val s2 = MusicController.state
            if (!s2.playing || s2.pkg != st.pkg) { delay(500); continue }
            val pos = s2.positionMs + (SystemClock.elapsedRealtime() - s2.atMs)
            val idx = lines.indexOfLast { it.first <= pos }
            if (idx >= 0 && idx != lrcIdx) {
                lrcIdx = idx
                push(lines[idx].second, force = true)
            }
            delay(150)
        }
    }

    LaunchedEffect(st.title, st.lyrics, st.artist, st.pkg, st.hasSession, st.playing) {
        silent = false
        if (cachedLrc(st.lyrics, st.artist, "${st.pkg}|${st.durationMs}|${st.lyrics.hashCode()}").isNotEmpty()) return@LaunchedEffect
        val t = lyricTextOf(st)
        if (!st.hasSession || t.isEmpty() || !st.playing) {
            // 播放器不在播放（暂停/播完/切走）：残留一律清掉
            clearAll()
            return@LaunchedEffect
        }
        if (st.lyrics.isBlank() && titleIsSongName(t, st.artist)) {
            // 播放器没开车载歌词：TITLE 就是歌名，不能当歌词显示
            clearAll()
            silent = true
            AppLog.i("LYRIC hidden: title is song name \"$t\"")
            return@LaunchedEffect
        }
        push(t, force = false)
        if (seen.contains(t)) return@LaunchedEffect
        seen = seen + t
        // «歌手-歌名» = 车载歌词模式，一句能停十几秒；其余多半是歌名。两种都设上限：
        // 几十秒一个字都不变，说明歌词流断了，字幕不能一直躺着
        // 车载歌词模式：播放器唱到哪句给哪句，中间停顿十几秒是正常的，不能算"没歌词"
        if (st.lyricSource != null || st.artist.contains('-')) return@LaunchedEffect
        val wait = SILENT_PLAIN_MS
        if (seen.size <= 1) {
            delay(wait)
            val still = MusicController.state
            if (seen.size <= 1 && lyricTextOf(still) == t && still.playing) {
                silent = true
                clearAll()
                AppLog.i("LYRIC hidden: stale ${wait}ms \"$t\"")
            }
        }
    }

    // 在设置里拖透明度，正在显示的这句要跟着变
    LaunchedEffect(model.lyricsAlpha) {
        val newest = slots.maxByOrNull { it.bornAt }
        slots.forEach { it.alpha.snapTo(if (it === newest) model.lyricsAlpha else model.lyricsAlpha * DIM) }
    }

    val editing = model.musicPageOpen
    val hasText = slots.isNotEmpty()
    if ((!hasText || silent) && !editing) return           // 隐身：整层不存在

    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val fs = h * 0.0625f * model.lyricsScale
        // 四边都允许贴边：只留 1px 防字形被裁；要留白就自己拖
        val edgeV = 1f
        val edgeH = 1f
        val gap = fs * FIXED_GAP_EM
        val ov = fs * model.lyricsOvEm
        val seamX = w * model.lyricsX
        val seamY = h * model.lyricsY
        // 单句最宽 = 屏宽减去两侧各 10mm
        val maxW = (w - 2f * w * EDGE_H).coerceAtLeast(1f)

        fun measure(text: String, sizePx: Float): Triple<String, Float, Float> {
            paint.textSize = sizePx
            var s = sizePx
            var t = text
            var tw = paint.measureText(text)
            while (tw > maxW && s > sizePx * 0.65f) {      // 先缩字，最多缩到 0.65
                s -= sizePx * 0.04f
                paint.textSize = s
                tw = paint.measureText(text)
            }
            if (tw > maxW) {                                // 还超就逐字截断加省略号
                val dots = paint.measureText("…")
                var end = text.length
                while (end > 1 && paint.measureText(text, 0, end) + dots > maxW) end--
                t = text.substring(0, end) + "…"
                tw = paint.measureText(t)
            }
            return Triple(t, s, tw)
        }

        fun line(raw: String, sizePx: Float, isTop: Boolean, alpha: () -> Float): Line {
            val (t, s, tw) = measure(raw, sizePx)
            paint.textSize = s
            val fm = paint.fontMetrics
            val baseline = seamY + if (isTop) -gap / 2f else gap / 2f
            val left = if (isTop) seamX + ov / 2f - tw else seamX - ov / 2f
            return Line(t, s, tw, fm.ascent, fm.descent, left, baseline, alpha)
        }

        val lines = mutableListOf<Line>()
        if (hasText) {
            slots.forEach { slot ->
                lines += line(slot.text, fs, slot.top) { slot.alpha.value }
            }
        } else {
            // 编辑态提示：不重叠、各占一侧
            val hintFs = fs * 0.9f
            val a = line(hintTop, hintFs, true) { model.lyricsAlpha * 0.75f }
            val b = line(hintBottom, hintFs, false) { model.lyricsAlpha * 0.75f }
            lines += a
            lines += b
        }

        val boxL = lines.minOf { it.left }
        val boxT = lines.minOf { it.top }
        val boxR = lines.maxOf { it.left + it.width }
        val boxB = lines.maxOf { it.bottom }

        // 硬边界：字的边缘到屏幕上/下 3mm、左/右 10mm。越界就整体回推，不缩字
        val minSeamX = edgeH + (seamX - boxL)
        val maxSeamX = (w - edgeH) - (boxR - seamX)
        val minSeamY = edgeV + (seamY - boxT)
        val maxSeamY = (h - edgeV) - (boxB - seamY)
        val shiftX = if (maxSeamX >= minSeamX) seamX.coerceIn(minSeamX, maxSeamX) - seamX else 0f
        val shiftY = if (maxSeamY >= minSeamY) seamY.coerceIn(minSeamY, maxSeamY) - seamY else 0f

        val rgb = android.graphics.Color.HSVToColor(
            floatArrayOf(
                model.lyricsH.coerceIn(0f, 360f),
                model.lyricsS.coerceIn(0f, 1f),
                model.lyricsV.coerceIn(0f, 1f),
            )
        )

        Canvas(Modifier.fillMaxSize()) {
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                lines.forEach { ln ->
                    val a = ln.alpha().coerceIn(0f, 1f)
                    if (a <= 0.01f) return@forEach
                    nc.save()
                    nc.translate(ln.left + shiftX, ln.baseline + shiftY)
                    nc.skew(SKEW_K, 0f)
                    paint.textSize = ln.sizePx
                    if (model.lyricsRainbow) {
                        // 五彩斑斓：整句字身铺一条彩虹，方向 0 横 / 1 竖 / 2 斜
                        val y0 = if (ln.ascent < 0f) ln.ascent else -ln.sizePx
                        val x1 = ln.width.ifZero(ln.sizePx)
                        val from = when (model.lyricsDir) {
                            1 -> floatArrayOf(0f, y0, 0f, ln.descent)
                            2 -> floatArrayOf(0f, y0, x1, ln.descent)
                            else -> floatArrayOf(0f, 0f, x1, 0f)
                        }
                        paint.shader = LinearGradient(from[0], from[1], from[2], from[3], rainbowColors, null, Shader.TileMode.CLAMP)
                    } else {
                        paint.shader = null
                        paint.color = rgb
                    }
                    paint.alpha = (a * 255f).roundToInt()
                    nc.drawText(ln.text, 0f, 0f, paint)
                    nc.restore()
                }
            }
            if (editing) dashedRect(boxL + shiftX, boxT + shiftY, boxR + shiftX, boxB + shiftY)
        }

        // 未锁定时可拖：只在文字框内响应（外扩 4mm），其余区域穿透。
        // 设置页里不挂手势——那里改用滑杆，手势会挡住下面的卡片
        if (!model.lyricsLocked) {
            val pad = 26f
            val bw = (boxR - boxL + pad * 2).coerceAtLeast(1f)
            val bh = (boxB - boxT + pad * 2).coerceAtLeast(1f)
            val leftMargin = seamX - boxL + pad
            val rightMargin = boxR - seamX + pad
            val topMargin = seamY - boxT + pad
            val bottomMargin = boxB - seamY + pad
            Box(
                Modifier
                    .offset { IntOffset((boxL + shiftX - pad).roundToInt(), (boxT + shiftY - pad).roundToInt()) }
                    .size(with(density) { bw.toDp() }, with(density) { bh.toDp() })
                    .pointerInput(Unit) {
                        detectDragGestures { change, delta ->
                            change.consume()
                            val nx = (model.lyricsX + delta.x / w).coerceIn(
                                (edgeH + leftMargin) / w,
                                ((w - edgeH - rightMargin) / w).coerceAtLeast((edgeH + leftMargin) / w),
                            )
                            val ny = (model.lyricsY + delta.y / h).coerceIn(
                                (edgeV + topMargin) / h,
                                ((h - edgeV - bottomMargin) / h).coerceAtLeast((edgeV + topMargin) / h),
                            )
                            model.moveLyrics(nx, ny)
                        }
                    },
            )
        }
    }
}

private fun Float.ifZero(fallback: Float): Float = if (this <= 0f) fallback else this

/** 编辑态虚线框：常态不画，字边就是框边。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.dashedRect(
    left: Float, top: Float, right: Float, bottom: Float,
) {
    val c = Color.White.copy(alpha = 0.35f)
    val dash = 12f
    val gapPx = 8f
    var x = left
    while (x < right) {
        drawLine(c, Offset(x, top), Offset(minOf(x + dash, right), top), 1.5f)
        drawLine(c, Offset(x, bottom), Offset(minOf(x + dash, right), bottom), 1.5f)
        x += dash + gapPx
    }
    var y = top
    while (y < bottom) {
        drawLine(c, Offset(left, y), Offset(left, minOf(y + dash, bottom)), 1.5f)
        drawLine(c, Offset(right, y), Offset(right, minOf(y + dash, bottom)), 1.5f)
        y += dash + gapPx
    }
}
