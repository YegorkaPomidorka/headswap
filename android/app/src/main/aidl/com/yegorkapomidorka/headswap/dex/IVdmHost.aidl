package com.yegorkapomidorka.headswap.dex;

import android.view.Surface;

/**
 * Shell-процесс (uid 2000), который держит VirtualDevice (этап B, Android 17): дисплей рабочего стола в своей группе.
 * Вызывать может только uid приложения, которому хост отдал этот binder.
 */
interface IVdmHost {
    /** Сводка старта хоста одной строкой: SDK, uid, привязка, ошибки. */
    String status();
    /** id дисплея или -1; причина отказа в lastError(). */
    int createDisplay(in Surface surface, String name, int width, int height, int density, int flags);
    void resizeDisplay(int displayId, int width, int height, int density);
    void releaseDisplay(int displayId);
    /** displayGroupId дисплея глазами хоста, -1 если не прочитан. */
    int displayGroup(int displayId);
    String lastError();
}
