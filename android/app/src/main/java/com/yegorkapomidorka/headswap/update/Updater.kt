package com.yegorkapomidorka.headswap.update

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.yegorkapomidorka.headswap.AppLocale
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.BuildConfig
import com.yegorkapomidorka.headswap.CarLifeState
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.ui.UiText
import com.yegorkapomidorka.headswap.ui.dp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/** Настройки и кэш обновлений (отдельный файл prefs): последний найденный релиз переживает перезапуск. */
class UpdatePrefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("update", Context.MODE_PRIVATE)

    /** Автопроверка при открытии приложения; по умолчанию вкл. */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var lastCheck: Long
        get() = sp.getLong("lastCheck", 0)
        set(v) = sp.edit().putLong("lastCheck", v).apply()

    /** Версия, плашку которой закрыли на главном экране: до следующей версии не показываем. */
    var dismissed: String
        get() = sp.getString("dismissed", "") ?: ""
        set(v) = sp.edit().putString("dismissed", v).apply()

    fun save(r: UpdateLogic.Release) = sp.edit()
        .putString("version", r.version).putString("tag", r.tag).putString("url", r.apkUrl).putLong("size", r.size)
        .putString("sha256", r.sha256).putString("notes", r.notes).putString("page", r.page)
        .apply()

    fun release(): UpdateLogic.Release? {
        val v = sp.getString("version", null) ?: return null
        val url = sp.getString("url", null) ?: return null
        return UpdateLogic.Release(
            v, sp.getString("tag", "") ?: "", url, sp.getLong("size", -1), sp.getString("sha256", null),
            sp.getString("notes", "") ?: "", sp.getString("page", "") ?: "",
        )
    }
}

/**
 * Обновление из приложения: последний релиз GitHub (UpdateLogic), APK в cacheDir/update, установка через PackageInstaller.
 * Подтверждение установки показывает система; подпись нового APK она же сверяет с установленным.
 * Состояние для экранов - поля [step], [progress], [error] (экраны опрашивают их в tick).
 */
object Updater {
    enum class Step { IDLE, CHECKING, DOWNLOADING, INSTALLING }

    @Volatile
    var step = Step.IDLE
        private set

    /** 0..100, пока [step] = DOWNLOADING. */
    @Volatile
    var progress = 0
        private set

    /** Последняя ошибка проверки, скачивания или установки; сбрасывается следующим действием. */
    @Volatile
    var error: UiText? = null
        private set

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var installed: String? = null
    private var cleaned = false

    /** Ошибка с текстом для пользователя; message - для лога. */
    private class Fail(val text: UiText, message: String) : Exception(message)

    fun installedVersion(ctx: Context): String = installed ?: (
        try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (_: Exception) {
            null
        } ?: "?"
        ).also { installed = it }

    /** Сохранённый релиз, если он новее установленной версии. */
    fun available(ctx: Context): UpdateLogic.Release? {
        if (BuildConfig.CARLIFE_ADB) return null      // versionName 8.6.7 не сравнится с релизами Headswap, а ставился бы другой пакет
        return UpdatePrefs(ctx).release()?.takeIf { UpdateLogic.isNewer(it.version, installedVersion(ctx)) }
    }

    /** Плашка на главном экране: проверка включена, есть новее и её не закрыли. */
    fun banner(ctx: Context): UpdateLogic.Release? {
        val p = UpdatePrefs(ctx)
        if (!p.enabled) return null
        return available(ctx)?.takeIf { it.version != p.dismissed }
    }

    fun dismiss(ctx: Context, version: String) {
        UpdatePrefs(ctx).dismissed = version
    }

    /** При открытии приложения: проверить, если включено и пора; заодно убрать APK уже установленных версий. */
    fun maybeCheck(ctx: Context) {
        if (BuildConfig.CARLIFE_ADB) return
        if (!cleaned && step == Step.IDLE) {
            cleaned = true
            val cur = installedVersion(ctx)
            File(ctx.cacheDir, "update").listFiles()
                ?.filter { !UpdateLogic.isNewer(it.name.removePrefix("headswap-").removeSuffix(".apk"), cur) }
                ?.forEach { it.delete() }
        }
        val p = UpdatePrefs(ctx)
        if (UpdateLogic.shouldCheck(p.enabled, p.lastCheck, System.currentTimeMillis())) check(ctx, manual = false)
    }

