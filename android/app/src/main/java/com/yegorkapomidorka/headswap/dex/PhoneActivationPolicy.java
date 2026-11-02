// Idea and shell hand-off from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov; no run-as, token instead.
package com.yegorkapomidorka.headswap.dex;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pure rules of the on-phone activation: the phone's own adbd (wireless debugging) starts the same
 * app_process entry the computer would, detached from the ADB stream because the instrumentation restarts
 * this very app and drops the connection. Nothing here takes a user-supplied shell command.
 */
public final class PhoneActivationPolicy {
    /** Detached entry: the manual launcher plus "open the app" on failure, which the PC path does not need. */
    public static final String ENTRY = ManualAccessPolicy.DEX_CLASS_PACKAGE + ".PhoneActivationLauncher";
    public static final String DISPATCHED = "HEADSWAP_PHONE_DISPATCHED=";
    public static final String FAILURE_PREFIX = "Активация не завершена:";
    /** Dispatch to proof of access: the launcher waits up to 30 s for READY and 6 s for survival, then the app restarts. */
    public static final long DEADLINE_MS = 60_000;
    public static final String PREFLIGHT = "test -x /system/bin/setsid && test -x /system/bin/nohup && test -x /system/bin/app_process";
    public static final String REMOVE_LOGS = "rm -f " + "/data/local/tmp/headswap-phone-*.log";

    private static final String LOG_PREFIX = "/data/local/tmp/headswap-phone-";
    private static final Pattern NONCE = Pattern.compile("[0-9a-f]{16}");
    private static final Pattern CODE = Pattern.compile("[0-9]{6}");
    private static final int LOG_LIMIT = 2000;

    public enum Progress { PENDING, READY, EXPIRED }

    private PhoneActivationPolicy() { }

    public static String newNonce() {
        byte[] random = new byte[8];
        new SecureRandom().nextBytes(random);
        StringBuilder hex = new StringBuilder(16);
        for (byte b : random) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }

    public static boolean isValidNonce(String nonce) { return nonce != null && NONCE.matcher(nonce).matches(); }

    public static boolean isValidCode(String code) { return code != null && CODE.matcher(code).matches(); }

    public static boolean isValidPort(int port) { return port >= 1 && port <= 65535; }

    public static String logPath(String nonce) {
        if (!isValidNonce(nonce)) throw new IllegalArgumentException("Некорректный номер запуска");
        return LOG_PREFIX + nonce + ".log";
    }

    /**
     * Two shell lines for the phone's own adbd. The package and token are the installed applicationId and the
     * app's activation token (validated by [ManualAccessPolicy]), the APK path is the installed base APK.
     * setsid + nohup + closed descriptors keep the launcher alive when the app (and the ADB stream) dies.
     */
    public static String dispatchCommand(String apkPath, ManualAccessPolicy.Options options, String nonce) {
        ManualAccessPolicy.requireApkPath(apkPath);
        if (options == null || options.help) throw new IllegalArgumentException("Некорректные параметры активации");
        ManualAccessPolicy.arguments(options); // validates package and token
        String log = logPath(nonce);
        return REMOVE_LOGS + "\n"
            + "CLASSPATH='" + apkPath + "' /system/bin/setsid /system/bin/nohup /system/bin/app_process / " + ENTRY
            + " --package " + options.packageName + " --token " + options.token + (options.audio ? " --audio" : "")
            + " </dev/null >" + log + " 2>&1 &\n"
            + "echo " + DISPATCHED + nonce;
    }

    public static boolean dispatchAccepted(int exitCode, String output, String nonce) {
        if (exitCode != 0 || output == null || !isValidNonce(nonce)) return false;
        for (String line : output.split("\\r?\\n")) if (line.trim().equals(DISPATCHED + nonce)) return true;
        return false;
    }

    /** `id -u` of an adb shell session. Anything else (root, an app uid) is not the adb shell we rely on. */
    public static boolean isShellUid(String output) { return output != null && output.trim().equals("2000"); }

    /** `pm path` lists the base APK and the splits; the installed copy must be the one this process runs from. */
    public static boolean packageListed(String pmOutput, String apkPath) {
        if (pmOutput == null || apkPath == null) return false;
        for (String line : pmOutput.split("\\r?\\n")) if (line.trim().equals("package:" + apkPath)) return true;
        return false;
    }

    public static String readLogCommand(String nonce) { return "tail -c " + LOG_LIMIT + " " + logPath(nonce) + " 2>/dev/null"; }

    /** The launcher's own failure line, cleaned and bounded; null when the log shows no failure. */
    public static String failureMessage(String log) {
        if (log == null) return null;
        for (String line : log.split("\\r?\\n")) {
            if (!line.startsWith(FAILURE_PREFIX)) continue;
            String clean = line.substring(FAILURE_PREFIX.length()).replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
            return clean.isEmpty() ? "ошибка Android" : clean.substring(0, Math.min(clean.length(), 400));
        }
        return null;
    }

    public static boolean logShowsReady(String log) { return log != null && log.contains("HEADSWAP_READY"); }

    /** Where an attempt stands. A clock that went backwards must not leave it pending for ever. */
    public static Progress progress(long startedMs, long nowMs, boolean accessReady) {
        if (accessReady) return Progress.READY;
        long age = nowMs - startedMs;
        return startedMs > 0 && age >= 0 && age <= DEADLINE_MS ? Progress.PENDING : Progress.EXPIRED;
    }
}
