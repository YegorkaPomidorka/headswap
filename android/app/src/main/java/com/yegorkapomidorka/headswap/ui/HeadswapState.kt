package com.yegorkapomidorka.headswap.ui

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.yegorkapomidorka.headswap.CarLifeState
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.aa.AaCreds
import com.yegorkapomidorka.headswap.aa.AaPrefs
import com.yegorkapomidorka.headswap.aa.AaState
import com.yegorkapomidorka.headswap.autosrv.AutoResult
import com.yegorkapomidorka.headswap.autosrv.AutoServer

/** Сводка состояния для главного экрана и уведомления. Чистая логика без Android: считается из Inputs. */
enum class Phase { OFF, WAITING, CONNECTED, RECONNECTING, ERROR }

enum class StageState { DONE, ACTIVE, PAUSE, ERR, TODO, PEND }

enum class Tone { PRI, OK, WARN, ERR }

enum class HeroIcon { POWER, PLUG, CAR_USB, CAR_WIFI, REFRESH, ALERT }

enum class PrimaryAction { START_SERVER, RESTART_SERVER }

enum class OutlineAction { OPEN_AA, STOP }

data class HomeState(
    val phase: Phase,
    val tone: Tone,
    val icon: HeroIcon,
    val title: UiText,
    val sub: UiText,
    val stages: List<Pair<UiText, Pair<StageState, UiText>>>,
    /** Оставшиеся секунды grace (только RECONNECTING), иначе -1. */
    val graceLeftS: Int,
    val graceTotalS: Int,
    val primary: PrimaryAction?,
    val outline: OutlineAction?,
    val logLink: Boolean,
    val showChain: Boolean,
    val notifTitle: UiText,
    val notifText: UiText,
)

class Inputs(
    val bridgeRunning: Boolean,
    val aaStatus: String,
    val protocolKnown: Boolean,
    /** Сервер ГУ AA запущен (по нашему запуску), пока сессии нет. */
    val serverUp: Boolean,
    /** Автозапуск включён и служба спецвозможностей подключена: сервер поднимется сам при подключении кабеля. */
    val autoReady: Boolean,
    /** Предыдущая сессия оборвалась не по кнопке «Стоп». */
    val lost: Boolean,
    val graceLeftS: Int,
    val graceTotalS: Int,
    val usbAudio: Boolean,
    /** Мост не стартует из-за настройки (нет сертификата head unit): текст для пользователя. */
    val setupError: UiText? = null,
    /** Размер видео на ГУ («1280×720»): фактический режим сессии. */
    val videoRes: String = "1280×720",
    /** На телефоне висит мастер первой настройки Android Auto (согласие/разрешения): пока не пройден, AA рвёт связь. */
    val aaConsent: Boolean = false,
    /** Сессия идёт по Wi-Fi: CarLife и звук идут к ГУ по воздуху, кабеля нет. */
    val wireless: Boolean = false,
)

object HomeLogic {
    val LABELS = listOf(
        UiText.res(R.string.home_stage_server), UiText.res(R.string.home_stage_headunit), UiText.res(R.string.home_stage_aa),
        UiText.res(R.string.home_stage_video), UiText.res(R.string.home_stage_audio),
    )
    private val NONE = UiText.raw("")
    private val CARLIFE = UiText.raw("CarLife 2.0")
    private val CARLIFE_WIFI = UiText.raw("CarLife 2.0 · Wi-Fi")
    private val TEXT_START_CHAIN = UiText.res(R.string.home_sub_start_chain)