    /** Запросить последний релиз в фоне. manual: ошибку показать тостом. */
    fun check(ctx: Context, manual: Boolean) {
        if (BuildConfig.CARLIFE_ADB) return
        synchronized(this) {
            if (step != Step.IDLE) return
            step = Step.CHECKING
            error = null
        }
        val app = ctx.applicationContext
        Thread({
            val p = UpdatePrefs(app)
            val now = System.currentTimeMillis()
            try {
                val rel = fetchLatest(app)
                p.lastCheck = now
                p.save(rel)
                val cur = installedVersion(app)
                AppLog.i("обновления: на GitHub ${rel.version} (${rel.tag}), установлена $cur" + if (UpdateLogic.isNewer(rel.version, cur)) ", есть новее" else "")
                step = Step.IDLE
            } catch (e: Exception) {
                AppLog.e("обновления: проверка не удалась", e)
                p.lastCheck = now - UpdateLogic.CHECK_INTERVAL_MS + UpdateLogic.RETRY_MS
                val text = UiText.res(R.string.upd_err_check, reason(e))
                error = text
                step = Step.IDLE
                if (manual) toast(app, text)
            }
        }, "update-check").start()
    }

    /**
     * Кнопка «Обновить»: разрешение на установку, диалог с описанием релиза, затем скачивание и установка.
     * confirm = false (кнопка в настройках) - без диалога: при крупном системном шрифте его кнопки бывают не видны.
     */
    fun start(act: Activity, confirm: Boolean = true) {
        val rel = available(act) ?: return
        if (step == Step.DOWNLOADING || step == Step.INSTALLING) return
        if (!act.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(act, R.style.Theme_Headswap_Dialog)
                .setTitle(act.getString(R.string.upd_unknown_title))
                .setMessage(act.getString(R.string.upd_unknown_message))
                .setPositiveButton(act.getString(R.string.act_open)) { _, _ -> openUnknownSources(act) }
                .setNegativeButton(act.getString(R.string.act_cancel), null)
                .show()
            return
        }
        if (!confirm) {
            download(act.applicationContext, rel)
            return
        }
        val footer = buildString {
            append(
                if (rel.size > 0) act.getString(R.string.upd_dialog_size, act.getString(R.string.share_size_mib, "%.1f".format(Locale.US, rel.size / 1048576.0)))
                else act.getString(R.string.upd_dialog_no_size),
            )
            if (CarLifeState.running) append("\n\n").append(act.getString(R.string.upd_dialog_bridge))
        }
        val b = AlertDialog.Builder(act, R.style.Theme_Headswap_Dialog)
        b.setTitle(act.getString(R.string.upd_dialog_title, rel.version))
            .setView(dialogBody(b.context, rel.notes, footer))
            .setPositiveButton(act.getString(R.string.upd_btn_update)) { _, _ -> download(act.applicationContext, rel) }
            .setNegativeButton(act.getString(R.string.act_cancel), null)
            .show()
    }

