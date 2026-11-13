package com.yegorkapomidorka.headswap.dongle;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Лог в logcat и в /mnt/sdcard/headswap-dongle.log, чтобы после поездки было что читать без adb. */
final class Log {
    private static final String TAG = "HsDongle";
    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static File file;

    static synchronized void init(Context ctx) {
        if (file == null) file = new File(Environment.getExternalStorageDirectory(), "headswap-dongle.log");
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