    fun compute(i: Inputs): HomeState {
        val st = i.aaStatus
        val reconnecting = i.bridgeRunning && "USB потерян" in st
        val connected = i.bridgeRunning && st.startsWith("мост: AA -> CarLife")
        val refused = i.bridgeRunning && "жду сервер ГУ" in st
        val total = if (i.graceTotalS > 0) i.graceTotalS else 8
        return when {
            i.setupError != null && !i.bridgeRunning -> state(
                Phase.ERROR, Tone.ERR, HeroIcon.ALERT, UiText.res(R.string.home_title_no_bridge), i.setupError,
                listOf(
                    StageState.ERR to UiText.res(R.string.home_stage_no_cert), StageState.PEND to NONE, StageState.PEND to NONE,
                    StageState.PEND to NONE, StageState.PEND to NONE,
                ),
                logLink = true,
                notifTitle = UiText.res(R.string.home_title_no_bridge), notifText = i.setupError,
            )
            i.aaConsent && i.bridgeRunning -> state(
                Phase.WAITING, Tone.WARN, HeroIcon.ALERT, UiText.res(R.string.home_title_aa_consent),
                UiText.res(R.string.home_sub_aa_consent),
                listOf(
                    StageState.DONE to UiText.res(R.string.home_stage_working), StageState.DONE to CARLIFE,
                    StageState.ACTIVE to UiText.res(R.string.home_stage_aa_consent),
                    StageState.PEND to NONE, StageState.PEND to NONE,
                ),
                outline = OutlineAction.OPEN_AA,
                notifText = UiText.res(R.string.notif_aa_consent),
            )
            reconnecting -> {
                val left = i.graceLeftS.coerceAtLeast(0)
                state(
                    Phase.RECONNECTING, Tone.WARN, HeroIcon.REFRESH, UiText.res(R.string.home_title_reconnecting),
                    UiText.res(R.string.home_sub_reconnecting, total),
                    listOf(
                        StageState.DONE to UiText.res(R.string.home_stage_holds), StageState.ACTIVE to UiText.res(R.string.home_stage_reconnecting),
                        StageState.PAUSE to UiText.res(R.string.home_stage_paused), StageState.PAUSE to UiText.res(R.string.home_stage_paused),
                        StageState.PAUSE to UiText.res(R.string.home_stage_paused),
                    ),
                    outline = OutlineAction.STOP, graceLeft = left, graceTotal = total,
                    notifText = UiText.res(R.string.notif_reconnecting, left),
                )
            }
            connected -> state(
                Phase.CONNECTED, Tone.OK, if (i.wireless) HeroIcon.CAR_WIFI else HeroIcon.CAR_USB, UiText.res(R.string.home_title_ok),
                UiText.res(if (i.wireless) R.string.home_sub_ok_wifi else R.string.home_sub_ok),
                listOf(
                    StageState.DONE to UiText.res(R.string.home_stage_working),
                    StageState.DONE to if (i.wireless) CARLIFE_WIFI else CARLIFE,
                    StageState.DONE to UiText.res(R.string.home_stage_connected),
                    StageState.DONE to UiText.raw(i.videoRes),
                    // настройка «Звук через Headswap» (бывш. «Звук через USB») - это звук по каналу CarLife; без кабеля он идёт по Wi-Fi
                    if (i.usbAudio) StageState.DONE to UiText.raw(if (i.wireless) "Wi-Fi" else "USB")
                    else StageState.PEND to UiText.res(R.string.home_stage_off),
                ),
                outline = OutlineAction.STOP,
                notifText = UiText.res(
                    when {
                        !i.usbAudio -> R.string.notif_video
                        i.wireless -> R.string.notif_video_wifi
                        else -> R.string.notif_video_usb
                    },
                ),
            )
            refused -> state(
                Phase.OFF, Tone.WARN, HeroIcon.POWER, UiText.res(R.string.home_title_off),
                UiText.res(R.string.home_sub_refused, TEXT_START_CHAIN),
                listOf(
                    StageState.TODO to UiText.res(R.string.home_stage_not_started), StageState.DONE to CARLIFE, StageState.PEND to NONE,
                    StageState.PEND to NONE, StageState.PEND to NONE,
                ),
                primary = PrimaryAction.START_SERVER, showChain = true,
                notifText = UiText.res(R.string.notif_server_off),
            )
            i.bridgeRunning -> state(
                Phase.WAITING, Tone.PRI, HeroIcon.PLUG, UiText.res(R.string.home_title_connecting),
                UiText.res(if ("отключился" in st) R.string.home_sub_aa_dropped else R.string.home_sub_cable_ok),
                listOf(
                    StageState.ACTIVE to UiText.res(R.string.home_stage_connecting),
                    if (i.protocolKnown) StageState.DONE to CARLIFE else StageState.ACTIVE to UiText.res(R.string.home_stage_handshake),
                    StageState.PEND to NONE, StageState.PEND to NONE, StageState.PEND to NONE,
                ),
                outline = OutlineAction.STOP,
                notifText = UiText.res(R.string.notif_cable_waiting),
            )
            i.lost -> state(
                Phase.ERROR, Tone.ERR, HeroIcon.ALERT, UiText.res(R.string.home_title_lost),
                UiText.res(R.string.home_sub_lost, total),
                listOf(
                    StageState.ERR to UiText.res(R.string.home_stage_closed), StageState.DONE to CARLIFE,
                    StageState.ERR to UiText.res(R.string.home_stage_disconnected),
                    StageState.PEND to NONE, StageState.PEND to NONE,
                ),
                primary = PrimaryAction.RESTART_SERVER, logLink = true,
                notifTitle = UiText.res(R.string.notif_restart_title), notifText = UiText.res(R.string.notif_lost, total),
            )
            i.serverUp || i.autoReady -> state(
                Phase.WAITING, Tone.PRI, HeroIcon.PLUG, UiText.res(R.string.home_title_ready),
                UiText.res(if (i.serverUp) R.string.home_sub_ready else R.string.home_sub_ready_auto),
                listOf(
                    StageState.DONE to UiText.res(if (i.serverUp) R.string.home_stage_started else R.string.home_stage_auto),
                    StageState.ACTIVE to UiText.res(R.string.home_stage_wait_cable),
                    StageState.PEND to NONE, StageState.PEND to NONE, StageState.PEND to NONE,
                ),
                outline = OutlineAction.OPEN_AA,
                notifText = UiText.res(if (i.serverUp) R.string.notif_server_started else R.string.notif_wait_cable),
            )
            else -> state(
                Phase.OFF, Tone.WARN, HeroIcon.POWER, UiText.res(R.string.home_title_off), TEXT_START_CHAIN,
                listOf(
                    StageState.TODO to UiText.res(R.string.home_stage_not_started), StageState.PEND to NONE, StageState.PEND to NONE,
                    StageState.PEND to NONE, StageState.PEND to NONE,
                ),
                primary = PrimaryAction.START_SERVER, showChain = true,
                notifText = UiText.res(R.string.notif_server_off),
            )
        }
    }

