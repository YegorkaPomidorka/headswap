package com.yegorkapomidorka.headswap.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.yegorkapomidorka.headswap.AppLocale
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.AppTheme
import com.yegorkapomidorka.headswap.BridgeService
import com.yegorkapomidorka.headswap.BuildConfig
import com.yegorkapomidorka.headswap.CarLifeState
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.aa.AaDensity
import com.yegorkapomidorka.headswap.aa.AaPrefs
import com.yegorkapomidorka.headswap.aa.CAR_MAKES
import com.yegorkapomidorka.headswap.aa.EQ_BAND_MAX
import com.yegorkapomidorka.headswap.aa.EQ_BAND_MIN
import com.yegorkapomidorka.headswap.aa.EQ_FREQS
import com.yegorkapomidorka.headswap.aa.EQ_PREAMP_MAX
import com.yegorkapomidorka.headswap.aa.EQ_PREAMP_MIN
import com.yegorkapomidorka.headswap.aa.EqPreset
import com.yegorkapomidorka.headswap.aa.EqSettings
import com.yegorkapomidorka.headswap.aa.FpsSetting
import com.yegorkapomidorka.headswap.aa.MicMode
import com.yegorkapomidorka.headswap.aa.MusicRate
import com.yegorkapomidorka.headswap.aa.VideoMode
import com.yegorkapomidorka.headswap.autosrv.AutoServer
import com.yegorkapomidorka.headswap.link.car.CarNetwork
import com.yegorkapomidorka.headswap.link.car.CarPairing
import com.yegorkapomidorka.headswap.link.car.CarProfile
import com.yegorkapomidorka.headswap.link.car.CarWifi
import com.yegorkapomidorka.headswap.link.diag.NetProbe
import com.yegorkapomidorka.headswap.update.UpdatePrefs
import com.yegorkapomidorka.headswap.update.Updater
import java.text.NumberFormat

/** Шапка «назад + заголовок» и прокручиваемое тело. */
fun scaffold(ctx: Context, title: String, onBack: () -> Unit, body: View): LinearLayout = ctx.vbox().apply {
    val header = ctx.hbox(4).apply {
        pad(ctx, 4, 8, 8, 0)
        addView(ctx.iconButton(R.drawable.ic_back, ctx.clr(R.color.on), ctx.getString(R.string.set_back)).apply { setOnClickListener { onBack() } })
        addView(ctx.text(title, 22f, 600))
    }
    addView(header, ctx.lpDp(MATCH, 64))
    addView(ScrollView(ctx).apply {
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(body, ViewGroup.LayoutParams(MATCH, WRAP))
    }, lp(MATCH, 0, 1f))
}

/** Разделы настроек: на главном экране по строке на каждый, внутри — свой экран (SettingsSectionScreen). */
enum class SettingsSection(val key: String, val titleRes: Int, val iconRes: Int) {
    APPEARANCE("appearance", R.string.set_sec_appearance, R.drawable.ic_palette),
    VIDEO("video", R.string.set_video, R.drawable.ic_video),
    SOUND("sound", R.string.set_sec_sound, R.drawable.ic_mic),

    /** Эквалайзер: вход из «Звук и микрофон», на главном экране своей строки нет. */
    EQ("eq", R.string.eq_title, R.drawable.ic_equalizer),
    HEADUNIT("headunit", R.string.set_sec_headunit, R.drawable.ic_car),

    /** Каталог чужих AA-приложений (Fermata Auto, ParkedTube, AABrowser) и предупреждение про видео в движении. */
    APPS("apps", R.string.set_sec_apps, R.drawable.ic_open),

    /** Беспровод: только в сборке с BuildConfig.WIRELESS_BETA. */
    WIRELESS("wireless", R.string.wl_title, R.drawable.ic_wifi),
    UPDATES("updates", R.string.upd_title, R.drawable.ic_download);

    companion object {
        /** Разделы этой сборки: без беты беспровода его нет ни в меню, ни по ключу. */
        fun shown(): List<SettingsSection> = values().filter { it != WIRELESS || BuildConfig.WIRELESS_BETA }

        fun byKey(key: String?): SettingsSection? = shown().firstOrNull { it.key == key }

        /** Строки главного экрана настроек: подразделы (EQ) открываются из своих разделов. */
        fun menu(): List<SettingsSection> = shown().filter { it != EQ }
    }
}

private val LANGS = listOf(
    AppLocale.SYSTEM to R.string.lang_system,
    "en" to R.string.lang_en,
    "ru" to R.string.lang_ru,
)

private val THEMES = listOf(
    AppTheme.SYSTEM to R.string.theme_system,
    AppTheme.LIGHT to R.string.theme_light,
    AppTheme.MEDIUM to R.string.theme_medium,
    AppTheme.DARK to R.string.theme_dark,
)

private val MICS = listOf(
    Triple(MicMode.CAR, R.string.mic_car_title, R.string.mic_car_sub),
    Triple(MicMode.PHONE, R.string.mic_phone_title, R.string.mic_phone_sub),
    Triple(MicMode.AUTO, R.string.mic_auto_title, R.string.mic_auto_sub),
)

private val EQ_PRESETS = listOf(
    EqPreset.FLAT to R.string.eq_preset_flat,
    EqPreset.BASS to R.string.eq_preset_bass,
    EqPreset.VOCAL to R.string.eq_preset_vocal,
    EqPreset.ROCK to R.string.eq_preset_rock,
    EqPreset.ELECTRONIC to R.string.eq_preset_electronic,
    EqPreset.CUSTOM to R.string.eq_preset_custom,
)

private fun Context.eqPresetName(p: EqPreset): String = getString(EQ_PRESETS.first { it.first == p }.second)

private fun Context.micMissing(aa: AaPrefs): Boolean =
    aa.micMode != MicMode.CAR && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED

/** Строка состояния обновлений и её цвет: одна и та же на главном экране и в разделе «Обновления». */
private fun Context.updateStatus(): Pair<String, Int> {
    val rel = Updater.available(this)
    val err = Updater.error
    return when {
        Updater.step == Updater.Step.CHECKING -> getString(R.string.upd_status_checking) to R.color.on2
        Updater.step == Updater.Step.DOWNLOADING -> getString(R.string.upd_status_downloading, rel?.version ?: "", Updater.progress) to R.color.on2
        Updater.step == Updater.Step.INSTALLING -> getString(R.string.upd_status_installing) to R.color.on2
        err != null -> err.get(this) to R.color.warn
        rel != null -> getString(R.string.upd_status_available, rel.version) to R.color.acc
        UpdatePrefs(this).lastCheck > 0 -> getString(R.string.upd_status_latest) to R.color.ok
        else -> getString(R.string.upd_status_never) to R.color.on2
    }
}

/** Главный экран настроек: разделы с текущими значениями, помощь, версия. */
class SettingsScreen(host: MainActivity) : Screen(host) {
    private val aa = AaPrefs(ctx)
    private val summaries = HashMap<SettingsSection, TextView>()
    override val view: View

