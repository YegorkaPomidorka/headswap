package com.yegorkapomidorka.headswap.launcher

import com.yegorkapomidorka.headswap.desktop.DesktopAppCatalog

/**
 * Сторонний лаунчер (DexPrefs.desktopComponent) в дочернем окне над нашей панелью «Назад / Домой / Недавние»:
 * выбор рабочего стола, действия кнопок, защита от перезапуска по кругу и состав «Недавних». Без Android и Compose.
 */
internal object ForeignDesktop {
    /** Сколько пакетов помнит история сеанса. */
    const val HISTORY_MAX = 30

    enum class NavKey { BACK, HOME, RECENTS }

    enum class NavStep {
        /** Back в дочернее окно (закрылась задача лаунчера - его перезапускает вызывающий, один раз). */
        BACK_IN_WINDOW,
        CLOSE_RECENTS,
        /** Поднять лаунчер над всем, что открыто в окне; «Недавние» при этом закрываются. */
        RAISE_LAUNCHER,
        OPEN_RECENTS,
    }

    /** Что сделать по кнопке панели; [recentsOpen] - оверлей «Недавние» сейчас на экране. */
    fun press(key: NavKey, recentsOpen: Boolean): NavStep = when (key) {
        NavKey.BACK -> if (recentsOpen) NavStep.CLOSE_RECENTS else NavStep.BACK_IN_WINDOW
        NavKey.HOME -> NavStep.RAISE_LAUNCHER
        NavKey.RECENTS -> if (recentsOpen) NavStep.CLOSE_RECENTS else NavStep.OPEN_RECENTS
    }

    /**
     * Действующий чужой лаунчер: выбор из настроек, пока его вход годен ([usable]) и откат не включён на этом же экране ГУ.
     * null - работает наш рабочий стол (лаунчер не выбран, пропал, отключён или откатили).
     */
    fun resolve(component: String?, usable: Boolean, brokenKey: String?, displayId: Int): AppRef? {
        val ref = AppRef.parse(component ?: return null) ?: return null
        if (!usable || brokenKey == brokenKey(ref, displayId)) return null
        return ref
    }

    /** Метка отката: тот же лаунчер на том же экране ГУ не пробуем снова, новый сеанс или новый выбор - пробуем. */
    fun brokenKey(ref: AppRef, displayId: Int): String = "${ref.key}@$displayId"

    /**
     * Откат на наш рабочий стол нужен, только если лаунчер не стартовал: окно ушло в FAILED, пока в нём лаунчер.
     * Упавший после запуска лаунчер не заменяем: ● и ◀ перезапускают его. FAILED у приложения из «Недавних» - не откат,
     * просто поднимаем лаунчер.
     */
    fun afterWindowFailed(trackedIsLauncher: Boolean): Failed = if (trackedIsLauncher) Failed.FALLBACK else Failed.RAISE_LAUNCHER

    enum class Failed { FALLBACK, RAISE_LAUNCHER }

    /** История сеанса: свежий снимок задач экрана ([current], null - не удалось узнать) первым, затем прежняя. */
    fun mergeHistory(history: List<String>, current: List<String>?): List<String> =
        ((current ?: emptyList()) + history).distinct().take(HISTORY_MAX)

    /**
     * «Недавние»: пакеты истории сеанса, у которых есть вход в каталоге, без самого лаунчера (наш пакет каталог уже
     * отфильтровал). [known] - список задач экрана удалось получить: тогда пустой результат честно пуст. Не удалось и
     * истории нет - заглушка: все приложения каталога (по одному входу на пакет).
     */
    fun recents(history: List<String>, known: Boolean, launcherPkg: String, catalog: List<DesktopAppCatalog.Entry>): List<AppRef> {
        val entries = catalog.filter { it.packageName != launcherPkg }.distinctBy { it.packageName }
        val byPackage = entries.associateBy { it.packageName }
        val used = history.mapNotNull { byPackage[it] }
        val list = if (known || used.isNotEmpty()) used else entries
        return list.map { AppRef(it.packageName, it.className) }
    }
}
