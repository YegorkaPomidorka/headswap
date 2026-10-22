package com.yegorkapomidorka.headswap.autosrv

import android.content.Context
import android.content.SharedPreferences

/** Найденный на экране переключатель точки доступа (свитч в Настройках или плитка шторки). checked = null: состояние не определилось. */
data class HotspotToggle(val label: String, val checked: Boolean?, val kind: String)

/** Что сценарию точки доступа нужно от телефона; реализует AccessibilityService, в тестах подменяется. */
interface HotspotUi {
    /** Экран готов к работе (включён, без блокировки). false = заблокирован, причина в detail. */
    fun prepareScreen(): Pair<Boolean, String>

    /** Точка доступа поднята (HotspotState.up: рассылка состояния AP, запасной путь - интерфейсы). */
    fun hotspotUp(): Boolean

    /** Состояние рассылки AP и все поднятые интерфейсы с IPv4, строкой для лога. */
    fun ifaces(): String

    /** Метки интентов, которыми пробуем открыть экран точки доступа в Настройках, по порядку. */
    fun tetherIntents(): List<String>

    /** Запустить интент по метке. null = startActivity не прошёл; иначе пакет, что оказался на экране. */
    fun openTetherSettings(label: String): String?

    /** Найти на экране переключатель точки доступа (запоминает узел для clickToggle). */
    fun findToggle(): HotspotToggle?

    /** Пункт «Точка доступа» без свитча (переход внутрь): подпись или null. Запоминает узел для clickMenuEntry. */
    fun findMenuEntry(): String?

    fun clickToggle(): Boolean

    fun clickMenuEntry(): Boolean

    /** Опустить шторку быстрых настроек (GLOBAL_ACTION_QUICK_SETTINGS; повтор разворачивает полностью). */
    fun openQuickSettings(): Boolean

    /** Найти плитку точки доступа в шторке (запоминает узел для clickTile). */
    fun findTile(): HotspotToggle?

    fun clickTile(): Boolean

    /** Дамп деревьев всех окон строками до 200 символов, суммарно не длиннее maxChars. */
    fun dump(maxChars: Int): List<String>

    /** Назад (закрыть шторку). */
    fun back()

    /** Вернуться в наше приложение (или Home). */
    fun returnToApp()

    fun now(): Long

    fun sleep(ms: Long)
}

enum class HotspotResult { OK, ALREADY, SKIPPED, MISMATCH, NOT_FOUND, TIMEOUT }

/** locked: сценарий шёл при заблокированном экране (только шторка) - итог можно повторить после разблокировки. */
class HotspotOutcome(val result: HotspotResult, val detail: String, val path: String?, val ms: Long, val locked: Boolean = false) {
    override fun toString() = "$result: $detail" + (path?.let { " (путь $it)" } ?: "") + " за $ms мс"
}

/** Подписи точки доступа ru/en и разбор состояния плитки; чистые функции. */
object HotspotText {
    private val TARGET = Regex("точк[аиуе] доступа|hotspot|tethering|режим модема|переносная|portable|мобильная точка")

    // соседние переключатели той же страницы: USB/Bluetooth-модем, «отключать точку автоматически», совместимость
    private val NOT_TARGET = Regex("usb|bluetooth|блютуз|ethernet|автомат|automatic|turn off|отключать|выключать|совместим|compatib")

    private val OFF = Regex("(^|[^а-яa-z])(выкл|off|disabled|отключен)")
    private val ON = Regex("(^|[^а-яa-z])(вкл|on|enabled)")

    fun norm(s: CharSequence?): String =
        s?.toString().orEmpty().lowercase().replace('ё', 'е').replace(' ', ' ').trim()

    /** Подпись относится к переключателю самой точки доступа (а не к соседним USB/Bluetooth/«автоотключению»). */
    fun isTarget(label: CharSequence?): Boolean {
        val t = norm(label)
        return t.isNotEmpty() && TARGET.containsMatchIn(t) && !NOT_TARGET.containsMatchIn(t)
    }