    init {
        val c = ctx
        val body = c.vbox(12).apply { pad(c, 16, 4, 16, 16) }

        val sections = c.card()
        for ((i, s) in SettingsSection.menu().withIndex()) {
            if (i > 0) sections.addView(c.divider())
            sections.addView(sectionRow(s))
        }
        body.addView(sections, lp(MATCH, WRAP))

        body.addView(c.sectionTitle(c.getString(R.string.set_help)))
        val links = c.card().apply {
            addView(link(R.drawable.ic_report, c.getString(R.string.set_report_title), c.getString(R.string.set_report_note), false) { LogShare.shareReport(c) })
            addView(c.divider())
            addView(link(R.drawable.ic_refresh_btn, c.getString(R.string.set_rerun_setup), null, false) { host.showOnboarding(rerun = true) })
            addView(c.divider())
            addView(link(R.drawable.ic_check, c.getString(R.string.set_check_ready), null, false) { host.showOnboarding(checklist = true) })
            addView(c.divider())
            addView(link(R.drawable.ic_terminal, c.getString(R.string.set_for_devs), null, true) { host.showDev() })
        }
        body.addView(links, lp(MATCH, WRAP))

        body.addView(View(c), lp(MATCH, c.dp(12), 1f))
        val ver = try {
            c.packageManager.getPackageInfo(c.packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        body.addView(c.text(c.getString(R.string.set_footer, ver), 13f, 400, c.clr(R.color.on2)).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP))
        body.addView(supportButton(), LinearLayout.LayoutParams(WRAP, c.dp(44)).margins(c, 0, 12, 0, 8).apply { gravity = Gravity.CENTER_HORIZONTAL })

        refreshSummaries()
        view = scaffold(c, c.getString(R.string.set_title), { host.back() }, body)
    }

    override fun onShow() = refreshSummaries()

    override fun tick() = refreshSummaries()

    override fun onPermissionResult() = refreshSummaries()

    private fun sectionRow(s: SettingsSection): View {
        val c = ctx
        val sum = c.text("", 14f, 400, c.clr(R.color.on2)).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        summaries[s] = sum
        return c.hbox(14).apply {
            pad(c, 14, 12, 12, 12)
            minimumHeight = c.dp(76)
            background = c.rippleBg(null, 0)
            isClickable = true
            setOnClickListener { host.showSettingsSection(s) }
            val badge = FrameLayout(c).apply {
                background = c.shape(c.clr(R.color.pric), 14)
                addView(c.icon(s.iconRes, 22, c.clr(R.color.acc)), FrameLayout.LayoutParams(c.dp(22), c.dp(22), Gravity.CENTER))
            }
            addView(badge, c.lpDp(c.dp(44), 44))
            val col = c.vbox(2).apply {
                addView(c.text(c.getString(s.titleRes), 17f, 500))
                addView(sum)
            }
            addView(col, lp(0, WRAP, 1f))
            addView(c.icon(R.drawable.ic_chevron, 20, c.clr(R.color.on2)))
        }
    }

    /** Подписи под разделами: текущие значения, а при проблеме — её описание цветом предупреждения. */
    private fun refreshSummaries() {
        val c = ctx
        fun set(s: SettingsSection, text: String, color: Int = R.color.on2) {
            val tv = summaries[s] ?: return
            if (tv.text.toString() != text) tv.text = text
            tv.setTextColor(c.clr(color))
        }
        val lang = c.getString(LANGS.firstOrNull { it.first == AppLocale.get(c) }?.second ?: R.string.lang_system)
        val theme = c.getString(THEMES.firstOrNull { it.first == AppTheme.get(c) }?.second ?: R.string.theme_system)
        set(SettingsSection.APPEARANCE, c.getString(R.string.set_sum_appearance, lang, theme))

        val mode = c.getString(if (aa.transcode) R.string.set_transcode_title else R.string.set_direct_title)
        val res = c.getString(aa.videoMode.effective(aa.transcode).titleRes)
        val dpi = aa.aaDensity.takeIf { it != AaDensity.AUTO }?.let { c.getString(R.string.set_sum_density, it) }
        set(SettingsSection.VIDEO, listOfNotNull(mode, res, dpi, c.getString(aa.fpsSetting.titleRes)).joinToString(" · "))

        if (c.micMissing(aa)) {
            set(SettingsSection.SOUND, c.getString(R.string.mic_no_permission), R.color.warn)
        } else {
            val mic = c.getString(R.string.set_sum_mic, c.getString(MICS.first { it.first == aa.micMode }.second))
            val eq = if (aa.usbAudio && !aa.eq.isFlat) c.getString(R.string.eq_sum, c.eqPresetName(aa.eqPreset)) else null
            set(SettingsSection.SOUND, listOfNotNull(if (aa.usbAudio) c.getString(R.string.set_usb_audio_title) else null, eq, mic).joinToString(" · "))
        }

        if (aa.autoServer && !AutoServer.isConnected) {
            set(SettingsSection.HEADUNIT, c.getString(if (AutoServer.isListed(c)) R.string.set_svc_not_connected else R.string.set_svc_off), R.color.warn)
        } else {
            val auto = c.getString(if (aa.autoServer) R.string.set_sum_on else R.string.set_sum_off)
            set(SettingsSection.HEADUNIT, c.getString(R.string.set_sum_headunit, aa.certSet.uppercase(), auto))
        }

        set(SettingsSection.APPS, c.getString(R.string.set_sum_apps, HuApps.detect(c).installed.size))

        val (upd, color) = c.updateStatus()
        set(SettingsSection.UPDATES, upd, color)

        if (BuildConfig.WIRELESS_BETA) {
            val car = CarProfile(c)
            val carText = if (car.isSet) c.getString(R.string.wl_car_line, car.btName ?: car.btMac ?: "") else null
            set(SettingsSection.WIRELESS, listOfNotNull(CarLifeState.wireless ?: c.getString(R.string.wl_off), carText).joinToString(" · "))
        }
    }

    /**
     * «Поддержать Егора»: контурная пилюля под версией, открывает страницу поддержки на сайте
     * (реквизиты там, в приложении их нет). URL по языку: ru — СБП на /headswap/support/,
     * остальные — PayPal на /headswap/en/#donate (R.string.set_support_url).
     */
    private fun supportButton(): View {
        val c = ctx
        val fg = c.clr(R.color.acc)
        return c.hbox(8).apply {
            gravity = Gravity.CENTER
            pad(c, 18, 0, 18, 0)
            background = c.rippleBg(c.shape(0, 22, fg, 1.5f), 22)
            isClickable = true
            isFocusable = true
            addView(c.icon(R.drawable.ic_heart, 18, fg))
            addView(c.text(c.getString(R.string.set_support), 15f, 600, fg))
            setOnClickListener {
                try {
                    c.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(c.getString(R.string.set_support_url))))
                } catch (e: Exception) {
                    AppLog.e("поддержка: браузер не открылся: ${e.message}")
                }
            }
        }
    }

    private fun link(icon: Int, title: String, note: String?, chevron: Boolean, onClick: () -> Unit): View {
        val c = ctx
        return c.hbox(14).apply {
            pad(c, 16, 0, 16, 0)
            background = c.rippleBg(null, 0)
            isClickable = true
            setOnClickListener { onClick() }
            addView(c.icon(icon, 22, c.clr(R.color.on2)))
            addView(c.text(title, 16f, 500), lp(0, WRAP, 1f))
            if (note != null) addView(c.text(note, 13f, 400, c.clr(R.color.on2)))
            if (chevron) addView(c.icon(R.drawable.ic_chevron, 20, c.clr(R.color.on2)))
            layoutParams = c.lpDp(MATCH, 56)
        }
    }
}

/** Переключатель из нескольких кнопок в рамке; выбранная залита pric и помечена «✓». */
private class Segmented(val ctx: Context, private val labels: List<String>, sp: Float, val onPick: (Int) -> Unit) {
    private val buttons = ArrayList<TextView>()
    val view: LinearLayout = ctx.hbox().apply {
        background = ctx.shape(0, 24, ctx.clr(R.color.line), 1.5f)
        clipToOutline = true
    }

