package com.yegorkapomidorka.headswap.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.dex.DexPrefs

/**
 * Режим чужого лаунчера: окно [EmbedHost.app] над нашей панелью навигации. Дисплей ГУ по-прежнему отдан
 * LauncherActivity, поэтому смена выбора и кнопка HOME доходят до неё как onNewIntent. Всё на главном потоке.
 */
internal object ForeignActions {
    private val model get() = LauncherModel

    /**
     * Перечитать DexPrefs.desktopComponent (onCreate, onNewIntent, onResume, стенд). [goHome] - это ещё и «Домой»
     * (HOME ГУ, смена выбора в настройках); без него лаунчер уже запущенного режима не трогаем.
     * [displayId] - дисплей ГУ: откат и история действуют в пределах одного сеанса.
     */
    fun reload(ctx: Context, displayId: Int, goHome: Boolean) {
        if (displayId != model.foreignDisplay) {
            model.foreignDisplay = displayId
            model.recentsHistory = emptyList()
            model.recentsOpen = false
        }
        val component = DexPrefs(ctx).desktopComponent
        val changed = component != model.foreignSeen
        if (changed) {
            model.foreignSeen = component
            model.foreignBroken = null // новый выбор: пробуем заново
            model.recentsHistory = emptyList()
        }
        val ref = component?.let(AppRef::parse)
        val usable = ref != null && desktopUsable(ctx, ref)
        val resolved = ForeignDesktop.resolve(component, usable, model.foreignBroken, displayId)
        if (changed && component != null && resolved == null) {
            AppLog.i("LAUNCHER чужой лаунчер $component: ${if (usable) "уже не стартовал на этом экране" else "пакета или входа нет"}, " +
                "показываю свой рабочий стол", false)
        }
        val previous = model.foreign
        if (resolved == null) {
            if (previous != null) leave(if (component == null) "выбран Headswap" else "лаунчер недоступен")
            return
        }
        if (resolved != previous) {
            AppLog.i("LAUNCHER чужой лаунчер ${resolved.key} в окне над панелью навигации", false)
            model.foreign = resolved
            model.areaRect = null // окно считается заново по новой раскладке
            model.area = Area.Home
            home()
        } else if (goHome) home()
    }

