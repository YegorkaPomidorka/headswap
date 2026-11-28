package com.yegorkapomidorka.headswap.link.car

/** Какой вариант раздела «По воздуху» показать. CHOOSE - выбор способа; SETUP - «Настройте один раз»; READY - всё задано, этап OFF; STATE - карточка со ступеньками; BOX_WAIT - компактное «Жду бокс». */
enum class WlKind { CHOOSE, SETUP, READY, STATE, BOX_WAIT }

enum class WlTitle { WAITING_CAR, CONNECTED, CONNECTING, HOTSPOT_OFF, NO_BOX_WIFI }

/** Цвет точки у заголовка: pri / ok / warn. */
enum class WlDot { PRI, OK, WARN }

enum class WlStep { BT_CAR, WIFI_DIRECT, JOIN_CAR_WIFI, HOTSPOT, CAR_IN_NET, BT_BOX, WIFI_BOX, CAR_LIVE }

enum class WlStepState { DONE, ACTIVE, WAIT, ERROR }

enum class WlMain { NONE, PICK_CAR, PICK_BOX, SAVE_NET, WAIT_WIFI, CONNECT_BOX, HOTSPOT_ON, OPEN_WIFI }

/** Что рисовать. Рисовалка читает только это (плюс имена и тексты, которых здесь нет: устройство, SSID, CarLifeState.wireless). */
data class WlScreen(
    val kind: WlKind,
    val mode: WlMode,
    /** Строка «Машина/Сеть/Бокс запомнены · имя · Забыть». */
    val deviceRow: Boolean = false,
    val title: WlTitle? = null,
    val dot: WlDot = WlDot.PRI,
    val steps: List<Pair<WlStep, WlStepState>> = emptyList(),
    val main: WlMain = WlMain.NONE,
    val stop: Boolean = false,
)

/** Чистая логика экрана «По воздуху», без android.*: проверяется JVM-тестами. */
object WlScreenLogic {
    /**
     * [choosing] - человек нажал «Сменить». [deviceSet] - машина (P2P) или бокс (BOX) выбраны, [netSet] - сеть машины задана,
     * [btNear] - Bluetooth запомненной машины на связи (бокс не смотрим: звуковых профилей у него нет), [hotspotUp] - точка доступа телефона включена.
     */
    fun screen(
        mode: WlMode,
        choosing: Boolean,
        stage: WlStage,
        deviceSet: Boolean,
        netSet: Boolean,
        btNear: Boolean,
        hotspotUp: Boolean,
    ): WlScreen {
        if (mode == WlMode.NONE || choosing) return WlScreen(WlKind.CHOOSE, mode)
        val off = stage == WlStage.OFF
        val up = stage == WlStage.UP
        // звено между телефоном и машиной: группа Wi-Fi Direct, сеть машины
        val link = when (stage) {
            WlStage.LINK, WlStage.UP -> WlStepState.DONE
            WlStage.WAIT -> WlStepState.ACTIVE
            else -> WlStepState.WAIT
        }
        val live = if (up) WlStepState.DONE else WlStepState.WAIT
        val waiting = if (up) WlTitle.CONNECTED else WlTitle.WAITING_CAR
        val dot = if (up) WlDot.OK else WlDot.PRI
        fun state(title: WlTitle, steps: List<Pair<WlStep, WlStepState>>, d: WlDot = dot, main: WlMain = WlMain.NONE) =
            WlScreen(WlKind.STATE, mode, deviceRow = mode != WlMode.HOTSPOT, title = title, dot = d, steps = steps, main = main, stop = true)
        return when (mode) {
            WlMode.P2P -> when {
                !deviceSet -> WlScreen(WlKind.SETUP, mode, main = WlMain.PICK_CAR)
                off -> WlScreen(WlKind.READY, mode, deviceRow = true, main = WlMain.WAIT_WIFI)
                else -> state(waiting, listOf(
                    WlStep.BT_CAR to if (btNear) WlStepState.DONE else WlStepState.WAIT,
                    WlStep.WIFI_DIRECT to link,
                    WlStep.CAR_LIVE to live,
                ))
            }
            WlMode.CAR_WIFI -> when {
                !netSet -> WlScreen(WlKind.SETUP, mode, main = WlMain.SAVE_NET)
                off -> WlScreen(WlKind.READY, mode, deviceRow = true, main = WlMain.WAIT_WIFI)
                else -> state(waiting, listOf(WlStep.JOIN_CAR_WIFI to link, WlStep.CAR_LIVE to live))
            }
            WlMode.HOTSPOT -> when {
                off -> WlScreen(WlKind.SETUP, mode, main = WlMain.WAIT_WIFI)
                hotspotUp || up -> state(waiting, listOf(
                    WlStep.HOTSPOT to WlStepState.DONE,
                    WlStep.CAR_IN_NET to if (up) WlStepState.DONE else WlStepState.ACTIVE,
                    WlStep.CAR_LIVE to live,
                ))
                else -> state(WlTitle.HOTSPOT_OFF, listOf(
                    WlStep.HOTSPOT to WlStepState.ERROR,
                    WlStep.CAR_IN_NET to WlStepState.WAIT,
                    WlStep.CAR_LIVE to WlStepState.WAIT,
                ), WlDot.WARN, WlMain.HOTSPOT_ON)
            }
            else -> when {      // BOX
                !deviceSet -> WlScreen(WlKind.SETUP, mode, main = WlMain.PICK_BOX)
                stage == WlStage.OFF || stage == WlStage.WAIT -> WlScreen(
                    WlKind.BOX_WAIT, mode, deviceRow = true,
                    main = if (off) WlMain.CONNECT_BOX else WlMain.NONE, stop = !off,
                )
                stage == WlStage.NO_LINK -> state(WlTitle.NO_BOX_WIFI, listOf(
                    WlStep.BT_BOX to WlStepState.DONE,
                    WlStep.WIFI_BOX to WlStepState.ERROR,
                    WlStep.CAR_LIVE to WlStepState.WAIT,
                ), WlDot.WARN, WlMain.OPEN_WIFI)
                else -> state(if (up) WlTitle.CONNECTED else WlTitle.CONNECTING, listOf(
                    WlStep.BT_BOX to WlStepState.DONE,
                    WlStep.WIFI_BOX to WlStepState.DONE,
                    WlStep.CAR_LIVE to if (up) WlStepState.DONE else WlStepState.ACTIVE,
                ))
            }
        }
    }

    /** Нужен ли ответ [CarBtState.check] для этого экрана: иначе Bluetooth не опрашиваем. */
    fun needsBt(mode: WlMode, deviceSet: Boolean, stage: WlStage): Boolean = deviceSet && when (mode) {
        WlMode.P2P -> stage != WlStage.OFF
        else -> false
    }
}