    private fun state(
        phase: Phase, tone: Tone, icon: HeroIcon, title: UiText, sub: UiText,
        stages: List<Pair<StageState, UiText>>,
        primary: PrimaryAction? = null, outline: OutlineAction? = null, logLink: Boolean = false,
        showChain: Boolean = false, graceLeft: Int = -1, graceTotal: Int = 0,
        notifTitle: UiText = title, notifText: UiText,
    ) = HomeState(
        phase, tone, icon, title, sub, LABELS.zip(stages), graceLeft, graceTotal,
        primary, outline, logLink, showChain, notifTitle, notifText,
    )
}

/**
 * Наблюдаемая сводка (только чтение из CarLifeState/AaState/AutoServer + то, что сама знает UI-часть):
 * собирает Inputs и помнит переходы (оборвалась ли сессия, когда подключились, до какого момента держится grace).
 * Вызывается с главной нити: экраном и тикером уведомления.
 */
object HeadswapStatus {
    private var prevRunning = false
    private var sawConnected = false
    private var userStopped = false
    private var lost = false
    private var serverUp = false
    private var lastOutcome: Any? = null
    private var graceDeadline = 0L
    private var graceTotalS = 0
    var connectedSince = 0L
        private set

    private val graceRe = Regex("""жива\s+(\d+)\s*с""")

    /** Пользователь нажал «Запустить сервер ГУ» (или открыл настройки AA): считаем сервер запущенным. */
    fun markServerRequested() {
        serverUp = true
        lost = false
        userStopped = false
    }

    /** «Остановить»: оборвавшейся связью это не считается, одноразовый сервер ГУ закрыт. */
    fun markUserStop() {
        userStopped = true
        lost = false
        serverUp = false
    }

    fun inputs(ctx: Context): Inputs {
        val now = SystemClock.elapsedRealtime()
        val running = CarLifeState.running
        val st = AaState.status
        val connectedNow = running && st.startsWith("мост: AA -> CarLife")
        val reconnectingNow = running && "USB потерян" in st

        if (running && !prevRunning) {
            lost = false
            userStopped = false
            sawConnected = false
            connectedSince = 0
        }
        if (connectedNow || reconnectingNow) sawConnected = true
        if (connectedNow) {
            if (connectedSince == 0L) connectedSince = System.currentTimeMillis()
        } else if (!reconnectingNow) {
            connectedSince = 0
        }
        if (reconnectingNow) {
            if (graceDeadline == 0L) {
                graceTotalS = graceRe.find(st)?.groupValues?.get(1)?.toIntOrNull() ?: 8
                graceDeadline = now + graceTotalS * 1000L
            }
        } else {
            graceDeadline = 0
        }
        if (!running && prevRunning) {
            if (sawConnected && !userStopped) lost = true
            serverUp = false
            sawConnected = false
            connectedSince = 0
        }
        prevRunning = running

        val last = AutoServer.last
        if (last !== lastOutcome) {
            lastOutcome = last
            if (last?.result == AutoResult.OK && !running) serverUp = true
        }

        val aaPrefs = AaPrefs(ctx)
        val left = if (graceDeadline > 0) ((graceDeadline - now + 999) / 1000).toInt() else -1
        return Inputs(
            bridgeRunning = running,
            aaStatus = st,
            protocolKnown = CarLifeState.protocol != "-",
            serverUp = serverUp,
            autoReady = aaPrefs.autoServer && AutoServer.isConnected,
            lost = lost,
            graceLeftS = left,
            graceTotalS = graceTotalS,
            usbAudio = aaPrefs.usbAudio,
            // известный текст нет-сертификата подменяем ресурсом, остальные ошибки показываем как есть
            setupError = AaState.setupError?.let { if (it == AaCreds.MISSING_TEXT) UiText.res(R.string.home_err_no_cert) else UiText.raw(it) },
            videoRes = (CarLifeState.videoMode ?: aaPrefs.videoGeometry.effective(aaPrefs.transcode)).outLabel,
            aaConsent = AutoServer.consentPending(),
            wireless = CarLifeState.wirelessSession,
        )
    }

    fun current(ctx: Context): HomeState = HomeLogic.compute(inputs(ctx))

    /** Подключён ли USB-аксессуар (для подсказок на экране). */
    fun accessoryPresent(ctx: Context): Boolean =
        ctx.getSystemService(UsbManager::class.java).accessoryList?.isNotEmpty() == true
}
