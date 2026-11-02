// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import android.app.ActivityManager

/** Reads identities only from actual framework task objects returned by ActivityManager. */
internal object FrameworkTaskIdentityReader {
    private val taskClass = ActivityManager.RunningTaskInfo::class.java
    private val displayMethod = runCatching { taskClass.getMethod("getDisplayId") }.getOrNull()
    private val displayField = runCatching { taskClass.getField("displayId") }.getOrNull()
    private val userMethod = runCatching { taskClass.getMethod("getUserId") }.getOrNull()
    private val userField = runCatching { taskClass.getField("userId") }.getOrNull()

    fun read(task: ActivityManager.RunningTaskInfo): FrameworkTaskIdentityPolicy.Identity? {
        // Do not accept a caller-defined subclass whose toString could impersonate framework data.
        if (task.javaClass != taskClass) return null
        val display = runCatching { (displayMethod?.invoke(task) as? Int) ?: displayField?.getInt(task) }.getOrNull()
        val user = runCatching { (userMethod?.invoke(task) as? Int) ?: userField?.getInt(task) }.getOrNull()
        if (display != null && user != null) {
            return FrameworkTaskIdentityPolicy.fromFields(display, user, task.taskId)
        }
        // No hidden-API exemptions, shell command, permission expansion, or unscoped task search.
        return runCatching {
            FrameworkTaskIdentityPolicy.fromFrameworkPrefix(task.toString(), task.taskId)
        }.getOrNull()
    }

    /** One line for the log: why [read] returned null (class, reflection results, start of toString without app text). */
    fun diagnose(task: ActivityManager.RunningTaskInfo): String {
        if (task.javaClass != taskClass) return "класс ${task.javaClass.name}"
        fun probe(name: String, block: () -> Any?): String =
            runCatching { "$name=${block()}" }.getOrElse { "$name!${it.javaClass.simpleName}" }
        val text = runCatching { task.toString().take(PREFIX_SAMPLE) }.getOrDefault("?")
        return listOf(
            "getDisplayId=" + if (displayMethod == null) "нет метода" else probe("вызов") { displayMethod.invoke(task) },
            "поле displayId=" + if (displayField == null) "нет поля" else probe("чтение") { displayField.getInt(task) },
            "getUserId=" + if (userMethod == null) "нет метода" else probe("вызов") { userMethod.invoke(task) },
            "поле userId=" + if (userField == null) "нет поля" else probe("чтение") { userField.getInt(task) },
            "toString='$text'",
        ).joinToString(", ")
    }

    private const val PREFIX_SAMPLE = 90
}