    init {
        for ((i, label) in labels.withIndex()) {
            val b = ctx.text(label, sp, 600).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                pad(ctx, 6, 0, 6, 0)
                isClickable = true
                setOnClickListener { onPick(i) }
            }
            buttons.add(b)
            view.addView(b, lp(0, ctx.dp(48), 1f))
        }
    }

    fun setSelected(sel: Int) {
        for ((i, b) in buttons.withIndex()) {
            val on = i == sel
            b.background = ctx.rippleBg(if (on) ctx.shape(ctx.clr(R.color.pric), 0) else null, 0)
            b.setTextColor(ctx.clr(if (on) R.color.onpric else R.color.on))
            b.text = if (on) "✓ ${labels[i]}" else labels[i]
        }
    }
}

/** Один раздел настроек (язык и тема, видео, звук, ГУ, обновления). */
class SettingsSectionScreen(host: MainActivity, val section: SettingsSection) : Screen(host) {
    private val aa = AaPrefs(ctx)
    private val langRows = LinkedHashMap<String, RadioRow>()
    private val themeRows = LinkedHashMap<String, RadioRow>()
    private val micRows = HashMap<MicMode, RadioRow>()
    private val rateRows = LinkedHashMap<MusicRate, RadioRow>()
    private val modeRows = LinkedHashMap<VideoMode, RadioRow>()
    private var makeValue: TextView? = null
    private var transcodeSeg: Segmented? = null
    private var transcodeNote: TextView? = null
    private var fpsSeg: Segmented? = null
    private var densityValue: TextView? = null
    private var densitySub: TextView? = null
    private var densityMinus: View? = null
    private var densityPlus: View? = null
    private var densityAuto: TextView? = null
    private var fpsNote: TextView? = null
    private var certSeg: Segmented? = null
    private var micNote: TextView? = null
    private val eqPresetBtns = ArrayList<Pair<TextView, EqPreset>>()
    private val eqBars = ArrayList<SeekBar>()
    private val eqValues = ArrayList<TextView>()
    private var eqPreBar: SeekBar? = null
    private var eqPreValue: TextView? = null
    private var eqUsbNote: TextView? = null
    private var eqEntrySub: TextView? = null
    private var eqEntryRow: View? = null
    /** Списки «Установлено на телефоне» и «Рекомендуем»: пересобираются при каждом возврате на экран. */
    private var appsHost: LinearLayout? = null
    private var svcStatus: TextView? = null
    private var svcButton: TextView? = null
    private var updStatus: TextView? = null
    private var updButton: TextView? = null
    private var wlStatus: TextView? = null
    private var wlNoPermission: TextView? = null
    private var wlAwaitingPermission = false
    private var wlCarLine: TextView? = null
    private var wlCarWarn: TextView? = null
    private var wlCarForget: View? = null
    private var wlAwaitingCarPermission = false
    private var wlCwLine: TextView? = null
    private var wlCwSsid: EditText? = null
    private var wlCwPass: EditText? = null
    private var wlCwResult: TextView? = null
    private var wlCwForget: View? = null
    /** Запрос сети машины из «Сохранить и проверить»; null - проверки нет. */
    private var wlCwCheck: CarNetwork? = null
    private val cwHandler = Handler(Looper.getMainLooper())
    private val body: LinearLayout = ctx.vbox(6).apply { pad(ctx, 16, 4, 16, 16) }
    override val view: View

    init {
        when (section) {
            SettingsSection.APPEARANCE -> buildAppearance()
            SettingsSection.VIDEO -> buildVideo()
            SettingsSection.SOUND -> buildSound()
            SettingsSection.EQ -> buildEq()
            SettingsSection.HEADUNIT -> buildHeadunit()
            SettingsSection.APPS -> buildApps()
            SettingsSection.UPDATES -> body.addView(updateCard(), lp(MATCH, WRAP).margins(ctx, 0, 8, 0, 0))
            SettingsSection.WIRELESS -> body.addView(wirelessCard(), lp(MATCH, WRAP).margins(ctx, 0, 8, 0, 0))
        }
        view = scaffold(ctx, ctx.getString(section.titleRes), { host.back() }, body)
    }

    override fun onShow() {
        refreshService()
        refreshMic()
        refreshEq()
        refreshEqEntry()
        refreshApps()
        refreshUpdate()
        refreshWireless()
        if (section == SettingsSection.APPS) maybeShowAppsWarning()
    }

    override fun tick() {
        refreshService()
        refreshUpdate()
        refreshWireless()
    }

    override fun onPermissionResult() {
        refreshMic()
        if (wlAwaitingPermission) {
            wlAwaitingPermission = false
            val lan = Build.VERSION.SDK_INT < 37 || NetProbe.localNetworkGranted(ctx) == "granted"
            if (lan) startWireless() else wlNoPermission?.visibility = View.VISIBLE
        }
        if (wlAwaitingCarPermission) {
            wlAwaitingCarPermission = false
            CarPairing.start(host)      // без разрешения выбор всё равно возможен, refreshWireless предупредит
        }
        refreshWireless()
    }

    override fun onActivityResultDone() = refreshWireless()

    /** Беспровод (бета): пояснение, старт/стоп, выбор машины для автостарта по Bluetooth, отчёт. */
    private fun wirelessCard(): View {
        val c = ctx
        val card = c.vbox(8).apply {
            background = c.shape(c.clr(R.color.surf), 20)
            pad(c, 16, 12, 16, 12)
        }
        card.addView(c.text(c.getString(R.string.wl_note), 14f, 400, c.clr(R.color.on2)), lp(MATCH, WRAP))
        wlStatus = c.text("", 15f, 500).also { card.addView(it, lp(MATCH, WRAP)) }
        wlNoPermission = c.text(c.getString(R.string.wl_no_permission), 13f, 400, c.clr(R.color.warn)).apply { visibility = View.GONE }
            .also { card.addView(it, lp(MATCH, WRAP)) }
        // высота кнопок задаётся явно (как на других экранах): pill с WRAP сжимается до высоты текста
        card.addView(c.primaryButton(c.getString(R.string.wl_start), heightDp = 60, sp = 17f).apply { setOnClickListener { onWirelessStart() } },
            lp(MATCH, c.dp(60)).apply { topMargin = c.dp(4) })
        card.addView(c.outlineButton(c.getString(R.string.wl_stop), heightDp = 56, sp = 16f).apply { setOnClickListener { Actions.stopBridge(c) } },
            lp(MATCH, c.dp(56)))
        // выбор машины: дальше при её Bluetooth-подключении ожидание по Wi-Fi стартует само
        wlCarLine = c.text("", 15f, 500).also { card.addView(it, lp(MATCH, WRAP).apply { topMargin = c.dp(8) }) }
        card.addView(c.text(c.getString(R.string.wl_car_note), 13f, 400, c.clr(R.color.on2)), lp(MATCH, WRAP))
        wlCarWarn = c.text("", 13f, 400, c.clr(R.color.warn)).apply { visibility = View.GONE }.also { card.addView(it, lp(MATCH, WRAP)) }
        card.addView(c.outlineButton(c.getString(R.string.wl_car_pick), heightDp = 56, sp = 16f).apply { setOnClickListener { onCarPick() } },
            lp(MATCH, c.dp(56)))
        wlCarForget = c.outlineButton(c.getString(R.string.wl_car_forget), heightDp = 56, sp = 16f).apply {
            setOnClickListener { CarPairing.forget(c); refreshWireless() }
        }.also { card.addView(it, lp(MATCH, c.dp(56))) }
        // Wi-Fi машины: телефон входит в её точку доступа сам (WifiNetworkSpecifier), интернет остаётся на основной сети
        val cw = CarWifi(c)
        wlCwLine = c.text("", 15f, 500).also { card.addView(it, lp(MATCH, WRAP).apply { topMargin = c.dp(8) }) }
        card.addView(c.text(c.getString(R.string.wl_cw_note), 13f, 400, c.clr(R.color.on2)), lp(MATCH, WRAP))
        wlCwSsid = wifiField(c.getString(R.string.wl_cw_ssid_hint), password = false).apply { setText(cw.ssid.orEmpty()) }
            .also { card.addView(it, lp(MATCH, c.dp(52))) }
        wlCwPass = wifiField(c.getString(R.string.wl_cw_pass_hint), password = true).apply { setText(cw.pass) }
            .also { card.addView(it, lp(MATCH, c.dp(52))) }
        wlCwResult = c.text("", 13f, 400, c.clr(R.color.on2)).apply { visibility = View.GONE }.also { card.addView(it, lp(MATCH, WRAP)) }
        card.addView(c.outlineButton(c.getString(R.string.wl_cw_save), heightDp = 56, sp = 16f).apply { setOnClickListener { onCarWifiSave() } },
            lp(MATCH, c.dp(56)))
        wlCwForget = c.outlineButton(c.getString(R.string.wl_cw_forget), heightDp = 56, sp = 16f).apply {
            setOnClickListener { onCarWifiForget() }
        }.also { card.addView(it, lp(MATCH, c.dp(56))) }
        card.addView(c.linkButton(c.getString(R.string.wl_report), heightDp = 52, sp = 16f).apply { setOnClickListener { LogShare.shareReport(c) } },
            lp(MATCH, c.dp(52)))
        refreshWireless()
        return card
    }

