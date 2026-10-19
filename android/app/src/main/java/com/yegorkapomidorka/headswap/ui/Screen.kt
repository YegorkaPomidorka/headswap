package com.yegorkapomidorka.headswap.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import com.yegorkapomidorka.headswap.MainActivity

/** Экран внутри MainActivity: сама View, обновление раз в 500 мс пока виден, реакция на «назад». */
abstract class Screen(val host: MainActivity) {
    val ctx: Context get() = host
    abstract val view: View

    /** Экран стал виден (в том числе после возврата из системных настроек). */
    open fun onShow() {}

    open fun onHide() {}

    /** Каждые 500 мс, пока экран виден. */
    open fun tick() {}

    /** true = нажатие «назад» обработано экраном. */
    open fun onBack(): Boolean = false

    open fun onPermissionResult() {}

    /** Вернулся результат стороннего activity/диалога (выбор машины через CDM). */
    open fun onActivityResultDone() {}

    /** Отладка (adb --ei scroll_y N): прокрутить экран, чтобы снять нижнюю часть. */
    fun scrollTo(y: Int) {
        fun find(v: View): ScrollView? {
            if (v is ScrollView) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        find(view)?.let { sv -> sv.post { sv.scrollTo(0, y) } }
    }
}

val Context.isNight: Boolean
    get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/** Настройки самого UI: онбординг пройден, ручные отметки шагов, которые определить нельзя. */
class UiPrefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)

    var onboardingDone: Boolean
        get() = sp.getBoolean("onboardingDone", false)
        set(v) = sp.edit().putBoolean("onboardingDone", v).apply()

    /** Тема интерфейса: system / light / medium / dark (см. AppTheme). */
    var theme: String
        get() = sp.getString("theme", "system") ?: "system"
        set(v) = sp.edit().putString("theme", v).apply()

    /** Беспровод уже запускали кнопкой «Ждать машину по Wi-Fi»: на главном экране показываем её и без выбранной машины (h185). */
    var wirelessUsed: Boolean
        get() = sp.getBoolean("wirelessUsed", false)
        set(v) = sp.edit().putBoolean("wirelessUsed", v).apply()

    fun isManualDone(id: String): Boolean = sp.getBoolean("manual_$id", false)

    fun setManualDone(id: String, v: Boolean) = sp.edit().putBoolean("manual_$id", v).apply()
}
