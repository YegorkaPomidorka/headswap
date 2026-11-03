// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Resolves the concrete TestApi overrides used by instrumented Android 13 processes. */
internal class DisplayEventAccess {
    private val methods = DisplayEventMethods(MotionEvent::class.java, KeyEvent::class.java, InputEvent::class.java)

    /** Checks both event types and their actual readback before a session. These local events are never injected. */
    fun preflight() {
        methods.preflight()
        val motion = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        try {
            methods.setDisplayId(motion, 1)
            methods.setDisplayId(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_UNKNOWN), 1)
        } finally { motion.recycle() }
    }

    /** Caller must first validate the current owned display/token; this only assigns and reads back its ID. */
    fun setDisplayId(event: InputEvent, displayId: Int) = methods.setDisplayId(event, displayId)

    fun getDisplayId(event: InputEvent): Int = methods.getDisplayId(event)
}

/** The same resolver is exercised with ordinary JVM classes, including simulated hidden-API filtering. */
internal class DisplayEventMethods(
    private val motionType: Class<*>,
    private val keyType: Class<*>,
    private val baseType: Class<*>,
    private val lookup: (Class<*>, String, Array<Class<*>>) -> Method = { type, name, parameters ->
        type.getDeclaredMethod(name, *parameters)
    },
) {
    private data class Accessors(val type: Class<*>, val frameworkName: String, val setter: Method, val getter: Method?)
    private val accessors by lazy {
        listOf(motionType to "MotionEvent", keyType to "KeyEvent").map { (type, frameworkName) ->
            require(type != baseType && baseType.isAssignableFrom(type)) { "Expected a concrete input event type" }
            Accessors(type, frameworkName,
                resolve(type, "setDisplayId", arrayOf(Int::class.javaPrimitiveType!!), Void.TYPE),
                try { resolve(type, "getDisplayId", emptyArray(), Int::class.javaPrimitiveType!!) }
                catch (_: NoSuchMethodException) { null }
                catch (_: SecurityException) { null })
        }
    }

    fun preflight() { accessors }

    fun setDisplayId(event: Any, displayId: Int) {
        require(displayId > 0) { "Ввод на основной или недействительный экран запрещён" }
        val methods = forEvent(event)
        invoke(methods.setter, event, displayId)
        check(read(methods, event) == displayId) { "Android изменил целевой экран события ввода" }
    }

    fun getDisplayId(event: Any): Int = read(forEvent(event), event)

    private fun forEvent(event: Any): Accessors = accessors.firstOrNull { it.type.isInstance(event) }
        ?: throw IllegalArgumentException("Неподдерживаемый тип события ввода: ${event.javaClass.name}")

    private fun read(methods: Accessors, event: Any): Int {
        if (methods.getter == null) {
            // Android 13 exposes the concrete TestApi setter but hides all getter declarations.
            // AOSP MotionEvent/KeyEvent.toString include the actual displayId. No subclass may
            // substitute a diagnostic string; unknown OEM formats fail closed. Never log the text.
            check(event.javaClass == methods.type) { "Readback requires an exact framework input event" }
            return DisplayEventText.readDisplayId(event.toString(), methods.frameworkName)
        }
        val value = invoke(methods.getter, event) as? Int
            ?: throw IllegalStateException("Android вернул некорректный ID экрана события ввода")
        check(value > 0) { "Android не назначил событию отдельный экран" }
        return value
    }

    private fun resolve(type: Class<*>, name: String, parameters: Array<Class<*>>, result: Class<*>): Method {
        // An inherited base declaration can be hidden even when the concrete TestApi override is allowed.
        // Fallback applies only to method lookup, never to a failed invocation or a mismatched readback.
        val method = try {
            lookup(type, name, parameters)
        } catch (concreteMissing: NoSuchMethodException) {
            try { lookup(baseType, name, parameters) }
            catch (baseMissing: NoSuchMethodException) {
                throw NoSuchMethodException("${type.name}.$name: concrete and base declarations unavailable").apply {
                    initCause(baseMissing)
                    addSuppressed(concreteMissing)
                }
            }
        }
        check(Modifier.isPublic(method.modifiers) && !Modifier.isStatic(method.modifiers) &&
            method.returnType == result && method.parameterTypes.contentEquals(parameters) &&
            method.declaringClass.isAssignableFrom(type)) { "Unexpected input display method: $method" }
        return method
    }

    private fun invoke(method: Method, event: Any, vararg arguments: Any): Any? = try {
        method.invoke(event, *arguments)
    } catch (failure: InvocationTargetException) {
        // Preserve SecurityException and framework failures. A second attempt could mutate an event twice.
        throw failure.targetException
    }
}

/** Bounded fallback for the observed public AOSP diagnostic formats, not a general string-to-event parser. */
internal object DisplayEventText {
    private val namedField = Regex("(?<![A-Za-z0-9_])displayId(?![A-Za-z0-9_])")
    private val positiveDecimal = Regex("[1-9][0-9]{0,9}")

    fun readDisplayId(text: String, frameworkName: String): Int {
        check(frameworkName == "MotionEvent" || frameworkName == "KeyEvent") { "Unsupported input diagnostic type" }
        check(text.length in 1..4096 && text.startsWith("$frameworkName { ") && text.endsWith(" }")) {
            "Android не предоставляет проверяемый ID экрана события ввода"
        }
        // KeyEvent can contain quoted application text for ACTION_MULTIPLE. This path only
        // supports key down/up; reject such text rather than mistaking it for a framework field.
        check('"' !in text && '\n' !in text && '\r' !in text && namedField.findAll(text).count() == 1) {
            "Неоднозначный ID экрана события ввода"
        }
        val fields = text.substring(frameworkName.length + 3, text.length - 2).split(',')
        val field = fields.singleOrNull { it.trim().substringBefore('=').trim() == "displayId" }?.trim()
        check(field != null && field.startsWith("displayId=")) { "Некорректное поле ID экрана события ввода" }
        val raw = field.removePrefix("displayId=")
        val value = if (positiveDecimal.matches(raw)) raw.toIntOrNull() else null
        check(value != null && value > 0) { "Android не назначил событию отдельный экран" }
        return value
    }
}