    private fun refreshWireless() {
        val st = wlStatus ?: return
        val c = ctx
        val text = c.getString(R.string.wl_status_line, CarLifeState.wireless ?: c.getString(R.string.wl_off))
        if (st.text.toString() != text) st.text = text
        val car = CarProfile(c)
        val carText = c.getString(R.string.wl_car_line, if (car.isSet) car.btName ?: car.btMac ?: "" else c.getString(R.string.wl_car_none))
        wlCarLine?.let { if (it.text.toString() != carText) it.text = carText }
        wlCarForget?.visibility = if (car.isSet) View.VISIBLE else View.GONE
        // без BLUETOOTH_CONNECT (31+) система не доставляет ACL выбранной машины: триггер молчит
        val warn = when {
            !CarPairing.available(c) -> c.getString(R.string.wl_car_unavailable)
            car.isSet && !carBtGranted() -> c.getString(R.string.wl_car_no_permission)
            else -> ""
        }
        wlCarWarn?.let {
            if (it.text.toString() != warn) it.text = warn
            it.visibility = if (warn.isEmpty()) View.GONE else View.VISIBLE
        }
        val cw = CarWifi(c)      // SSID на экране показываем, в логи и отчёт он не уходит
        val cwText = c.getString(R.string.wl_cw_line, if (cw.isSet) c.getString(R.string.wl_cw_set, cw.ssid) else c.getString(R.string.wl_cw_none))
        wlCwLine?.let { if (it.text.toString() != cwText) it.text = cwText }
        wlCwForget?.visibility = if (cw.isSet) View.VISIBLE else View.GONE
    }

    private fun wifiField(hint: String, password: Boolean): EditText = EditText(ctx).apply {
        this.hint = hint
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(ctx.clr(R.color.on))
        setHintTextColor(ctx.clr(R.color.on2))
        typeface = ctx.sans(400)
        background = ctx.shape(ctx.clr(R.color.surf2), 14)
        pad(ctx, 16, 0, 16, 0)
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or
            if (password) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        typeface = ctx.sans(400)      // setInputType для пароля сам ставит моноширинный
    }

    private fun showCwResult(text: String, color: Int = R.color.on2) {
        wlCwResult?.apply {
            this.text = text
            setTextColor(ctx.clr(color))
            visibility = View.VISIBLE
        }
    }

    /**
     * «Сохранить и проверить»: запрос сети машины с переднего плана, чтобы человек увидел и одобрил системный диалог (из фона
     * его не показать). Это только одобрение/проверка: через 5 с после входа запрос снимаем, дальше его держит сервис.
     */
    private fun onCarWifiSave() {
        val ssid = wlCwSsid?.text?.toString().orEmpty()
        val pass = wlCwPass?.text?.toString().orEmpty()
        if (ssid.isEmpty()) {
            showCwResult(ctx.getString(R.string.wl_cw_need_ssid), R.color.warn)
            return
        }
        CarWifi(ctx).save(ssid, pass)
        wlCwCheck?.release("recheck")
        val net = CarNetwork(ctx)
        wlCwCheck = net
        showCwResult(ctx.getString(R.string.wl_cw_checking))
        val fail = {
            if (wlCwCheck === net) {
                showCwResult(ctx.getString(R.string.wl_cw_fail), R.color.warn)
                wlCwCheck = null
            }
        }
        net.request(
            ssid, pass, CW_CHECK_TIMEOUT_MS,
            onAvailable = {
                if (wlCwCheck === net) {
                    showCwResult(ctx.getString(R.string.wl_cw_ok), R.color.ok)
                    cwHandler.postDelayed({
                        if (wlCwCheck === net) {
                            net.release("check")
                            wlCwCheck = null
                        }
                    }, CW_CHECK_HOLD_MS)
                }
            },
            onUnavailable = { fail() },
            onLost = { fail() },
        )
        refreshWireless()
    }

    private fun onCarWifiForget() {
        CarWifi(ctx).clear()
        wlCwCheck?.release("forget")
        wlCwCheck = null
        wlCwSsid?.setText("")
        wlCwPass?.setText("")
        wlCwResult?.visibility = View.GONE
        refreshWireless()
    }

