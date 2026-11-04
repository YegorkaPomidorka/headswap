package com.yegorkapomidorka.headswap.carapp

import android.os.IBinder
import androidx.car.app.OnDoneCallback
import androidx.car.app.model.CarText
import androidx.car.app.model.Distance
import androidx.car.app.model.OnClickDelegate
import androidx.car.app.model.TemplateWrapper
import androidx.car.app.serialization.Bundleable
import com.yegorkapomidorka.headswap.AppLog
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

/**
 * Отладка хоста на стенде: шаблон целиком (поля моделей рефлексией) в лог и клики по номеру (cmd car_click), пока
 * лаунчер не рисует шаблоны сам. Только поток хоста.
 */
internal object CarTemplateDump {
    private val clicks = mutableListOf<OnClickDelegate>()
    private var lastKey = ""

    /** Собрать кликабельные элементы; [log] не null - написать дамп, если шаблон поменялся не только цифрами. */
    fun collect(w: TemplateWrapper?, log: ((String) -> Unit)?) {
        clicks.clear()
        val t = w?.template ?: return
        val sb = StringBuilder()
        walk(t, 0, sb, IdentityHashMap())
        if (log == null) return
        val s = sb.toString()
        val key = s.replace(Regex("\\d+"), "#") // NavigationTemplate приходит каждую секунду: метры и секунды - не новость
        if (key == lastKey) return
        lastKey = key
        log("шаблон ${t.javaClass.simpleName} (кликов ${clicks.size}):")
        s.lineSequence().filter { it.isNotBlank() }.forEach { log("  $it") }
    }

    fun click(i: Int) {
        val d = clicks.getOrNull(i)
        if (d == null) { AppLog.i("CARAPP клик #$i: нет такого (всего ${clicks.size})", false); return }
        runCatching {
            d.sendClick(object : OnDoneCallback {
                override fun onSuccess(response: Bundleable?) { AppLog.i("CARAPP клик #$i: ок", false) }
                override fun onFailure(response: Bundleable) { AppLog.i("CARAPP клик #$i: отказ", false) }
            })
        }.onFailure { AppLog.i("CARAPP клик #$i упал: ${it.javaClass.simpleName}: ${it.message}", false) }
    }

    private fun walk(o: Any?, depth: Int, sb: StringBuilder, seen: IdentityHashMap<Any, Boolean>) {
        val pad = "  ".repeat(depth)
        if (o == null || depth > 9 || seen.put(o, true) != null) return
        for (c in generateSequence<Class<*>>(o.javaClass) { it.superclass }.takeWhile { it != Any::class.java }) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                val v = runCatching { f.isAccessible = true; f.get(o) }.getOrNull() ?: continue
                val name = f.name.removePrefix("m").replaceFirstChar { it.lowercase() }
                val leaf = leaf(v)
                when {
                    leaf != null -> if (leaf.isNotEmpty()) sb.append(pad).append(name).append(" = ").append(leaf).append('\n')
                    v is Collection<*> -> {
                        if (v.isEmpty()) continue
                        sb.append(pad).append(name).append(" [").append(v.size).append("]\n")
                        v.forEachIndexed { i, e ->
                            val l = leaf(e)
                            sb.append(pad).append("  #").append(i).append(' ').append(l ?: e?.javaClass?.simpleName).append('\n')
                            if (l == null) walk(e, depth + 2, sb, seen)
                        }
                    }
                    v.javaClass.name.startsWith("androidx.car.app.") -> {
                        sb.append(pad).append(name).append(": ").append(v.javaClass.simpleName).append('\n')
                        walk(v, depth + 1, sb, seen)
                    }
                }
            }
        }
    }

    /** Значение-лист одной строкой; "" - пропустить; null - раскрывать дальше. */
    private fun leaf(v: Any?): String? = when (v) {
        null -> ""
        is String -> "\"$v\""
        is Number, is Boolean, is Enum<*> -> v.toString()
        is CarText -> "\"${v.toCharSequence()}\""
        is CharSequence -> "\"$v\""
        is Distance -> "${v.displayDistance} unit${v.displayUnit}"
        is androidx.car.app.model.CarColor -> "color${v.type}"
        is androidx.car.app.model.CarIcon -> "icon(type ${v.type})"
        is OnClickDelegate -> { clicks.add(v); "КЛИК #${clicks.size - 1}" }
        is IBinder, is android.os.IInterface -> ""
        else -> if (v.javaClass.simpleName.contains("Delegate")) "делегат ${v.javaClass.simpleName}" else null
    }
}
