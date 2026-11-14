package com.yegorkapomidorka.headswap.dongle;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Лог в logcat и во внутреннюю память приложения (/data/data/…/files/dongle.log): карта в машине бывает
 * не смонтирована, и в поездке 13.11 на неё не записалось ни строки. Забирать root-adb: adb pull …/files/dongle.log.
 */
final class Log {
    private static final String TAG = "HsDongle";
    private static final long MAX_BYTES = 4L * 1024 * 1024;
    private static File file;

    static synchronized void init(Context ctx) {
        if (file == null) file = new File(ctx.getFilesDir(), "dongle.log");
    }

    static synchronized void i(String msg) {
        android.util.Log.i(TAG, msg);
        if (file == null) return;
        try {
            if (file.length() > MAX_BYTES) {
                File old = new File(file.getPath() + ".1");
                old.delete();
                file.renameTo(old);
            }
            FileWriter w = new FileWriter(file, true);
            w.write(new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()) + " " + msg + "\n");
            w.close();
        } catch (IOException ignored) {
            // карта может быть отдана ПК как накопитель
        }
    }

    private Log() { }
}