    private fun carBtGranted() =
        Build.VERSION.SDK_INT < 31 || ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** На Android 12+ перед выбором просим BLUETOOTH_CONNECT (имя устройства, доставка ACL); отказ выбор не блокирует. */
    private fun onCarPick() {
        if (!carBtGranted()) {
            wlAwaitingCarPermission = true
            host.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_CAR_BT)
            return
        }
        CarPairing.start(host)
    }

    /**
     * На Android 17+ без ACCESS_LOCAL_NETWORK LAN режется молча: сначала спрашиваем, старт - в onPermissionResult.
     * BLUETOOTH_CONNECT (Android 12+) и «Устройства поблизости» (Wi-Fi Direct) - для ветки A, без них беспровод всё равно стартует.
     */
    private fun onWirelessStart() {
        wlNoPermission?.visibility = View.GONE
        val ask = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 37 && NetProbe.localNetworkGranted(ctx) != "granted") ask += Manifest.permission.ACCESS_LOCAL_NETWORK
        if (!carBtGranted()) ask += Manifest.permission.BLUETOOTH_CONNECT
        // ветка A: вход в Wi-Fi Direct-группу ГУ; без него беспровод стартует, но только веткой B
        val p2p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        if (ctx.checkSelfPermission(p2p) != PackageManager.PERMISSION_GRANTED) ask += p2p
        if (ask.isNotEmpty()) {
            wlAwaitingPermission = true
            host.requestPermissions(ask.toTypedArray(), REQ_LOCAL_NETWORK)
            return
        }
        startWireless()
    }

    private fun startWireless() {
        AppLog.i("кнопка «Ждать машину по Wi-Fi»")
        ctx.startForegroundService(Intent(ctx, BridgeService::class.java).setAction(BridgeService.ACTION_WIFI_B))
    }

    private companion object {
        /** Тот же код, что MainActivity.REQ_LOCAL_NETWORK: результат попадает в её лог. */
        const val REQ_LOCAL_NETWORK = 37
        /** BLUETOOTH_CONNECT перед выбором машины (38 занят диалогом CDM, CarPairing.REQ_CAR_PAIR). */
        const val REQ_CAR_BT = 39
        /** Проверка Wi-Fi машины из настроек: сколько ждём одобрения и входа, и сколько держим сеть после входа. */
        const val CW_CHECK_TIMEOUT_MS = 60_000L
        const val CW_CHECK_HOLD_MS = 5_000L
    }

    private fun note(text: String, color: Int = R.color.on2): TextView =
        ctx.text(text, 13f, 400, ctx.clr(color)).also { body.addView(it, lp(WRAP, WRAP).margins(ctx, 8, 2, 8, 0)) }

    // --- язык и тема ---

    private fun buildAppearance() {
        val c = ctx
        body.addView(c.sectionTitle(c.getString(R.string.lang_title)))
        for ((tag, titleRes) in LANGS) {
            val row = RadioRow(c, c.getString(titleRes), "") { setLang(tag) }
            langRows[tag] = row
            body.addView(row.view, lp(MATCH, WRAP))
        }
        refreshLang()

        body.addView(c.sectionTitle(c.getString(R.string.theme_title)))
        for ((mode, titleRes) in THEMES) {
            val row = RadioRow(c, c.getString(titleRes), "") { setTheme(mode) }
            themeRows[mode] = row
            body.addView(row.view, lp(MATCH, WRAP))
        }
        refreshTheme()
    }

    private fun setLang(tag: String) {
        if (AppLocale.get(ctx) == tag) return
        AppLocale.set(ctx, tag)
        refreshLang()
        if (AppLocale.needsRecreate) host.recreate()      // на 33+ activity пересоздаёт система
    }

    private fun refreshLang() {
        val cur = AppLocale.get(ctx)
        for ((tag, row) in langRows) row.setSelected(cur == tag)
    }

    private fun setTheme(mode: String) {
        if (AppTheme.get(ctx) == mode) return
        AppTheme.set(ctx, mode)
        refreshTheme()
        host.recreate()      // тема и цвета читаются при создании activity
    }

    private fun refreshTheme() {
        val cur = AppTheme.get(ctx)
        for ((mode, row) in themeRows) row.setSelected(cur == mode)
    }

    // --- видео ---

    private fun buildVideo() {
        val c = ctx
        body.addView(c.sectionTitle(c.getString(R.string.set_mode)))
        val seg = Segmented(c, listOf(c.getString(R.string.set_transcode_title), c.getString(R.string.set_direct_title)), 15f) { setTranscode(it == 0) }
        transcodeSeg = seg
        body.addView(seg.view, lp(MATCH, WRAP))
        transcodeNote = note("")

        body.addView(c.sectionTitle(c.getString(R.string.set_resolution)))
        for (m in VideoMode.values()) {
            val row = RadioRow(c, c.getString(m.titleRes), c.getString(m.subRes)) { setVideoMode(m) }
            modeRows[m] = row
            body.addView(row.view, lp(MATCH, WRAP))
        }
        note(c.getString(R.string.set_apply_next))

        body.addView(c.sectionTitle(c.getString(R.string.set_aa_density)))
        body.addView(densityCard(), lp(MATCH, WRAP))
        note(c.getString(R.string.set_aa_density_note))

        body.addView(c.sectionTitle(c.getString(R.string.set_fps)))
        val fps = FpsSetting.values()
        val fSeg = Segmented(c, fps.map { c.getString(it.titleRes) }, 15f) { setFps(fps[it]) }
        fpsSeg = fSeg
        body.addView(fSeg.view, lp(MATCH, WRAP))
        fpsNote = note("")
        note(c.getString(R.string.set_fps_note))

        setTranscode(aa.transcode, save = false)
        refreshFps()
    }

    private fun setTranscode(on: Boolean, save: Boolean = true) {
        if (save) aa.transcode = on
        transcodeSeg?.setSelected(if (on) 0 else 1)
        transcodeNote?.text = ctx.getString(if (on) R.string.set_transcode_sub else R.string.set_direct_sub)
        refreshVideoMode()
    }

    private fun setVideoMode(m: VideoMode) {
        if (m.needsTranscode && !aa.transcode) return      // кроп возможен только при перекодировании
        aa.videoMode = m
        refreshVideoMode()
    }

    /** Без перекодирования «широкий» недоступен: радио затемнено, фактический режим 1280×720 (в prefs выбор остаётся). */
    private fun refreshVideoMode() {
        val shown = aa.videoMode.effective(aa.transcode)
        for ((m, row) in modeRows) {
            val blocked = m.needsTranscode && !aa.transcode
            row.setSelected(m == shown)
            row.setSub(if (blocked) ctx.getString(R.string.set_needs_transcode) else ctx.getString(m.subRes))
            row.view.alpha = if (blocked) 0.45f else 1f
        }
        refreshDensity()      // «авто» зависит от режима
    }

    /** «−  значение  +  Авто»: 11 вариантов (авто и 100..280 через 20) не влезают в Segmented, поэтому степпер. */
    private fun densityCard(): View {
        val c = ctx
        fun stepButton(label: String, desc: String, up: Boolean) = c.text(label, 24f, 500, c.clr(R.color.acc)).apply {
            gravity = Gravity.CENTER
            contentDescription = desc
            background = c.rippleBg(c.shape(c.clr(R.color.pric), 24), 24)
            isClickable = true
            setOnClickListener { setDensity(AaDensity.step(aa.aaDensity, autoDensity(), up)) }
        }
        val minus = stepButton("−", c.getString(R.string.set_aa_density_smaller), false)
        val plus = stepButton("+", c.getString(R.string.set_aa_density_larger), true)
        val value = c.text("", 17f, 600).apply { gravity = Gravity.CENTER }
        val sub = c.text("", 13f, 400, c.clr(R.color.on2)).apply { gravity = Gravity.CENTER }
        val auto = c.text(c.getString(R.string.set_aa_density_reset), 15f, 600).apply {
            gravity = Gravity.CENTER
            pad(c, 14, 0, 14, 0)
            isClickable = true
            setOnClickListener { setDensity(AaDensity.AUTO) }
        }
        densityMinus = minus
        densityPlus = plus
        densityValue = value
        densitySub = sub
        densityAuto = auto
        val row = c.hbox(10).apply {
            background = c.shape(c.clr(R.color.surf), 20)
            pad(c, 12, 10, 12, 10)
            addView(minus, c.lpDp(c.dp(48), 48))
            addView(c.vbox(2).apply {
                addView(value, lp(MATCH, WRAP))
                addView(sub, lp(MATCH, WRAP))
            }, lp(0, WRAP, 1f))
            addView(plus, c.lpDp(c.dp(48), 48))
            addView(auto, c.lpDp(WRAP, 44))
        }
        refreshDensity()
        return row
    }

    /** density режима, который реально применится (с учётом passthrough). */
    private fun autoDensity(): Int = aa.videoMode.effective(aa.transcode).density

    private fun setDensity(v: Int) {
        aa.aaDensity = v
        refreshDensity()
    }

    private fun refreshDensity() {
        val value = densityValue ?: return
        val c = ctx
        val cur = aa.aaDensity
        val auto = autoDensity()
        val isAuto = cur == AaDensity.AUTO
        value.text = if (isAuto) c.getString(R.string.set_aa_density_auto, auto) else c.getString(R.string.set_aa_density_manual, cur)
        densitySub?.text = if (isAuto) c.getString(R.string.set_aa_density_auto_sub) else c.getString(R.string.set_aa_density_manual_sub, auto)
        for ((b, up) in listOf(densityMinus to false, densityPlus to true)) {
            val can = AaDensity.step(cur, auto, up) != cur
            b?.isEnabled = can
            b?.alpha = if (can) 1f else 0.35f
        }
        densityAuto?.apply {
            background = c.rippleBg(if (isAuto) c.shape(c.clr(R.color.pric), 22) else c.shape(0, 22, c.clr(R.color.line), 1.5f), 22)
            setTextColor(c.clr(if (isAuto) R.color.onpric else R.color.on))
            text = if (isAuto) "✓ ${c.getString(R.string.set_aa_density_reset)}" else c.getString(R.string.set_aa_density_reset)
        }
    }

    private fun setFps(f: FpsSetting) {
        aa.fpsSetting = f
        refreshFps()
    }

    private fun refreshFps() {
        fpsSeg?.setSelected(FpsSetting.values().indexOf(aa.fpsSetting))
        fpsNote?.text = ctx.getString(aa.fpsSetting.subRes)
    }

    // --- звук и микрофон ---

    private fun buildSound() {
        val c = ctx
        body.addView(c.sectionTitle(c.getString(R.string.set_sound)))
        body.addView(ToggleRow(c, c.getString(R.string.set_usb_audio_title), c.getString(R.string.set_usb_audio_sub), aa.usbAudio) {
            aa.usbAudio = it
            refreshEqEntry()
        }.view)
        body.addView(eqEntry(), lp(MATCH, WRAP).margins(c, 0, 6, 0, 0))

        body.addView(c.sectionTitle(c.getString(R.string.set_music_rate)))
        for (r in MusicRate.values()) {
            val row = RadioRow(c, c.getString(r.titleRes), "") { setMusicRate(r) }
            rateRows[r] = row
            body.addView(row.view, lp(MATCH, WRAP))
        }
        note(c.getString(R.string.set_music_rate_note))
        refreshMusicRate()

        body.addView(c.sectionTitle(c.getString(R.string.set_mic)))
        for ((m, titleRes, subRes) in MICS) {
            val row = RadioRow(c, c.getString(titleRes), c.getString(subRes)) { setMic(m) }
            micRows[m] = row
            body.addView(row.view, lp(MATCH, WRAP))
        }
        micNote = note("", R.color.warn)
        refreshMic()
    }

    /** Строка «Эквалайзер · пресет ›» под «Звук по USB»: открывает экран EQ; без звука по USB затемнена. */
    private fun eqEntry(): View {
        val c = ctx
        val sub = c.text("", 14f, 400, c.clr(R.color.on2))
        eqEntrySub = sub
        return c.hbox(12).apply {
            minimumHeight = c.dp(72)
            pad(c, 16, 12, 12, 12)
            background = c.rippleBg(c.shape(c.clr(R.color.surf), 20), 20)
            isClickable = true
            setOnClickListener { host.showSettingsSection(SettingsSection.EQ) }
            addView(c.vbox(2).apply {
                addView(c.text(c.getString(R.string.eq_title), 17f, 500))
                addView(sub)
            }, lp(0, WRAP, 1f))
            addView(c.icon(R.drawable.ic_chevron, 20, c.clr(R.color.on2)))
            eqEntryRow = this
        }.also { refreshEqEntry() }
    }

    private fun refreshEqEntry() {
        val sub = eqEntrySub ?: return
        val usb = aa.usbAudio
        sub.text = if (usb) ctx.eqPresetName(aa.eqPreset) else ctx.getString(R.string.eq_sum_usb_only)
        eqEntryRow?.alpha = if (usb) 1f else 0.45f
    }

    private fun setMusicRate(r: MusicRate) {
        aa.musicRate = r
        refreshMusicRate()
    }

    private fun refreshMusicRate() {
        for ((r, row) in rateRows) row.setSelected(aa.musicRate == r)
    }

    private fun setMic(m: MicMode) {
        aa.micMode = m
        if (m != MicMode.CAR && ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            host.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
        }
        refreshMic()
    }

    private fun refreshMic() {
        val note = micNote ?: return
        for ((m, row) in micRows) row.setSelected(aa.micMode == m)
        val missing = ctx.micMissing(aa)
        note.text = if (missing) ctx.getString(R.string.mic_no_permission) else ""
        note.visibility = if (missing) View.VISIBLE else View.GONE
    }

    // --- эквалайзер ---

    private fun buildEq() {
        val c = ctx
        eqUsbNote = note(c.getString(R.string.eq_usb_only), R.color.warn)
        body.addView(c.sectionTitle(c.getString(R.string.eq_presets)))
        // каждая кнопка отдельная пилюля, по две в ряд
        val grid = c.vbox(8)
        for (row in EQ_PRESETS.chunked(2)) {
            val line = c.hbox(8)
            for ((p, titleRes) in row) {
                val b = c.text(c.getString(titleRes), 15f, 600).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    pad(c, 8, 0, 8, 0)
                    isClickable = true
                    setOnClickListener { pickEqPreset(p) }
                }
                eqPresetBtns.add(b to p)
                line.addView(b, lp(0, c.dp(48), 1f))
            }
            grid.addView(line, lp(MATCH, WRAP))
        }
        body.addView(grid, lp(MATCH, WRAP))

        body.addView(c.sectionTitle(c.getString(R.string.eq_bands)))
        val bands = c.card().apply { pad(c, 0, 6, 0, 6) }
        for ((i, f) in EQ_FREQS.withIndex()) {
            val (row, bar, value) = eqSliderRow(freqLabel(f), EQ_BAND_MIN, EQ_BAND_MAX) { setEqBand(i, it) }
            eqBars.add(bar)
            eqValues.add(value)
            bands.addView(row)
        }
        body.addView(bands, lp(MATCH, WRAP))

        body.addView(c.sectionTitle(c.getString(R.string.eq_preamp)))
        val pre = c.card().apply { pad(c, 0, 6, 0, 6) }
        val (preRow, preBar, preValue) = eqSliderRow("", EQ_PREAMP_MIN, EQ_PREAMP_MAX) { setEqPreamp(it) }
        eqPreBar = preBar
        eqPreValue = preValue
        pre.addView(preRow)
        body.addView(pre, lp(MATCH, WRAP))

        body.addView(c.tonalButton(c.getString(R.string.eq_reset)).apply { setOnClickListener { pickEqPreset(EqPreset.FLAT) } },
            c.lpDp(MATCH, 52).margins(c, 0, 12, 0, 0))
        note(c.getString(R.string.eq_note))
        refreshEq()
    }

    /** Строка «подпись | слайдер | значение в дБ»; onChange только от пальца. */
    private fun eqSliderRow(label: String, min: Int, max: Int, onChange: (Int) -> Unit): Triple<View, SeekBar, TextView> {
        val c = ctx
        val value = c.text("", 14f, 500, c.clr(R.color.on2)).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        val bar = SeekBar(c).apply {
            this.max = max - min
            progressTintList = ColorStateList.valueOf(c.clr(R.color.acc))
            thumbTintList = ColorStateList.valueOf(c.clr(R.color.acc))
            progressBackgroundTintList = ColorStateList.valueOf(c.clr(R.color.line))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) onChange(progress + min)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}

                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        val row = c.hbox(4).apply {
            pad(c, 16, 0, 16, 0)
            if (label.isNotEmpty()) addView(c.text(label, 14f, 500).apply { maxLines = 1 }, c.lpDp(c.dp(64), 44))
            addView(bar, c.lpDp(0, 44, 1f))
            addView(value, c.lpDp(c.dp(64), 44))
        }
        return Triple(row, bar, value)
    }

    private fun freqLabel(f: Int): String {
        if (f < 1000) return ctx.getString(R.string.eq_hz, f.toString())
        val nf = NumberFormat.getInstance(ctx.resources.configuration.locales[0]).apply { maximumFractionDigits = 1 }
        return ctx.getString(R.string.eq_khz, nf.format(f / 1000.0))
    }

    private fun dbLabel(v: Int): String = ctx.getString(R.string.eq_db, if (v > 0) "+$v" else if (v < 0) "−${-v}" else "0")

    /** Любое движение слайдера = «Свой»; его значения запоминаются отдельно. */
    private fun applyCustomEq(s: EqSettings) {
        aa.eq = s
        aa.eqCustom = s
        aa.eqPreset = EqPreset.CUSTOM
        refreshEq()
    }

    private fun setEqBand(i: Int, v: Int) {
        val cur = aa.eq
        applyCustomEq(cur.copy(bands = cur.bands.toMutableList().also { it[i] = v }))
    }

    private fun setEqPreamp(v: Int) = applyCustomEq(aa.eq.copy(preamp = v))

    private fun pickEqPreset(p: EqPreset) {
        aa.eq = p.settings ?: aa.eqCustom
        aa.eqPreset = p
        refreshEq()
    }

    private fun refreshEq() {
        val usbNote = eqUsbNote ?: return
        val s = aa.eq
        for ((i, bar) in eqBars.withIndex()) {
            val v = s.bands[i]
            if (bar.progress != v - EQ_BAND_MIN) bar.progress = v - EQ_BAND_MIN
            eqValues[i].text = dbLabel(v)
        }
        eqPreBar?.let { if (it.progress != s.preamp - EQ_PREAMP_MIN) it.progress = s.preamp - EQ_PREAMP_MIN }
        eqPreValue?.text = dbLabel(s.preamp)
        val preset = aa.eqPreset
        val c = ctx
        for ((b, p) in eqPresetBtns) {
            val on = p == preset
            b.background = c.rippleBg(if (on) c.shape(c.clr(R.color.pric), 24) else c.shape(0, 24, c.clr(R.color.line), 1.5f), 24)
            b.setTextColor(c.clr(if (on) R.color.onpric else R.color.on))
            val title = c.eqPresetName(p)
            b.text = if (on) "✓ $title" else title
        }
        usbNote.visibility = if (aa.usbAudio) View.GONE else View.VISIBLE
    }

    // --- головное устройство ---

    private fun buildHeadunit() {
        val c = ctx
        body.addView(c.sectionTitle(c.getString(R.string.set_cert)))
        body.addView(certCard())
        body.addView(c.sectionTitle(c.getString(R.string.set_autostart)))
        body.addView(autostartCard())
        body.addView(c.sectionTitle(c.getString(R.string.set_car_make)))
        val value = c.text("", 13f, 400, c.clr(R.color.on2))
        makeValue = value
        val row = c.hbox(14).apply {
            pad(c, 16, 0, 16, 0)
            background = c.rippleBg(null, 0)
            isClickable = true
            setOnClickListener { pickCarMake() }
            addView(c.icon(R.drawable.ic_car, 22, c.clr(R.color.on2)))
            addView(c.text(c.getString(R.string.set_car_make), 16f, 500), lp(0, WRAP, 1f))
            addView(value)
            addView(c.icon(R.drawable.ic_chevron, 20, c.clr(R.color.on2)))
            layoutParams = c.lpDp(MATCH, 56)
        }
        body.addView(c.card().apply { addView(row) }, lp(MATCH, WRAP))
        note(c.getString(R.string.set_car_make_note))
        refreshCarMake()
    }

    private fun pickCarMake() {
        val makes = listOf<String?>(null) + CAR_MAKES
        val labels = makes.map { it ?: ctx.getString(R.string.set_car_make_default) }.toTypedArray()
        AlertDialog.Builder(ctx, R.style.Theme_Headswap_Dialog)
            .setTitle(R.string.set_car_make)
            .setSingleChoiceItems(labels, makes.indexOf(aa.carMake)) { d, i ->
                aa.carMake = makes[i]
                refreshCarMake()
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshCarMake() {
        makeValue?.text = aa.carMake ?: ctx.getString(R.string.set_car_make_default_short)
    }

    private fun certCard(): View {
        val c = ctx
        val card = c.vbox(8).apply {
            background = c.shape(c.clr(R.color.surf), 20)
            pad(c, 12, 12, 12, 12)
        }
        val sets = listOf("jvc", "hur")
        val seg = Segmented(c, sets.map { it.uppercase() }, 16f) {
            aa.certSet = sets[it]
            certSeg?.setSelected(it)
        }
        certSeg = seg
        seg.setSelected(sets.indexOf(aa.certSet))
        card.addView(seg.view, lp(MATCH, WRAP))
        card.addView(c.text(c.getString(R.string.set_cert_hint), 14f, 400, c.clr(R.color.on2)).apply { pad(c, 6, 0, 6, 0) })
        return card
    }

    private fun autostartCard(): View {
        val c = ctx
        val card = c.vbox().apply { background = c.shape(c.clr(R.color.surf), 20) }
        card.addView(
            ToggleRow(c, c.getString(R.string.set_autostart), c.getString(R.string.set_autostart_sub), aa.autoServer, 72, false, 17f, 14f) { aa.autoServer = it }.view,
            lp(MATCH, WRAP),
        )
        card.addView(c.divider())
        val row = c.hbox(12).apply { pad(c, 16, 10, 12, 10); minimumHeight = c.dp(64) }
        val status = c.text("", 14f, 400, c.clr(R.color.on2))
        val button = c.text(c.getString(R.string.set_autostart_enable), 15f, 600, c.clr(R.color.acc)).apply {
            gravity = Gravity.CENTER
            pad(c, 14, 0, 14, 0)
            background = c.rippleBg(null, 20)
            isClickable = true
            setOnClickListener { Actions.enableServiceDialog(c) }
        }
        svcStatus = status
        svcButton = button
        row.addView(status, lp(0, WRAP, 1f))
        row.addView(button, c.lpDp(WRAP, 44))
        card.addView(row, lp(MATCH, WRAP))
        refreshService()
        return card
    }

    private fun refreshService() {
        val status = svcStatus ?: return
        val c = ctx
        val connected = AutoServer.isConnected
        status.text = c.getString(
            when {
                connected -> R.string.set_svc_connected
                AutoServer.isListed(c) -> R.string.set_svc_not_connected
                else -> R.string.set_svc_off
            },
        )
        status.setTextColor(c.clr(if (connected) R.color.ok else R.color.warn))
        svcButton?.visibility = if (connected) View.GONE else View.VISIBLE
    }

    // --- приложения для магнитолы ---

    private fun buildApps() {
        val c = ctx
        body.addView(
            c.hbox(12).apply {
                background = c.shape(c.clr(R.color.warnc), 16)
                pad(c, 14, 12, 14, 12)
                addView(c.icon(R.drawable.ic_alert, 22, c.clr(R.color.warn)))
                addView(c.text(c.getString(R.string.apps_warn_banner), 14f, 500).apply { lineHeight = c.dp(20) }, lp(0, WRAP, 1f))
            },
            lp(MATCH, WRAP).margins(c, 0, 8, 0, 0),
        )

        appsHost = c.vbox(6).also { body.addView(it, lp(MATCH, WRAP)) }

        body.addView(c.sectionTitle(c.getString(R.string.apps_hint_title)))
        note(c.getString(R.string.apps_hint_text))
        refreshApps()
    }

    private fun appButton(label: String, onClick: () -> Unit): TextView {
        val c = ctx
        return c.text(label, 15f, 600, c.clr(R.color.acc)).apply {
            gravity = Gravity.CENTER
            pad(c, 14, 0, 14, 0)
            background = c.rippleBg(null, 20)
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    /** Найденное на телефоне: иконка и имя самого приложения, описание из каталога (если есть), подпись про «Неизвестные источники». */
    private fun installedAppRow(pkg: String, label: CharSequence, icon: Drawable?, catalog: HuApp?): View {
        val c = ctx
        val canOpen = HuApps.launchIntent(c, pkg) != null
        return c.hbox(12).apply {
            pad(c, 16, 12, 12, 12)
            icon?.let { addView(ImageView(c).apply { setImageDrawable(it) }, c.lpDp(c.dp(24), 24)) }
            addView(c.vbox(4).apply {
                addView(c.text(label, 17f, 500))
                catalog?.let { addView(c.text(c.getString(it.descRes), 14f, 400, c.clr(R.color.on2))) }
                if (catalog != null && HuApps.status(c, catalog).unsupported) {
                    addView(c.text(c.getString(R.string.apps_unsupported), 13f, 400, c.clr(R.color.warn)))
                }
            }, lp(0, WRAP, 1f))
            if (canOpen) addView(appButton(c.getString(R.string.apps_open)) { HuApps.open(c, pkg) }, c.lpDp(WRAP, 44))
        }
    }

    /** Запись каталога, которой на телефоне нет: описание, требование, «Скачать». */
    private fun recommendedAppRow(app: HuApp): View {
        val c = ctx
        val unsupported = HuApps.status(c, app).unsupported
        return c.vbox(6).apply {
            pad(c, 16, 12, 12, 8)
            addView(c.text(app.name, 17f, 500))
            addView(c.text(c.getString(app.descRes), 14f, 400, c.clr(R.color.on2)))
            app.reqRes?.let { addView(c.text(c.getString(it), 13f, 400, c.clr(R.color.on2))) }
            if (unsupported) {
                addView(c.text(c.getString(R.string.apps_unsupported), 13f, 400, c.clr(R.color.warn)))
            } else {
                addView(c.hbox(4).apply {
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    addView(appButton(c.getString(R.string.apps_download)) { HuApps.download(c, app) }, c.lpDp(WRAP, 44))
                }, lp(MATCH, WRAP))
            }
        }
    }

    /** Поиск по PackageManager: зовётся при каждом возврате на экран (после браузера и установщика). */
    private fun refreshApps() {
        val host = appsHost ?: return
        val c = ctx
        val pm = c.packageManager
        val sel = HuApps.detect(c)
        host.removeAllViews()

        host.addView(c.sectionTitle(c.getString(R.string.apps_installed_title)))
        val rows = sel.installed.mapNotNull { pkg ->
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                Triple(pkg, info.loadLabel(pm), info)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }.sortedBy { it.second.toString().lowercase() }
        if (rows.isEmpty()) {
            host.addView(c.text(c.getString(R.string.apps_installed_empty), 14f, 400, c.clr(R.color.on2)).apply { lineHeight = c.dp(20) })
        } else {
            // одна подпись на всю секцию: в списке только приложения не из Play
            host.addView(c.text(c.getString(R.string.apps_installed_note), 13f, 400, c.clr(R.color.on2)).apply { lineHeight = c.dp(18) },
                lp(MATCH, WRAP).apply { bottomMargin = c.dp(8) })
            val card = c.card()
            for ((i, r) in rows.withIndex()) {
                if (i > 0) card.addView(c.divider())
                val icon = try { r.third.loadIcon(pm) } catch (_: Exception) { null }
                card.addView(installedAppRow(r.first, r.second, icon, HuApps.CATALOG.firstOrNull { it.pkg == r.first }), lp(MATCH, WRAP))
            }
            host.addView(card, lp(MATCH, WRAP))
        }

        if (sel.recommended.isNotEmpty()) {
            host.addView(c.sectionTitle(c.getString(R.string.apps_recommended_title)))
            val card = c.card()
            for ((i, app) in sel.recommended.withIndex()) {
                if (i > 0) card.addView(c.divider())
                card.addView(recommendedAppRow(app), lp(MATCH, WRAP))
            }
            host.addView(card, lp(MATCH, WRAP))
        }
    }

    /** Один раз за установку: видео на ходу не блокируем, ответственность на водителе. */
    private fun maybeShowAppsWarning() {
        if (aa.appsWarningShown) return
        aa.appsWarningShown = true
        AlertDialog.Builder(ctx, R.style.Theme_Headswap_Dialog)
            .setTitle(R.string.apps_warn_title)
            .setMessage(R.string.apps_warn_text)
            .setPositiveButton(R.string.apps_warn_ok, null)
            .show()
    }

    // --- обновления ---

    /** Переключатель проверки + строка состояния с кнопкой «Проверить» / «Обновить» (как карточка автозапуска). */
    private fun updateCard(): View {
        val c = ctx
        val prefs = UpdatePrefs(c)
        val card = c.vbox().apply { background = c.shape(c.clr(R.color.surf), 20) }
        card.addView(
            ToggleRow(c, c.getString(R.string.upd_check_title), c.getString(R.string.upd_check_sub), prefs.enabled, 72, false, 17f, 14f) {
                prefs.enabled = it
                if (it) Updater.maybeCheck(c)
            }.view,
            lp(MATCH, WRAP),
        )
        card.addView(c.divider())
        val row = c.hbox(12).apply { pad(c, 16, 10, 12, 10); minimumHeight = c.dp(64) }
        val status = c.text("", 14f, 400, c.clr(R.color.on2))
        val button = c.text("", 15f, 600, c.clr(R.color.acc)).apply {
            gravity = Gravity.CENTER
            pad(c, 14, 0, 14, 0)
            background = c.rippleBg(null, 20)
            isClickable = true
            setOnClickListener { if (Updater.available(c) != null) Updater.start(host, confirm = false) else Updater.check(c, manual = true) }
        }
        updStatus = status
        updButton = button
        row.addView(status, lp(0, WRAP, 1f))
        row.addView(button, c.lpDp(WRAP, 44))
        card.addView(row, lp(MATCH, WRAP))
        refreshUpdate()
        return card
    }

    private fun refreshUpdate() {
        val status = updStatus ?: return
        val button = updButton ?: return
        val c = ctx
        val (text, color) = c.updateStatus()
        if (status.text.toString() != text) status.text = text
        status.setTextColor(c.clr(color))
        val label = c.getString(if (Updater.available(c) != null) R.string.upd_btn_update_now else R.string.upd_btn_check)
        if (button.text.toString() != label) button.text = label
        button.visibility = if (Updater.step != Updater.Step.IDLE) View.GONE else View.VISIBLE
    }
}