    fun isExcluded(label: CharSequence?): Boolean = NOT_TARGET.containsMatchIn(norm(label))

    /** Состояние по текстам плитки (stateDescription, contentDescription...), в порядке приоритета; null = не ясно. */
    fun stateOf(texts: List<CharSequence?>): Boolean? {
        for (s in texts) {
            val t = norm(s)
            if (t.isEmpty()) continue
            if (OFF.containsMatchIn(t)) return false
            if (ON.containsMatchIn(t)) return true
        }
        return null
    }
}

/**
 * Эксперимент h135: включить/выключить точку доступа телефона через интерфейс системы (спецвозможности).
 * Состояние определяется по рассылке AP и интерфейсам (HotspotState), а не по тумблеру. Два пути: экран точки доступа
 * в Настройках (телефон разблокирован) и плитка в шторке быстрых настроек (заблокирован или Настройки не помогли).
 * Диагностический зонд: каждый шаг в лог, при неудаче дамп окон.
 */
class HotspotAutomation(
    private val ui: HotspotUi,
    private val log: (String) -> Unit,
    private val stateWaitMs: Long = 8_000,
    private val pageWaitMs: Long = 2_500,
    private val qsDelayMs: Long = 1_000,
    private val pollMs: Long = 250,
) {
    private val t0 = ui.now()
    private var locked = false

    private fun done(r: HotspotResult, detail: String, path: String? = null): HotspotOutcome {
        val o = HotspotOutcome(r, detail, path, ui.now() - t0, locked)
        log("итог: $o")
        return o
    }

    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = ui.now() + timeoutMs
        while (true) {
            if (cond()) return true
            if (ui.now() >= end) return false
            ui.sleep(pollMs)
        }
    }

    private fun <T : Any> waitFor(timeoutMs: Long, find: () -> T?): T? {
        var res: T? = null
        waitUntil(timeoutMs) {
            res = find()
            res != null
        }
        return res
    }

    private fun dumpLog(what: String, maxChars: Int) {
        val lines = ui.dump(maxChars)
        log("дамп ($what), строк ${lines.size}")
        for (l in lines) log("  $l")
    }

    fun run(on: Boolean, force: Boolean = false): HotspotOutcome {
        val up = ui.hotspotUp()
        log("запрос: ${if (on) "включить" else "выключить"}, точка сейчас ${if (up) "поднята" else "не поднята"}; интерфейсы: ${ui.ifaces()}; weEnabled=$weEnabled force=$force")
        if (on && up) return done(HotspotResult.ALREADY, "уже включена")
        if (!on && !up) {
            weEnabled = false
            return done(HotspotResult.ALREADY, "уже выключена")
        }
        if (!on && !force && !weEnabled) return done(HotspotResult.SKIPPED, "точку включили не мы, не выключаю")

        val (ready, why) = ui.prepareScreen()
        log("экран: $why")
        if (ready) {
            val r = viaSettings(on)
            if (r != null) {
                ui.returnToApp()
                return r
            }
            log("путь Настроек не нашёл переключатель, пробую шторку")
        } else {
            locked = true
            log("телефон заблокирован, сразу шторка быстрых настроек")
        }
        val r = viaQuickSettings(on)
        if (ready) ui.returnToApp()
        return r
    }

    private fun viaSettings(on: Boolean): HotspotOutcome? {
        for (name in ui.tetherIntents()) {
            val pkg = ui.openTetherSettings(name)
            if (pkg == null) {
                log("настройки: $name: startActivity не прошёл")
                continue
            }
            log("настройки: $name: запущено, на экране $pkg")
            ui.sleep(400)
            var toggle = waitFor(pageWaitMs) { ui.findToggle() }
            if (toggle == null) {
                val entry = ui.findMenuEntry()
                if (entry != null) {
                    log("настройки: $name: свитча нет, есть пункт «$entry», захожу внутрь")
                    if (ui.clickMenuEntry()) {
                        ui.sleep(600)
                        toggle = waitFor(pageWaitMs) { ui.findToggle() }
                    } else {
                        log("настройки: $name: пункт «$entry» не нажался")
                    }
                }
            }
            if (toggle == null) {
                log("настройки: $name: переключатель не найден")
                dumpLog("настройки $name", 3_000)
                continue
            }
            return clickAndConfirm(on, toggle, "settings:$name") { ui.clickToggle() }
        }
        return null
    }

    private fun viaQuickSettings(on: Boolean): HotspotOutcome {
        var tile: HotspotToggle? = null
        for (attempt in 1..2) {
            if (!ui.openQuickSettings()) log("шторка: performGlobalAction(QUICK_SETTINGS) отказал (попытка $attempt)")
            ui.sleep(qsDelayMs)
            tile = waitFor(pageWaitMs) { ui.findTile() }
            if (tile != null) break
            log("шторка: плитка не найдена (попытка $attempt)")
        }
        val t = tile
        val out = if (t == null) {
            dumpLog("шторка", 6_000)
            done(HotspotResult.NOT_FOUND, "плитка точки доступа не найдена в шторке", "qs")
        } else {
            clickAndConfirm(on, t, "qs") { ui.clickTile() }
                ?: done(HotspotResult.NOT_FOUND, "плитка найдена, но не нажалась", "qs")
        }
        ui.back()
        log("шторка: закрываю (назад)")
        return out
    }

    /** null = клик не прошёл (вызывающий пробует следующий путь); иначе итог. */
    private fun clickAndConfirm(on: Boolean, t: HotspotToggle, path: String, click: () -> Boolean): HotspotOutcome? {
        log("$path: найден «${t.label}» [${t.kind}] checked=${t.checked}")
        if (t.checked == on) {
            // интерфейс говорит обратное: тумблер уже в нужном положении (точка поднимается/гаснет, или интерфейс назван иначе)
            log("$path: переключатель уже в нужном положении, не кликаю; жду интерфейс")
            return if (waitUntil(stateWaitMs) { ui.hotspotUp() == on }) {
                done(HotspotResult.OK, "состояние стало нужным без нашего клика", path)
            } else {
                log("$path: интерфейсы после ожидания: ${ui.ifaces()}")
                dumpLog(path, 6_000)
                done(HotspotResult.MISMATCH, "переключатель показывает ${if (on) "включено" else "выключено"}, а интерфейс точки не изменился", path)
            }
        }
        if (!click()) {
            log("$path: клик не прошёл")
            dumpLog("$path клик", 4_000)
            return null
        }
        log("$path: клик выполнен, жду ${if (on) "поднятия" else "опускания"} интерфейса до $stateWaitMs мс")
        if (waitUntil(stateWaitMs) { ui.hotspotUp() == on }) {
            weEnabled = on
            log("$path: интерфейс ${if (on) "поднялся" else "опустился"}: ${ui.ifaces()}")
            return done(HotspotResult.OK, if (on) "точка доступа включена" else "точка доступа выключена", path)
        }
        log("$path: интерфейс не изменился за $stateWaitMs мс; интерфейсы: ${ui.ifaces()}")
        dumpLog("$path после клика", 6_000)
        return done(HotspotResult.TIMEOUT, "клик прошёл, но интерфейс точки не ${if (on) "поднялся" else "опустился"}", path)
    }

    companion object {
        private const val PREFS = "hotspot"
        private const val KEY_WE_ENABLED = "we_enabled"

        @Volatile private var prefs: SharedPreferences? = null
        @Volatile private var mem = false

        /** Подключить хранилище: флаг переживает смерть процесса и BridgeService. Без вызова (тесты) живёт в памяти. */
        fun attach(ctx: Context) {
            val sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs = sp
            mem = sp.getBoolean(KEY_WE_ENABLED, false)
        }

        /** Точку включили мы (по факту поднятия точки после клика); выключаем по отключению от машины только такую. */
        var weEnabled: Boolean
            get() = mem
            set(v) {
                mem = v
                prefs?.edit()?.putBoolean(KEY_WE_ENABLED, v)?.apply()
            }
    }
}
