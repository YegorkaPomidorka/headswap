// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex;

import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import androidx.annotation.Keep;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Entry point invoked by an authorized ADB shell, not an Android component or app service.
 * No run-as: the installation is identified by the app-generated activation token, which the instrumentation
 * inside the app process checks before it adopts any permission. The status comes back as am's own output.
 */
@Keep
public final class ManualAccessLauncher {
    private static final int COMMAND_LIMIT = 16_384;
    private static final long READY_TIMEOUT_MS = 30_000;
    /** The instrumentation loop wakes every 5 s; a dead one takes the app process with it. */
    private static final long SURVIVAL_MS = 6_000;
    private static final String END = "\u0000";
    private static final String OVERFLOW = "\u0000OVERFLOW";

    private ManualAccessLauncher() { }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** Same bounded activation used by the explicitly requested, detached phone bootstrap. */
    public static int run(String[] args) {
        int result = 0;
        try {
            ManualAccessPolicy.Options options = ManualAccessPolicy.Options.parse(args);
            if (options.help) {
                System.out.println("HeadSwap ADB activation: --package <id> --token <token> [--audio]\n"
                    + "Готовую команду с пакетом и токеном показывает приложение HeadSwap.\n"
                    + "Основной доступ включает управление текущей музыкой: пауза при отключении USB и продолжение той же сессии после готовности CarLife.\n"
                    + "Для USB-звука передайте --audio.\n"
                    + "Служебные API доступны только в активированном процессе HeadSwap.");
                return 0;
            }
            check(Process.myUid() == 2000, "Запустите команду из обычного ADB shell (UID 2000), без root.");
            check(Build.VERSION.SDK_INT >= 33, "Нужен Android 13 или новее.");
            // A shell-owned, non-symlink lock serializes two manual invocations. No keys or IDs are stored.
            try (FileChannel channel = FileChannel.open(Paths.get("/data/local/tmp/headswap-manual-access.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 FileLock lock = channel.tryLock()) {
                check(lock != null, "Другая ручная активация ещё выполняется. Дождитесь её завершения.");
                int uid = packageUid(options.packageName);
                activate(uid, options);
                grantNotificationAccess(options.packageName);
                try {
                    String opened = run(5000, prepend("am", ManualAccessPolicy.openAppArguments(options.packageName)));
                    if (opened.contains("Error:") || opened.contains("Exception")) throw new IOException("Activity launch rejected");
                } catch (Exception ignored) {
                    System.out.println("Доступ включён. Откройте HeadSwap на телефоне вручную.");
                }
                System.out.println("HEADSWAP_READY\nГотово. Можно отключить компьютер и подключить телефон к CarLife.\n"
                    + "Повторная активация нужна после перезагрузки, обновления, принудительной остановки или потери доступа.");
            }
        } catch (Exception error) {
            result = 1;
            System.err.println("Активация не завершена: " + safe(error.getMessage())
                + "\nHeadSwap не останавливался принудительно. Проверьте настройку и повторите команду.");
        }
        return result;
    }

    private static String[] prepend(String first, java.util.List<String> rest) {
        String[] all = new String[rest.size() + 1];
        all[0] = first;
        for (int n = 0; n < rest.size(); n++) all[n + 1] = rest.get(n);
        return all;
    }

    private static int packageUid(String packageName) throws Exception {
        String paths = run(5000, "pm", "path", packageName);
        check(paths.startsWith("package:/"), "HeadSwap не установлен: " + packageName);
        String packages = run(5000, "cmd", "package", "list", "packages", "-U", "--user", "current", packageName);
        Matcher match = Pattern.compile("^package:" + Pattern.quote(packageName) + "\\s+uid:(\\d+)\\s*$", Pattern.MULTILINE).matcher(packages);
        check(match.find(), "Не удалось проверить UID HeadSwap.");
        int uid = Integer.parseInt(match.group(1));
        check(!match.find() && uid >= 10000 && uid % 100000 >= 10000, "Неоднозначный UID HeadSwap.");
        return uid;
    }

    private static void activate(int uid, ManualAccessPolicy.Options options) throws Exception {
        if (options.audio) System.out.println("Запрошен USB-звук: только воспроизводимое аудио, без микрофона.");
        // Starting the instrumentation restarts the app: an already active session is replaced, not reused.
        java.lang.Process launcher = new ProcessBuilder("/system/bin/sh", "-c", ManualAccessPolicy.launchCommand(options))
            .redirectErrorStream(true).start();
        ArrayBlockingQueue<String> lines = new ArrayBlockingQueue<>(128);
        Thread reader = new Thread(() -> readLines(launcher.getInputStream(), lines), "headswap-manual-output");
        reader.setDaemon(true); reader.start();
        ManualAccessPolicy.ReadyOutput output = new ManualAccessPolicy.ReadyOutput();
        boolean launcherStopped = false;
        try {
            long deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS;
            while (!output.ready) {
                long left = deadline - SystemClock.elapsedRealtime();
                check(left > 0, "За 30 секунд HeadSwap не подтвердил доступ. Проверьте токен и выбранные опции в приложении.");
                String line = lines.poll(left, TimeUnit.MILLISECONDS);
                check(line != null, "За 30 секунд HeadSwap не подтвердил доступ.");
                check(!END.equals(line), "Процесс запуска завершился без READY.");
                check(!OVERFLOW.equals(line), "Слишком большой ответ процесса запуска.");
                output.feed(line);
                check(output.failure == null, output.failure);
            }
            check(output.pid != null, "Нет PID собственного процесса запуска: завершение запрещено.");
            requireCapabilities(output, options);
            // The app reports its own PID; trust it only after /proc agrees on UID and name.
            ManualAccessPolicy.Identity app = identity(output.appPid);
            check(ManualAccessPolicy.isAppProcess(app, uid, options.packageName), "Процесс приложения не совпал с отчётом HeadSwap.");
            stopOwnedLauncher(output.pid, options);
            launcherStopped = true;
            launcher.waitFor(3, TimeUnit.SECONDS);
            System.out.println("Проверяю доступ после завершения собственного ADB-запуска…");
            long until = SystemClock.elapsedRealtime() + SURVIVAL_MS;
            while (SystemClock.elapsedRealtime() < until) {
                Thread.sleep(500);
                boolean same;
                try { same = ManualAccessPolicy.sameProcess(app, identity(app.pid)); }
                catch (IOException | RuntimeException gone) { same = false; }
                check(same, "Процесс HeadSwap завершился после выхода из ADB-запуска. Доступ не сохранился.");
            }
            startVdmHost(options, app.pid);
        } finally {
            if (!launcherStopped && output.pid != null && launcher.isAlive()) {
                try { stopOwnedLauncher(output.pid, options); }
                catch (Exception ignored) {
                    if (launcher.isAlive()) System.err.println("Процесс запуска не завершён: его владелец не подтверждён.");
                }
            }
            // Never force-stop the app, kill by process name, or destroy an unverified launcher PID.
            try { launcher.getInputStream().close(); } catch (IOException ignored) { }
        }
    }

    /**
     * Этап B (Android 17): отдельный shell-процесс держит VirtualDevice для дисплея рабочего стола. Отвязан от этого
     * запуска (setsid/nohup), живёт, пока жив проверенный выше процесс приложения. Не вышло - рабочий стол на обычном дисплее.
     */
    private static void startVdmHost(ManualAccessPolicy.Options options, int appPid) {
        if (Build.VERSION.SDK_INT < VdmHostPolicy.MIN_SDK) return;
        try {
            String apk = System.getProperty("java.class.path");
            String command = VdmHostPolicy.startCommand(apk, options.packageName, appPid);
            java.lang.Process process = new ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (Exception error) {
            System.out.println("Хост виртуального устройства не запущен: " + safe(error.getMessage()));
        }
    }

    /**
     * Phone notifications on the head unit screen: the listener is enabled from the shell, like the other grants, and
     * (Android 15+) allowed to see sensitive content such as one-time codes. Optional: any failure is only reported,
     * the activation itself never depends on it.
     */
    private static void grantNotificationAccess(String packageName) {
        try {
            run(5000, "cmd", "notification", "allow_listener", ManualAccessPolicy.notificationListener(packageName));
        } catch (Exception error) {
            System.out.println("Уведомления на экране ГУ: доступ не выдан (" + safe(error.getMessage()) + ").");
        }
        // The op appeared in Android 15; older systems have nothing to allow and would print a spurious error.
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                run(5000, "appops", "set", packageName, "RECEIVE_SENSITIVE_NOTIFICATIONS", "allow");
            } catch (Exception error) {
                System.out.println("Уведомления на экране ГУ: содержимое скрытых уведомлений недоступно (" + safe(error.getMessage()) + ").");
            }
        }
    }

    private static void requireCapabilities(ManualAccessPolicy.ReadyOutput status, ManualAccessPolicy.Options options) throws IOException {
        check(status.mediaControl,
            "Android не выдал право управления музыкой. Повторите активацию; текущие права не изменены.");
        check(!options.audio || status.playbackAudio,
            "Android не выдал право захвата звука. Включите USB-звук в HeadSwap и повторите команду с --audio.");
    }

    private static ManualAccessPolicy.Identity identity(int pid) throws IOException {
        check(pid > 1, "Некорректный PID.");
        String path = "/proc/" + pid + "/";
        return ManualAccessPolicy.parseIdentity(pid, new String(readFile(path + "status", 16_384), StandardCharsets.UTF_8),
            readFile(path + "cmdline", 16_384), new String(readFile(path + "stat", 8192), StandardCharsets.UTF_8));
    }

    private static byte[] readFile(String path, int maximum) throws IOException {
        try (InputStream in = new FileInputStream(path)) { return boundedRead(in, maximum); }
    }

    private static void stopOwnedLauncher(int pid, ManualAccessPolicy.Options options) throws Exception {
        ManualAccessPolicy.Identity owner = identity(pid);
        check(ManualAccessPolicy.ownsLauncher(owner, options), "UID/аргументы собственного процесса запуска не совпали; завершение запрещено.");
        run(5000, "/system/bin/sh", "-c", ManualAccessPolicy.guardedKillCommand(owner, options));
    }

    private static String run(long timeoutMs, String... command) throws Exception {
        java.lang.Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final byte[][] bytes = new byte[1][];
        final Exception[] failure = new Exception[1];
        Thread reader = new Thread(() -> {
            try { bytes[0] = boundedRead(process.getInputStream(), COMMAND_LIMIT); }
            catch (Exception error) { failure[0] = error; }
        }, "headswap-manual-command");
        reader.setDaemon(true); reader.start();
        // These are only short, directly owned inspection/guard commands, never the instrumentation.
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException("Команда Android не ответила вовремя: " + command[0]);
        }
        reader.join(1000);
        check(!reader.isAlive(), "Не завершилось чтение ответа Android.");
        if (failure[0] != null) throw failure[0];
        String text = new String(bytes[0] == null ? new byte[0] : bytes[0], StandardCharsets.UTF_8);
        check(process.exitValue() == 0, "Android " + command[0] + ": " + safe(text));
        return text;
    }

    private static byte[] boundedRead(InputStream in, int maximum) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048]; int count;
        while ((count = in.read(buffer)) != -1) {
            if (out.size() + count > maximum) throw new IOException("Ответ Android превысил лимит.");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private static void readLines(InputStream in, ArrayBlockingQueue<String> target) {
        try {
            ByteArrayOutputStream line = new ByteArrayOutputStream(); int value;
            while ((value = in.read()) != -1) {
                if (value == '\n') {
                    if (!target.offer(new String(line.toByteArray(), StandardCharsets.UTF_8))) throw new IOException("Output queue full");
                    line.reset();
                } else {
                    if (line.size() >= 8192) throw new IOException("Output line too large");
                    line.write(value);
                }
            }
            if (line.size() > 0) target.offer(new String(line.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception error) { target.clear(); target.offer(OVERFLOW); }
        finally { target.offer(END); }
    }

    private static void check(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message == null ? "Неизвестная ошибка активации" : message);
    }

    private static String safe(String text) {
        if (text == null || text.isEmpty()) return "ошибка Android";
        String clean = text.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "?").trim();
        return clean.length() > 600 ? clean.substring(clean.length() - 600) : clean;
    }
}
