package com.yegorkapomidorka.headswap.launcher

import android.app.Notification
import android.media.MediaMetadata
import android.os.Bundle
import android.util.Log
import com.yegorkapomidorka.headswap.AppLog
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * HeadSwap 通用歌词接收器。
 * 不负责歌词 UI、歌词搜索和播放器控制，只收集系统公开的媒体/通知数据。
 */
internal object LyricsReceiver {

    data class LyricsState(
        val text: String = "",
        val packageName: String? = null,
        val source: String? = null,
        val updatedAt: Long = 0L
    )

    var state by mutableStateOf(LyricsState())
        private set

    private data class Sequence(
        var packageName: String? = null,
        var trackKey: String? = null,
        var previous: String = "",
        var pending: String = "",
        var pendingAt: Long = 0L
    )

    private var lastArtist: String = ""
    private var carArtist: String = ""
    private var lastSig: String = ""

    /** 候选池：每个来源各存一份文本，显示层只取「当前最高优先级且有效」的那一份 */
    private data class Cand(val text: String, val rank: Int, val source: String, val at: Long)

    private var lrcCand: Cand? = null      // rank 1：整首带时间轴，粘性持有
    private var lineCand: Cand? = null     // rank 2/3：实时单句（车载逐句 / 普通逐句）
    private var weakCand: Cand? = null     // rank 4/5：通知

    private fun clearCands() {
        lrcCand = null
        lineCand = null
        weakCand = null
    }

    private val mediaSequence = Sequence()
    private val notificationSequence = Sequence()

    private val lyricKeyNames = setOf(
        "lyrics", "lyric", "current_lyric", "currentlyric", "lyric_line",
        "lyricline", "current_line", "currentline", "extra_lyric", "text_lyric"
    )

