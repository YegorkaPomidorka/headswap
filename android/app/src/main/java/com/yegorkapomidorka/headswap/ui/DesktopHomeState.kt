package com.yegorkapomidorka.headswap.ui

import android.content.Context
import android.content.res.Configuration
import com.yegorkapomidorka.headswap.CarLifeState
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.aa.AaPrefs
import com.yegorkapomidorka.headswap.dex.DelegatedDesktopAccess
import com.yegorkapomidorka.headswap.dex.DesktopAudioState
import com.yegorkapomidorka.headswap.dex.DexPrefs
import com.yegorkapomidorka.headswap.dex.DexState
import com.yegorkapomidorka.headswap.dex.PhoneActivationState
import java.util.Locale

/** Главный экран в режиме «Свой лаунчер»: тот же скелет, что у Android Auto (HomeState), но свои этапы, тексты и кнопки. */
enum class DesktopPhase { ACTIVATE, WAITING, RECOVERING, ERROR, WORKING }

/** Кнопки внизу экрана, сверху вниз. */
enum class DesktopButton { ACTIVATE, STOP, DIAG }

data class DesktopHomeState(
    val phase: DesktopPhase,
    val tone: Tone,
    val icon: HeroIcon,
    val title: UiText,
    val sub: UiText,
    val stages: List<Pair<UiText, Pair<StageState, UiText>>>,
    val buttons: List<DesktopButton>,
)

class DesktopInputs(
    /** Активация READY: без неё рабочий стол не стартует. */
    val activationReady: Boolean,
    /** Ключ adb уже принят телефоном: повторной активации код не нужен. */
    val paired: Boolean,
    /** Сервис моста работает (CarLifeState.running). */
    val bridgeRunning: Boolean,
    /** Рабочий стол на виртуальном дисплее запущен (DexState.running). */
    val desktopRunning: Boolean,
    /** DexState.status сейчас - строка восстановления (desk_recovering). */
    val recovering: Boolean,
    val status: String,
    val error: String?,
    val wireless: Boolean = false,
    /** Размер видео текущей сессии («1280×720»). */
    val videoRes: String = "1280×720",
    /** Подпись выбранного лаунчера на экране машины. */
    val desktopName: String = "Headswap",
    /** Звук идёт по каналу CarLife (захват работает и в нём не тишина); иначе - Bluetooth магнитолы. */
    val usbAudio: Boolean = false,
)

object DesktopHomeLogic {
    val LABELS = listOf(
        UiText.res(R.string.home_desk_stage_activation), UiText.res(R.string.home_desk_stage_headunit),
        UiText.res(R.string.home_desk_stage_desktop), UiText.res(R.string.home_desk_stage_video),
        UiText.res(R.string.home_desk_stage_audio),
    )
    private val NONE = UiText.raw("")
    private val CARLIFE = UiText.raw("CarLife 2.0")
    private val CARLIFE_WIFI = UiText.raw("CarLife 2.0 · Wi-Fi")
    private val ACTIVE = UiText.res(R.string.home_desk_st_active)