    /** Пакет есть, включён и открывается так же, как запустит реестр окна: exported-вход под LAUNCHER именно этого класса. */
    private fun desktopUsable(ctx: Context, ref: AppRef): Boolean = runCatching {
        ctx.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(ref.pkg), 0,
        ).any {
            val a = it.activityInfo
            a != null && ComponentName(a.packageName, a.name).flattenToString() == ComponentName(ref.pkg, ref.cls).flattenToString() &&
                a.enabled && a.exported && a.applicationInfo.enabled
        }
    }.getOrDefault(false)

    /**
     * ● : лаунчер поверх всего, что открыто в окне. Если в окне сейчас другое приложение, меняется ключ окна и запуск
     * идёт обычным путём (слот следит уже за лаунчером); если лаунчер и так в окне - проверка его задачи, перезапуск
     * только когда он не сверху. refresh вместе со сменой ключа не зовём: на рабочем потоке он прочитал бы старый ключ.
     */
    fun home() {
        val ref = model.foreign ?: return
        model.recentsOpen = false
        if (EmbedHost.app.status == EmbedStatus.FAILED) {
            // прежний FAILED не должен сразу откатить новую попытку: статус сбрасываем до того, как слой его увидит
            EmbedHost.app.status = EmbedStatus.CONNECTING
            EmbedHost.retry(EmbedHost.app)
        }
        if (model.appKey != ref.key) {
            model.starting = model.packageLabel(ref.pkg)
            model.appKey = ref.key
        } else EmbedHost.refresh(EmbedHost.app)
    }

    /** Нажатие кнопки панели или аппаратный Back ГУ. */
    fun press(key: ForeignDesktop.NavKey) {
        AppLog.i("LAUNCHER панель: $key, недавние=${model.recentsOpen}, окно=${EmbedHost.app.pkg}", false)
        when (ForeignDesktop.press(key, model.recentsOpen)) {
            ForeignDesktop.NavStep.BACK_IN_WINDOW -> EmbedHost.back(EmbedHost.app) { backClosed() }
            ForeignDesktop.NavStep.CLOSE_RECENTS -> model.recentsOpen = false
            ForeignDesktop.NavStep.RAISE_LAUNCHER -> if (model.launchDebounce.allow(model.now())) home()
            ForeignDesktop.NavStep.OPEN_RECENTS -> openRecents()
        }
    }

    /** Back вывел задачу из корня: если это был лаунчер или открытое из «Недавних» приложение - лаунчер заново, один раз на нажатие. */
    fun backClosed() {
        model.launchLock.finish()
        model.starting = null
        home()
    }

    /** Задача в окне пропала сама (процесс убит, Back не наш): лаунчер перезапускается, откат не нужен. */
    fun gone() {
        AppLog.i("LAUNCHER задача в окне пропала (${EmbedHost.app.pkg}), поднимаю лаунчер", false)
        home()
    }

    /** Слот ушёл в FAILED: не стартовавший лаунчер заменяем нашим столом, неудачное приложение из «Недавних» - лаунчером. */
    fun windowFailed() {
        val ref = model.foreign ?: return
        when (ForeignDesktop.afterWindowFailed(EmbedHost.app.pkg == ref.pkg)) {
            ForeignDesktop.Failed.FALLBACK -> {
                AppLog.i("LAUNCHER чужой лаунчер ${ref.key} не стартовал (окно не открылось), показываю свой рабочий стол", false)
                model.foreignBroken = ForeignDesktop.brokenKey(ref, model.foreignDisplay)
                leave("окно не открылось")
            }
            ForeignDesktop.Failed.RAISE_LAUNCHER -> home()
        }
    }

    /** Уйти в наш рабочий стол: окно чужого лаунчера освобождается (оно держит одну из двух аренд). */
    private fun leave(why: String) {
        AppLog.i("LAUNCHER выход из режима чужого лаунчера: $why", false)
        model.foreign = null
        model.areaRect = null
        model.recentsOpen = false
        model.recents = null
        model.starting = null
        model.launchLock.finish()
        model.appKey = null
        model.home()
    }

    // --- «Недавние» ---

    private fun openRecents() {
        val ref = model.foreign ?: return
        model.recents = null
        model.recentsOpen = true
        EmbedHost.taskPackages(EmbedHost.app) { packages ->
            if (packages != null) model.recentsHistory = ForeignDesktop.mergeHistory(model.recentsHistory, packages)
            model.recents = ForeignDesktop.recents(model.recentsHistory, packages != null, ref.pkg, model.catalog)
            AppLog.i("LAUNCHER недавние: задач на экране=${packages?.size ?: "неизвестно"}, в списке=${model.recents?.size}", false)
        }
    }

    /**
     * Приложение из «Недавних» в том же окне. Запуск идёт сменой ключа окна, а не прямым launch реестра: слот следит
     * за пакетом, запущенным последним, и тогда задача открытого приложения не считается пропавшей лаунчером, а ●
     * возвращает лаунчер тем же путём.
     */
    fun openRecent(ref: AppRef) {
        model.recentsOpen = false
        if (!model.launchDebounce.allow(model.now())) return
        model.recentsHistory = ForeignDesktop.mergeHistory(model.recentsHistory, listOf(ref.pkg))
        AppLog.i("LAUNCHER недавние: открываю ${ref.key}", false)
        if (model.appKey == ref.key) { EmbedHost.refresh(EmbedHost.app); return }
        model.starting = model.label(ref) ?: ref.pkg
        model.appKey = ref.key
    }
}