    /** 由 MusicController.refresh() 在读取 MediaSession 后调用。 */
    @Synchronized
    fun onMediaMetadata(pkg: String, metadata: MediaMetadata?) {
        if (metadata == null) return

        val artist = read(metadata, MediaMetadata.METADATA_KEY_ARTIST)
        val album = read(metadata, MediaMetadata.METADATA_KEY_ALBUM)
        val mediaId = read(metadata, MediaMetadata.METADATA_KEY_MEDIA_ID)
        val duration = runCatching {
            metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        }.getOrDefault(0L)
        val trackKey = listOf(mediaId, album, duration.toString()).joinToString("|")

        if (mediaSequence.packageName != pkg || mediaSequence.trackKey != trackKey) {
            if (mediaSequence.trackKey != trackKey) { carArtist = ""; clearCands() }
            mediaSequence.reset(pkg, trackKey)
            state = LyricsState(packageName = pkg)
        }

        // 明确歌词字段优先级最高。
        findMetadataLyrics(metadata)?.let {
            if (!isJustName(it, artist, album)) {
                val full = it.contains('\n') || lrcStamp.containsMatchIn(it)
                publish(pkg, it, "media_metadata", rank = 1)
                if (full) return
                return
            }
        }

        // ViPER HiFi 已验证的路径：逐句更新 MediaSession TITLE。
        val title = read(metadata, MediaMetadata.METADATA_KEY_TITLE).ifBlank {
            read(metadata, MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        }
        val hasTrackIdentity = artist.isNotBlank() || album.isNotBlank() ||
            mediaId.isNotBlank() || duration > 0L

        if (hasTrackIdentity) {
            val sig = "$title|$artist|$duration"
            if (sig != lastSig) {
                lastSig = sig
                AppLog.i("LYRIC raw title=\"" + title.take(60) + "\" artist=\"" + artist.take(40) + "\" dur=$duration", false)
            }
            if (carMode(artist)) carArtist = artist
            lastArtist = carArtist.ifBlank { artist }
            // 车载歌词模式：artist 是「歌名-歌手」，说明 TITLE 每句都在换，收到即显示，别拖一句
            acceptDynamic(mediaSequence, pkg, trackKey, title, "media_title", trusted = carMode(lastArtist))
        }
    }

    /** 由现有 NotificationListenerService 调用；不要求通知显示功能开启。 */
    @Synchronized
    fun onNotification(pkg: String, extras: Bundle?) {
        extras ?: return
        val activePkg = state.packageName
        if (activePkg != null && activePkg != pkg) return

        findBundleLyrics(extras)?.let {
            publish(pkg, it, "notification_extras", rank = 4)
            return
        }

        val text = sequenceOf(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()
        ).filterNotNull().firstOrNull { it.isNotBlank() }.orEmpty()

        if (text.isNotBlank()) {
            acceptDynamic(notificationSequence, pkg, "notification", text, "notification_text", trusted = carMode(lastArtist))
        }
    }

    /**
     * 最低优先级（rank 5）兜底入口：CarLife 侧未知报文里如果真的带着文本歌词，
     * 就作为最后一档候选收进来——只有前面所有来源都没有歌词时才会被显示。
     */
    @Synchronized
    fun onUnknownCommandText(raw: String) {
        val pkg = state.packageName ?: return
        AppLog.i("LYRIC carlife text len=" + raw.length + " src=carlife_cmd")
        publish(pkg, raw, "carlife_cmd", rank = 5)
    }

    /** 无活动媒体会话时清除旧歌词。 */
    @Synchronized
    fun onNoSession() {
        clearCands()
        mediaSequence.reset(null, null)
        notificationSequence.reset(null, null)
        state = LyricsState()
    }

    private fun findMetadataLyrics(md: MediaMetadata): String? {
        for (key in md.keySet()) {
            val lower = key.substringAfterLast('.').lowercase()
            if (isLyricKey(lower)) {
                val value = read(md, key)
                normalize(value)?.let { return it }
            }
        }
        return null
    }

    private fun findBundleLyrics(bundle: Bundle): String? {
        // 先按已知歌词键名找一遍（命中率最高、最不容易误判）
        for (key in bundle.keySet()) {
            val lower = key.substringAfterLast('.').lowercase()
            if (isLyricKey(lower)) {
                val value = runCatching { bundle.get(key)?.toString().orEmpty() }.getOrDefault("")
                normalize(value)?.let { return it }
            }
        }
        // 再全字段扫一遍：网易云/酷狗的"车载歌词"常写在自造键上，键名认不出来但内容是句子
        for (key in bundle.keySet()) {
            val lower = key.substringAfterLast('.').lowercase()
            if (lyricKeyNames.contains(lower)) continue
            if (lower.contains("title") || lower.contains("artist") || lower.contains("album") ||
                lower.contains("progress") || lower.contains("duration") || lower.contains("icon")
            ) continue
            val v = runCatching { bundle.get(key) }.getOrNull()
            if (v !is CharSequence) continue                     // int[] / Bitmap / PendingIntent 一律跳过
            val raw = v.toString()
            if (raw.length < 4 || raw.length > 600) continue
            if (lower.contains("compact") || lower.contains("action") || lower.contains("template") ||
                lower.contains("channel") || lower.contains("group") || lower.contains("sort")
            ) continue
            if (!raw.contains('\n') && !raw.contains('[')) continue   // 单句逐句流交给下面的 BIG_TEXT/TEXT 路径
            normalize(raw)?.let {
                AppLog.i("LYRIC notification extras key=$key len=${raw.length}")
                return it
            }
        }
        return null
    }

    /** 车载歌词模式下 artist 写作「歌名-歌手」，拆出歌名与歌手。 */
    private fun carParts(artist: String): Pair<String, String>? {
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

    private fun carMode(artist: String): Boolean = carParts(artist) != null

    /** 这一行其实是「歌名」「歌名 - 歌手」「歌手」：不是歌词。 */
    private fun isTrackNameLine(line: String): Boolean {
        val t = line.trim()
        // 通用：任意「A - B」形式，只要有一边是歌手名，就不是歌词
        for (sep in listOf(" - ", " — ", "-", "—")) {
            val i = t.indexOf(sep)
            if (i <= 0) continue
            val l = t.substring(0, i).trim()
            val r = t.substring(i + sep.length).trim()
            if (l.isEmpty() || r.isEmpty() || t.length > 60) continue
            val a = lastArtist.trim()
            val tokens = a.split('/', '、', '&', ',').map { it.trim() }.filter { it.isNotEmpty() }
            if (a.isNotEmpty() && (l == a || r == a)) return true
            if (tokens.isNotEmpty() && tokens.any { it == l || it == r }) return true
        }
        val parts = carParts(lastArtist) ?: return false
        val song = parts.first
        val who = parts.second
        if (t.equals(song, ignoreCase = true)) return true
        if (t.equals("$song - $who", ignoreCase = true)) return true
        if (t.equals("$song-$who", ignoreCase = true)) return true
        if (t.equals("$song $who", ignoreCase = true)) return true
        return false
    }

    /** 拿到的其实只是歌名或歌手名：不是歌词。 */
    private fun isJustName(text: String, artist: String, album: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return true
        val a = artist.trim().lowercase()
        val al = album.trim().lowercase()
        if (a.isNotEmpty() && (t == a || t == a.replace(" ", "") || a.contains(t) && t.length >= 2 && t.length * 3 <= a.length)) return true
        if (al.isNotEmpty() && t == al) return true
        return false
    }

    private fun isLyricKey(key: String): Boolean =
        key in lyricKeyNames || key.contains("lyric") || key.contains("caption") || key.contains("subtitle")

    private fun read(md: MediaMetadata, key: String): String {
        val str = runCatching { md.getString(key) }.getOrNull()
        if (!str.isNullOrBlank()) return str
        return runCatching { md.getText(key)?.toString().orEmpty() }.getOrDefault("")
    }

    /** 动态字段至少连续变化一次才确认，避免把普通歌曲标题直接显示成歌词。 */
    private fun acceptDynamic(
        sequence: Sequence,
        pkg: String,
        trackKey: String,
        raw: String,
        source: String,
        trusted: Boolean = false
    ) {
        val line = normalize(raw) ?: return
        // 逐句通道只认单行纯文本：整块 LRC、多行、带时间戳的一律丢弃（QQ 会把这些脏报文往 TITLE 里塞）
        if (line.contains('\n') || lrcStamp.containsMatchIn(line) || line.startsWith("[")) {
            AppLog.i("LYRIC drop timed/multi-line src=$source len=${line.length}")
            return
        }
        if (isTrackNameLine(line)) return
        val now = SystemClock.elapsedRealtime()

        if (sequence.packageName != pkg || sequence.trackKey != trackKey) {
            sequence.reset(pkg, trackKey)
        }
        if (line == sequence.previous) return

        val confirmed = trusted || (sequence.pending.isNotBlank() &&
            sequence.pending != line && now - sequence.pendingAt <= 8_000L)
        val rank = when (source) {
            "media_title" -> if (trusted) 2 else 3      // 车载逐句 高于 普通逐句
            else -> 5                                   // 通知正文最低
        }
        if (confirmed) publish(pkg, line, source, rank)

        sequence.previous = line
        sequence.pending = line
        sequence.pendingAt = now
    }

    /** 带时间轴的整块歌词：[mm:ss] 后面跟正文，交给图层按播放进度驱动。 */
    private val lrcStamp = Regex("""\[\d{1,2}:\d{2}(?:[.:]\d{1,3})?]""")

    private fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val original = raw.replace("\r", "").trim()
        // 一次性甩过来整首带时间轴的歌词：原样保留，让图层按进度推句
        if (lrcStamp.findAll(original).count() >= 2) {
            val lines = original.lineSequence().map { it.trim() }.filter { it.isNotBlank() }
            if (lines.count() >= 2) return lines.joinToString("\n")
        }
        val cleaned = original
            .replace(lrcStamp, "")
            .replace(Regex("""<\d{1,2}:\d{2}(?:[.:]\d{1,3})?>"""), "")
            .trim()
        // 逐句通道常见的脏报文：歌词前面/中间夹着 00:12.34
        val anyStamp = Regex("""(?:\[|\()?\d{1,2}:\d{2}(?:[.:]\d{1,3})?(?:]|\))?""")
        if (anyStamp.containsMatchIn(original) && lrcStamp.findAll(original).count() >= 1 &&
            original.length > 180
        ) return null
        val rows = cleaned.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        // 多行但没有时间轴：无法知道每句落在哪一秒，宁可不显示，也别乱配
        if (rows.size >= 3) {
            AppLog.i("LYRIC drop multi-line blob without timestamps lines=${rows.size}")
            return null
        }
        val line = rows.lastOrNull() ?: return null
        if (line.length !in 2..180) return null
        val lower = line.lowercase()
        val blocked = listOf(
            "正在播放", "已暂停", "播放失败", "bluetooth", "connecting", "loading",
            "unknown artist", "unknown title",
            "为你打包", "戳>>", "点击", "热门推荐", "猜你喜欢"
        )
        if (blocked.any { lower.contains(it) }) return null
        if (line.startsWith("http://") || line.startsWith("https://")) return null
        return line
    }

    private fun publish(pkg: String, raw: String, source: String, rank: Int = 3) {
        val line = normalize(raw) ?: return
        val now = SystemClock.elapsedRealtime()
        val cand = Cand(line, rank, source, now)
        when {
            rank == 1 -> {
                val changed = lrcCand?.text != line
                lrcCand = cand
                if (changed) AppLog.i("LYRIC arbitrate owner=LRC src=$source lines_len=${line.length}")
            }
            rank <= 3 -> lineCand = cand          // 低优先级只进缓存，不抢显示
            else -> weakCand = cand
        }
        resolve(pkg)
    }

    /** 显示层的 LRC 解析器判定这份时间轴无效：撤掉 rank 1，立刻让缓存里的单句接管。 */
    @Synchronized
    fun rejectFullLrc() {
        if (lrcCand == null) return
        AppLog.i("LYRIC arbitrate LRC invalid -> fallback to single line")
        lrcCand = null
        state.packageName?.let { resolve(it) }
    }

    /** 取当前最高优先级且有效的那一份，写进 state。 */
    private fun resolve(pkg: String) {
        val best = lrcCand ?: lineCand ?: weakCand
        if (best == null) {
            if (state.text.isNotEmpty()) state = LyricsState(packageName = pkg)
            return
        }
        if (state.packageName == pkg && state.text == best.text && state.source == best.source) return
        state = LyricsState(best.text, pkg, best.source, SystemClock.elapsedRealtime())
    }

    private fun Sequence.reset(pkg: String?, key: String?) {
        packageName = pkg
        trackKey = key
        previous = ""
        pending = ""
        pendingAt = 0L
    }
}