    /** Порядок проверки: активация, ошибка, восстановление, работает, ждём. */
    fun compute(i: DesktopInputs): DesktopHomeState = when {
        !i.activationReady -> state(
            DesktopPhase.ACTIVATE, Tone.PRI, HeroIcon.KEY, UiText.res(R.string.home_desk_title_activate),
            UiText.res(if (i.paired) R.string.home_desk_sub_activate_paired else R.string.home_desk_sub_activate),
            listOf(
                StageState.TODO to UiText.res(if (i.paired) R.string.home_desk_st_needed_again else R.string.home_desk_st_needed),
                StageState.PEND to NONE, StageState.PEND to NONE, StageState.PEND to NONE, StageState.PEND to NONE,
            ),
            listOf(DesktopButton.ACTIVATE),
        )
        // настоящее восстановление error сбрасывает (BridgeService), а оборванное оставляет и его, и строку «(1/2)…»: тогда это ошибка
        i.error != null -> state(
            DesktopPhase.ERROR, Tone.ERR, HeroIcon.ALERT, UiText.res(R.string.home_desk_title_error), UiText.raw(i.error),
            listOf(
                StageState.DONE to ACTIVE,
                if (i.bridgeRunning) StageState.DONE to (if (i.wireless) CARLIFE_WIFI else CARLIFE) else StageState.PEND to NONE,
                StageState.ERR to UiText.res(R.string.home_desk_st_error), StageState.PEND to NONE, StageState.PEND to NONE,
            ),
            // «Остановить» только пока сессия жива
            if (i.bridgeRunning) listOf(DesktopButton.DIAG, DesktopButton.STOP) else listOf(DesktopButton.DIAG),
        )
        i.recovering && i.bridgeRunning -> state(
            DesktopPhase.RECOVERING, Tone.WARN, HeroIcon.REFRESH, UiText.res(R.string.home_title_reconnecting), UiText.raw(i.status),
            listOf(
                StageState.DONE to ACTIVE, StageState.ACTIVE to UiText.res(R.string.home_stage_reconnecting),
                StageState.PAUSE to UiText.res(R.string.home_stage_paused), StageState.PAUSE to UiText.res(R.string.home_stage_paused),
                StageState.PAUSE to UiText.res(R.string.home_stage_paused),
            ),
            listOf(DesktopButton.STOP),
        )
        i.bridgeRunning && i.desktopRunning -> state(
            DesktopPhase.WORKING, Tone.OK, if (i.wireless) HeroIcon.CAR_WIFI else HeroIcon.CAR_USB, UiText.res(R.string.home_title_ok),
            UiText.res(R.string.home_desk_sub_ok, i.desktopName),
            listOf(
                StageState.DONE to ACTIVE,
                StageState.DONE to (if (i.wireless) CARLIFE_WIFI else CARLIFE),
                StageState.DONE to UiText.raw(i.desktopName),
                StageState.DONE to UiText.raw(i.videoRes),
                StageState.DONE to UiText.raw(if (!i.usbAudio) "Bluetooth" else if (i.wireless) "Wi-Fi" else "USB"),
            ),
            listOf(DesktopButton.STOP),
        )
        // сервис уже поднят (кабель есть), рабочий стол ещё стартует: не просим кабель, который уже воткнут
        i.bridgeRunning -> state(
            DesktopPhase.WAITING, Tone.PRI, HeroIcon.PLUG, UiText.res(R.string.home_title_connecting),
            UiText.res(R.string.home_desk_sub_starting),
            listOf(
                StageState.DONE to ACTIVE, StageState.ACTIVE to UiText.res(R.string.home_stage_connecting),
                StageState.PEND to NONE, StageState.PEND to NONE, StageState.PEND to NONE,
            ),
            listOf(DesktopButton.STOP),
        )
        else -> state(
            DesktopPhase.WAITING, Tone.PRI, HeroIcon.PLUG, UiText.res(R.string.home_title_ready), UiText.res(R.string.home_sub_ready),
            listOf(
                StageState.DONE to ACTIVE, StageState.ACTIVE to UiText.res(R.string.home_stage_wait_cable),
                StageState.PEND to NONE, StageState.PEND to NONE, StageState.PEND to NONE,
            ),
            emptyList(),
        )
    }

    private fun state(
        phase: DesktopPhase, tone: Tone, icon: HeroIcon, title: UiText, sub: UiText,
        stages: List<Pair<StageState, UiText>>, buttons: List<DesktopButton>,
    ) = DesktopHomeState(phase, tone, icon, title, sub, LABELS.zip(stages), buttons)
}

/** Сборка DesktopInputs из DexState / CarLifeState / DelegatedDesktopAccess; вызывается с главной нити. */
object DesktopHomeStatus {
    /** Строки desk_recovering всех языков: сервис моста пишет статус в своей локали, экран может быть в другой. */
    private var recoveringTexts: Set<String>? = null

    /** Подпись лаунчера по сохранённому «пакет/класс»; PackageManager не дёргаем на каждый тик. */
    private var labelFor: Pair<String, String>? = null

    fun inputs(ctx: Context): DesktopInputs {
        val status = DexState.status
        val aa = AaPrefs(ctx)
        return DesktopInputs(
            activationReady = DelegatedDesktopAccess.status(ctx) == DelegatedDesktopAccess.Status.READY,
            paired = PhoneActivationState(ctx).paired,
            bridgeRunning = CarLifeState.running,
            desktopRunning = DexState.running,
            recovering = status in recoveringTexts(ctx),
            status = status,
            error = DexState.error,
            wireless = CarLifeState.wirelessSession,
            videoRes = (CarLifeState.videoMode ?: aa.videoGeometry.effective(aa.transcode)).outLabel,
            desktopName = desktopName(ctx),
            usbAudio = DesktopAudioState.running && DesktopAudioState.nonSilentBuffers > 0,
        )
    }

    fun current(ctx: Context): DesktopHomeState = DesktopHomeLogic.compute(inputs(ctx))

    private fun recoveringTexts(ctx: Context): Set<String> = recoveringTexts ?: buildSet {
        val locales = listOf(Locale.forLanguageTag("ru"), Locale.ENGLISH, ctx.resources.configuration.locales[0])
        for (loc in locales) {
            val lctx = ctx.createConfigurationContext(Configuration(ctx.resources.configuration).apply { setLocale(loc) })
            for (n in 1..2) add(lctx.getString(R.string.desk_recovering, n))
        }
    }.also { recoveringTexts = it }

    private fun desktopName(ctx: Context): String {
        val component = DexPrefs(ctx).desktopComponent ?: return "Headswap"
        labelFor?.let { if (it.first == component) return it.second }
        val pkg = component.substringBefore('/')
        val label = runCatching {
            val pm = ctx.packageManager
            pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg
        labelFor = component to label
        return label
    }
}
