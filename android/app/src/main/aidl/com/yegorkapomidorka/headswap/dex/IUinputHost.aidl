package com.yegorkapomidorka.headswap.dex;

/**
 * h1007: shell-процесс (uid 2000) с тачскрином /dev/uinput, привязанным к дисплею ГУ (Android 13–15).
 * Вызывать может только uid приложения, которому хост отдал этот binder.
 */
interface IUinputHost {
    /** Сводка старта хоста одной строкой. */
    String status();
    /** Создать (пересоздать) тачскрин для дисплея с осями 0..width-1, 0..height-1; false - причина в lastError(). */
    boolean open(int displayId, int width, int height);
    /** Сырые события evdev тройками type, code, value. */
    oneway void events(int displayId, in int[] triplets);
    void close(int displayId);
    /** Описание тачскрина глазами InputManager: id, включён ли, дисплей; пусто - устройства нет. */
    String describe(int displayId);
    String lastError();
}