    /**
     * Тело диалога обновления: описание релиза в окошке со своим скроллом, под ним размер и прочее.
     * Через setMessage длинное описание на части прошивок растягивает диалог и выталкивает кнопки за экран.
     */
    private fun dialogBody(ctx: Context, notes: String, footer: String): View {
        val pad = ctx.dp(24)
        fun label(s: String) = TextView(ctx).apply {
            setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium)
            text = s
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, ctx.dp(16), pad, ctx.dp(8))
            if (notes.isNotEmpty()) {
                val maxH = (ctx.resources.displayMetrics.heightPixels * 0.4f).toInt()
                addView(object : ScrollView(ctx) {
                    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
                    }
                }.apply {
                    isVerticalScrollBarEnabled = true
                    isScrollbarFadingEnabled = false
                    addView(label(notes))
                })
            }
            addView(label(footer).apply { if (notes.isNotEmpty()) setPadding(0, ctx.dp(16), 0, 0) })
        }
    }

    private fun openUnknownSources(ctx: Context) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
        } catch (e: Exception) {
            AppLog.e("не открыть настройки установки из неизвестных источников", e)
        }
    }

    private fun download(app: Context, rel: UpdateLogic.Release) {
        synchronized(this) {
            if (step != Step.IDLE) return
            step = Step.DOWNLOADING
            progress = 0
            error = null
        }
        AppLog.i("обновление: скачиваю ${rel.version} ${rel.apkUrl}")
        Thread({
            val f = try {
                fetchApk(app, rel).also { checkApk(app, it) }
            } catch (e: Exception) {
                fail(app, UiText.res(R.string.upd_err_download, reason(e)), "обновление: не скачать ${rel.version}", e)
                return@Thread
            }
            step = Step.INSTALLING
            try {
                install(app, f)
            } catch (e: Exception) {
                fail(app, UiText.res(R.string.upd_err_install, reason(e)), "обновление: не начать установку", e)
            }
        }, "update-download").start()
    }

    /** Итог сессии PackageInstaller (InstallReceiver). При успехе процесс обычно убит раньше, чем это придёт. */
    fun onInstallStatus(ctx: Context, status: Int, message: String?, confirm: Intent?) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                AppLog.i("обновление: жду подтверждения установки")
                step = Step.IDLE
                if (confirm == null) {
                    fail(ctx, UiText.res(R.string.upd_err_install, UiText.raw(message ?: "no confirm intent")), "обновление: нет intent подтверждения", null)
                    return
                }
                try {
                    ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    fail(ctx, UiText.res(R.string.upd_err_install, reason(e)), "обновление: не открыть подтверждение установки", e)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                AppLog.i("обновление установлено")
                step = Step.IDLE
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                AppLog.i("обновление: установку отменили ($message)")
                step = Step.IDLE
            }
            else -> {
                val why = if (status == PackageInstaller.STATUS_FAILURE_CONFLICT) UiText.res(R.string.upd_err_conflict) else UiText.raw(message ?: "status $status")
                fail(ctx, UiText.res(R.string.upd_err_install, why), "обновление: установка не удалась, статус $status: $message", null)
            }
        }
    }

    private fun fail(ctx: Context, text: UiText, log: String, e: Exception?) {
        AppLog.e(log, e)
        error = text
        step = Step.IDLE
        toast(ctx.applicationContext, text)
    }

    private fun reason(e: Exception): UiText = (e as? Fail)?.text ?: UiText.raw(e.message ?: e.javaClass.simpleName)

    private fun toast(app: Context, text: UiText) {
        main.post { Toast.makeText(app, text.get(AppLocale.wrap(app)), Toast.LENGTH_LONG).show() }
    }

    // --- сеть ---

    private fun open(url: String, app: Context): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "Headswap/${installedVersion(app)} (Android ${Build.VERSION.RELEASE})")
    }

    private fun fetchLatest(app: Context): UpdateLogic.Release {
        val c = open(UpdateLogic.API_LATEST, app)
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            val code = c.responseCode
            if (code == 200) return parseRelease(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            if (code == 404) throw Fail(UiText.res(R.string.upd_err_no_release), "релизов на GitHub нет (API 404)")
            AppLog.i("обновления: API GitHub ответил HTTP $code, смотрю редирект страницы релиза")
        } finally {
            c.disconnect()
        }
        return fetchFromPage(app)
    }

    /** Без API: github.com/.../releases/latest отвечает редиректом на страницу тега; APK по имени headswap-<версия>.apk. */
    private fun fetchFromPage(app: Context): UpdateLogic.Release {
        val c = open(UpdateLogic.PAGE_LATEST, app)
        c.instanceFollowRedirects = false
        try {
            val code = c.responseCode
            val tag = UpdateLogic.tagFromLocation(c.getHeaderField("Location"))
                ?: throw Fail(
                    if (code == 404 || code in 300..399) UiText.res(R.string.upd_err_no_release) else UiText.raw("HTTP $code"),
                    "страница релиза: HTTP $code без тега",
                )
            val ver = UpdateLogic.versionFromTag(tag)
            return UpdateLogic.Release(ver, tag, UpdateLogic.assetUrl(tag, ver), -1, null, "", UpdateLogic.tagPage(tag))
        } finally {
            c.disconnect()
        }
    }

    private fun parseRelease(json: String): UpdateLogic.Release {
        val o = JSONObject(json)
        val tag = o.getString("tag_name")
        val ver = UpdateLogic.versionFromTag(tag)
        val arr = o.optJSONArray("assets") ?: JSONArray()
        val assets = (0 until arr.length()).map { i ->
            val a = arr.getJSONObject(i)
            UpdateLogic.Asset(a.optString("name"), a.optString("browser_download_url"), a.optLong("size", -1), if (a.isNull("digest")) null else a.optString("digest"))
        }
        val apk = UpdateLogic.pickApk(assets, ver) ?: throw Fail(UiText.res(R.string.upd_err_no_apk, tag), "в релизе $tag нет APK")
        return UpdateLogic.Release(
            ver, tag, apk.url, apk.size, UpdateLogic.sha256(apk.digest),
            UpdateLogic.notes(if (o.isNull("body")) null else o.optString("body")), o.optString("html_url", UpdateLogic.tagPage(tag)),
        )
    }

    /** APK в cacheDir/update; уже скачанный и совпавший по размеру и SHA-256 не качаем заново (установку могли отменить). */
    private fun fetchApk(app: Context, rel: UpdateLogic.Release): File {
        val dir = File(app.cacheDir, "update").apply { mkdirs() }
        val f = File(dir, "headswap-${rel.version}.apk")
        if (f.isFile && rel.size > 0 && f.length() == rel.size && (rel.sha256 == null || sha256(f) == rel.sha256)) {
            AppLog.i("обновление: ${f.name} уже скачан")
            return f
        }
        dir.listFiles()?.forEach { it.delete() }
        val c = open(rel.apkUrl, app)
        try {
            val code = c.responseCode
            if (code != 200) throw Fail(UiText.raw("HTTP $code"), "APK: HTTP $code")
            val total = if (rel.size > 0) rel.size else c.contentLengthLong
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            c.inputStream.use { inp ->
                f.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (total > 0) progress = (done * 100 / total).toInt().coerceIn(0, 100)
                    }
                }
            }
            if (rel.size > 0 && done != rel.size) throw Fail(UiText.res(R.string.upd_err_incomplete), "скачано $done Б из ${rel.size}")
            val sum = hex(md.digest())
            if (rel.sha256 != null && sum != rel.sha256) throw Fail(UiText.res(R.string.upd_err_checksum), "SHA-256 $sum, в релизе ${rel.sha256}")
            AppLog.i("обновление: скачано $done Б, SHA-256 $sum")
            return f
        } catch (e: Exception) {
            f.delete()
            throw e
        } finally {
            c.disconnect()
        }
    }

    /** Наш пакет и версия новее установленной: иначе система откажет уже после подтверждения пользователя. */
    @Suppress("DEPRECATION")
    private fun checkApk(app: Context, f: File) {
        val pm = app.packageManager
        val arc = pm.getPackageArchiveInfo(f.path, 0)
        if (arc == null || arc.packageName != app.packageName) {
            f.delete()
            throw Fail(UiText.res(R.string.upd_err_not_apk), "скачан не APK Headswap: ${arc?.packageName}")
        }
        val cur = pm.getPackageInfo(app.packageName, 0)
        if (arc.longVersionCode <= cur.longVersionCode) {
            throw Fail(
                UiText.res(R.string.upd_err_not_newer, arc.versionName ?: "?", cur.versionName ?: "?"),
                "APK ${arc.versionName} (${arc.longVersionCode}) не новее установленной ${cur.versionName} (${cur.longVersionCode})",
            )
        }
    }

    private fun install(app: Context, f: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(f.length())
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { s ->
                s.openWrite("headswap.apk", 0, f.length()).use { out ->
                    f.inputStream().use { it.copyTo(out) }
                    s.fsync(out)
                }
                // FLAG_MUTABLE: установщик дописывает в intent статус и intent подтверждения
                val pi = PendingIntent.getBroadcast(
                    app, id, Intent(app, InstallReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                s.commit(pi.intentSender)
            }
        } catch (e: Exception) {
            try {
                installer.abandonSession(id)
            } catch (_: Exception) {
            }
            throw e
        }
        AppLog.i("обновление: сессия установки $id отправлена")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}

/** Статус сессии PackageInstaller -> Updater (подтверждение установки, ошибки). */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        @Suppress("DEPRECATION")
        val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_INTENT)
        Updater.onInstallStatus(context, status, message, confirm)
    }
}
