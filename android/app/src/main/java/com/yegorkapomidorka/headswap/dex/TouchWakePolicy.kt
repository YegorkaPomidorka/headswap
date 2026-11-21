package com.yegorkapomidorka.headswap.dex

/**
 * h1151: на Android 13–15 injectInputEvent выбрасывается, пока телефон спит, а тачскрин uinput есть не везде
 * (A13: нет ASSOCIATE_INPUT_DEVICE_TO_DISPLAY у shell, нет хоста активации). Тогда первое касание рабочего стола
 * будит телефон KEYCODE_WAKEUP (кнопки пробуждения система пропускает и во сне), жест ждёт пробуждения и идёт как раньше.
 * h1052: Samsung (S21U, A15) ввод во сне пропускает, а пробуждение на каждое касание раздражает - Samsung не будим.
 * Отличить по результату inject нельзя: выброшенный политикой ввод InputDispatcher отдаёт как SUCCEEDED.
 * На A16+ интерактивность по дисплеям, будить не нужно.
 */
internal object TouchWakePolicy {
    const val MAX_SDK = 35
    /** Сколько ждать пробуждения, держа жест. */
    const val WAIT_MS = 800L
    /** После isInteractive Android ещё раздаёт новое состояние вводу (Notifier -> InputManager.setInteractive). */
    const val SETTLE_MS = 60L

    /** Прошивки, которые пропускают inject на отдельный экран, пока телефон спит. */
    fun passesInputAsleep(manufacturer: String?): Boolean = manufacturer.equals("samsung", ignoreCase = true)

    fun shouldWake(sdk: Int, isDown: Boolean, interactive: Boolean?, manufacturer: String?): Boolean =
        sdk <= MAX_SDK && isDown && interactive == false && !passesInputAsleep(manufacturer)
}
