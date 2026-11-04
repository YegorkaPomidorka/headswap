// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

/** Only the system-written identity prefix is eligible; Intent/component text is never parsed. */
internal object FrameworkTaskIdentityPolicy {
    data class Identity(val displayId: Int, val userId: Int, val taskId: Int)

    private const val PREFIX_LIMIT = 128
    private val prefix = Regex(
        "\\ATaskInfo\\{userId=(0|[1-9][0-9]{0,9}) " +
            "taskId=(0|[1-9][0-9]{0,9}) " +
            "(?:effectiveUid=-?[0-9]{1,10} )?" +
            "displayId=(0|[1-9][0-9]{0,9}) isRunning=(?:true|false)(?: |\\})"
    )

    fun fromFields(displayId: Int, userId: Int, taskId: Int): Identity? =
        if (displayId >= 0 && userId >= 0 && taskId >= 0) Identity(displayId, userId, taskId) else null

    /**
     * Android 13 exposes neither TaskInfo.getDisplayId nor its identity fields to ordinary ART.
     * Its framework toString begins with these three integers, before any app-controlled strings.
     * Newer releases may put a system-written effectiveUid between taskId and displayId.
     * A changed/OEM format or an inconsistent public task id makes the whole observation unknown.
     */
    fun fromFrameworkPrefix(text: String, expectedTaskId: Int): Identity? {
        if (expectedTaskId < 0) return null
        val match = prefix.find(text.take(PREFIX_LIMIT)) ?: return null
        val userId = match.groupValues[1].toIntOrNull() ?: return null
        val taskId = match.groupValues[2].toIntOrNull() ?: return null
        val displayId = match.groupValues[3].toIntOrNull() ?: return null
        if (taskId != expectedTaskId) return null
        return fromFields(displayId, userId, taskId)
    }

    private val visibleTypes = Regex(" requestedVisibleTypes=(-?[0-9]{1,10})(?=[ }]|$)")

    /**
     * TaskInfo.requestedVisibleTypes (Android 15+, скрытое поле): какие системные панели просит видимыми верхнее окно задачи.
     * Берётся последнее вхождение: поле пишет система после текста интента. Нет поля (Android 14 и ниже, OEM) - null.
     */
    fun requestedVisibleTypes(text: String): Int? =
        visibleTypes.findAll(text).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
}
