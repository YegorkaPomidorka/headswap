// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure validation shared by the manual, app_process-hosted ADB activator and its tests.
 * The installed applicationId (it may carry a suffix) is always a parameter; only class names are fixed.
 */
public final class ManualAccessPolicy {
    /** Class names live in our namespace, independent of the installed applicationId. */
    static final String DEX_CLASS_PACKAGE = ManualAccessPolicy.class.getName().substring(0, ManualAccessPolicy.class.getName().lastIndexOf('.'));
    public static final String ENTRY = DEX_CLASS_PACKAGE + ".ManualAccessLauncher";
    public static final String INSTRUMENTATION_CLASS = DEX_CLASS_PACKAGE + ".DelegatedDesktopInstrumentation";
    public static final String TOKEN_ARGUMENT = "headswap_activation_token";
    public static final String AUDIO_ARGUMENT = "headswap_audio";
    public static final String NO_HIDDEN_API_CHECKS = "--no-hidden-api-checks";
    public static final String AM_CLASS ="com.android.commands.am.Am";
    public static final int TOKEN_BYTES = 16;
    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    // Characters that stay literal for cmd.exe, PowerShell, POSIX shells and the device shell alike.
    private static final Pattern APK_PATH = Pattern.compile("/[A-Za-z0-9_./~=+-]+\\.apk");
    private static final Pattern UID = Pattern.compile("^Uid:\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s*$", Pattern.MULTILINE);

    private ManualAccessPolicy() { }

    public static final class Options {
        public final boolean audio, help;
        public final String packageName, token;
        public Options(boolean audio, boolean help, String packageName, String token) {
            this.audio = audio; this.help = help; this.packageName = packageName; this.token = token;
        }
        public static Options parse(String[] args) {
            boolean audio = false, help = false;
            String packageName = null, token = null;
            for (int n = 0; n < args.length; n++) {
                switch (args[n]) {
                    case "--audio": if (audio) throw new IllegalArgumentException("Повторный --audio"); audio = true; break;
                    case "--help": if (help) throw new IllegalArgumentException("Повторный --help"); help = true; break;
                    case "--package":
                        if (packageName != null) throw new IllegalArgumentException("Повторный --package");
                        packageName = value(args, ++n, "--package"); break;
                    case "--token":
                        if (token != null) throw new IllegalArgumentException("Повторный --token");
                        token = value(args, ++n, "--token"); break;
                    default: throw new IllegalArgumentException("Неизвестный параметр: " + args[n]);
                }
            }
            if (help) {
                if (audio || packageName != null || token != null) throw new IllegalArgumentException("--help используется отдельно");
                return new Options(false, true, null, null);
            }
            if (packageName == null) throw new IllegalArgumentException("Не указан --package");
            if (token == null) throw new IllegalArgumentException("Не указан --token");
            requirePackage(packageName);
            requireToken(token);
            return new Options(audio, false, packageName, token);
        }
        private static String value(String[] args, int index, String name) {
            if (index >= args.length) throw new IllegalArgumentException("У " + name + " нет значения");
            return args[index];
        }
    }

    public static String component(String packageName) {
        requirePackage(packageName);
        return packageName + "/" + INSTRUMENTATION_CLASS;
    }

    /** Notification listener of the phone-notifications feature: full class name (it lives in the namespace, not in the applicationId). */
    public static final String NOTIFICATION_LISTENER_CLASS = "com.yegorkapomidorka.headswap.notify.HeadswapNotificationListener";

    public static String notificationListener(String packageName) {
        requirePackage(packageName);
        return packageName + "/" + NOTIFICATION_LISTENER_CLASS;
    }

    public static List<String> arguments(Options options) {
        requirePackage(options.packageName);
        requireToken(options.token);
        // --no-hidden-api-checks: the instrumented app process reads the display/user of other tasks (TaskInfo
        // fields are hidden API); without it the launcher cannot tell whether an embedded app is still on screen.
        List<String> args = new ArrayList<>(Arrays.asList("instrument", "-w", "-r", NO_HIDDEN_API_CHECKS, "-e", TOKEN_ARGUMENT, options.token));
        if (options.audio) args.addAll(Arrays.asList("-e", AUDIO_ARGUMENT, "true"));
        args.add(component(options.packageName));
        return Collections.unmodifiableList(args);
    }

    public static String launchCommand(Options options) {
        return "echo HEADSWAP_ACCESS_LAUNCHER_PID=$$; exec am " + String.join(" ", arguments(options));
    }

    /** `am start` arguments that open the app's own launcher activity, resolved by the installed package. */
    public static List<String> openAppArguments(String packageName) {
        requirePackage(packageName);
        return Collections.unmodifiableList(Arrays.asList("start", "--user", "current", "--display", "0",
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-p", packageName));
    }

    // --- activation token: the app generates and stores it, the PC command carries it, the instrumentation checks it ---

    public static String newToken() {
        byte[] random = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(random);
        StringBuilder hex = new StringBuilder(TOKEN_BYTES * 2);
        for (byte b : random) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }

    public static boolean isValidToken(String token) {
        return token != null && TOKEN.matcher(token).matches();
    }

    /** Constant-time comparison; a missing or malformed value on either side never matches. */
    public static boolean tokensMatch(String expected, String given) {
        if (!isValidToken(expected) || !isValidToken(given)) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), given.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * The command typed on the computer after `adb -d shell`. The APK path is embedded literally so no
     * `$(pm path ...)` has to survive the computer's own shell; it is revalidated against shell metacharacters.
     */
    public static String shellCommand(String apkPath, Options options) {
        requireApkPath(apkPath);
        requirePackage(options.packageName);
        requireToken(options.token);
        return "CLASSPATH=" + apkPath + " app_process / " + ENTRY + " --package " + options.packageName
            + " --token " + options.token + (options.audio ? " --audio" : "");
    }

    public static String adbCommand(String apkPath, Options options) {
        return "adb -d shell " + shellCommand(apkPath, options);
    }

    public static void requireApkPath(String apkPath) {
        if (apkPath == null || !APK_PATH.matcher(apkPath).matches()) throw new IllegalArgumentException("Некорректный путь к APK");
    }

    public static void requirePackage(String packageName) {
        if (packageName == null || !PACKAGE.matcher(packageName).matches()) throw new IllegalArgumentException("Некорректное имя пакета");
    }

    private static void requireToken(String token) {
        if (!isValidToken(token)) throw new IllegalArgumentException("Некорректный токен запуска");
    }

    public static final class Identity {
        public final int pid;
        public final long startTicks;
        public final long[] uids;
        public final List<String> argv;
        private Identity(int pid, long startTicks, long[] uids, List<String> argv) {
            this.pid = pid; this.startTicks = startTicks; this.uids = uids;
            this.argv = Collections.unmodifiableList(argv);
        }
    }

    public static Identity parseIdentity(int pid, String status, byte[] cmdline, String stat) {
        if (pid <= 1 || cmdline.length > 16_384 || cmdline.length == 0 || status.length() > 16_384 || stat.length() > 8192)
            throw new IllegalArgumentException("Некорректные данные процесса");
        Matcher match = UID.matcher(status);
        if (!match.find()) throw new IllegalArgumentException("UID процесса не найден");
        long[] uids = new long[4];
        for (int n = 0; n < 4; n++) uids[n] = Long.parseLong(match.group(n + 1));
        if (match.find()) throw new IllegalArgumentException("Повторный UID процесса");
        List<String> argv = new ArrayList<>();
        int start = 0;
        for (int n = 0; n < cmdline.length; n++) {
            if (cmdline[n] != 0) continue;
            if (n == start) { start = n + 1; continue; } // Android process names may leave trailing NUL padding.
            String arg = new String(cmdline, start, n - start, StandardCharsets.UTF_8);
            if (!Arrays.equals(arg.getBytes(StandardCharsets.UTF_8), Arrays.copyOfRange(cmdline, start, n)))
                throw new IllegalArgumentException("Некорректная кодировка процесса");
            for (int k = 0; k < arg.length(); k++) if (arg.charAt(k) < 32 || arg.charAt(k) == 127)
                throw new IllegalArgumentException("Управляющий символ в аргументе процесса");
            argv.add(arg); start = n + 1;
        }
        if (start != cmdline.length || argv.isEmpty()) throw new IllegalArgumentException("Нет завершающего NUL процесса");
        int space = stat.indexOf(' '), end = stat.lastIndexOf(')');
        if (space <= 0 || end <= space || Integer.parseInt(stat.substring(0, space)) != pid)
            throw new IllegalArgumentException("PID не совпал");
        String[] fields = stat.substring(end + 1).trim().split("\\s+");
        if (fields.length < 20) throw new IllegalArgumentException("Короткий stat процесса");
        long ticks = Long.parseLong(fields[19]);
        if (ticks <= 0) throw new IllegalArgumentException("Некорректное время запуска процесса");
        return new Identity(pid, ticks, uids, argv);
    }

    public static boolean ownsLauncher(Identity identity, Options options) {
        if (identity == null || options == null || !isValidToken(options.token)) return false;
        for (long uid : identity.uids) if (uid != 2000) return false;
        int index = identity.argv.indexOf(AM_CLASS);
        return index >= 0 && identity.argv.subList(index + 1, identity.argv.size()).equals(arguments(options));
    }

    /** The reported app process: our package name as argv[0], and every UID equal to the installed app's UID. */
    public static boolean isAppProcess(Identity identity, long appUid, String packageName) {
        if (identity == null || appUid < 10000 || packageName == null) return false;
        for (long uid : identity.uids) if (uid != appUid) return false;
        return identity.argv.size() == 1 && identity.argv.get(0).equals(packageName);
    }

    /** Same kernel process incarnation: PID reuse changes the start time. */
    public static boolean sameProcess(Identity before, Identity after) {
        return before != null && after != null && before.pid == after.pid && before.startTicks == after.startTicks;
    }

    public static String guardedKillCommand(Identity identity, Options options) {
        if (!ownsLauncher(identity, options)) throw new IllegalArgumentException("Процесс запуска не принадлежит этой активации");
        return "p=" + identity.pid + "\n"
            + "test \"$(awk '/^Uid:/ {print $2 \":\" $3 \":\" $4 \":\" $5}' /proc/$p/status)\" = 2000:2000:2000:2000 || exit 41\n"
            + "a=$(tr '\\000' '\\n' < /proc/$p/cmdline) || exit 42\n"
            + "expected=" + quote(String.join("\n", identity.argv)) + "\n"
            + "test \"$a\" = \"$expected\" || exit 43\n"
            + "s=$(cat /proc/$p/stat) || exit 44\n"
            + "s=${s##*) }\nset -- $s\n"
            + "test \"${20}\" = " + identity.startTicks + " || exit 45\n"
            + "kill -KILL \"$p\"";
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }

    /** Single-line bounded diagnostics, safe for am's line-oriented result protocol. */
    public static String conciseFailure(Throwable error) {
        StringBuilder text = new StringBuilder();
        Throwable current = error;
        java.util.Set<Throwable> seen = Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (int depth = 0; current != null && depth < 4 && seen.add(current); depth++) {
            String message = current.getMessage();
            String part = current.getClass().getSimpleName() + (message == null || message.trim().isEmpty() ? "" : ": " + message);
            if (text.length() > 0) text.append("; причина: ");
            text.append(part);
            current = current.getCause();
        }
        return conciseText(text.length() == 0 ? "Ошибка активации HeadSwap" : text.toString());
    }

    private static String conciseText(String text) {
        String clean = text.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        return clean.length() <= 640 ? clean : clean.substring(0, 637) + "...";
    }

    /**
     * Parses `am instrument -w -r` output. The status bundle is collected until its STATUS_CODE line, so the
     * order of the bundle keys does not matter. Only a code-0 bundle with access=ready and the app PID is READY.
     */
    public static final class ReadyOutput {
        public Integer pid;
        /** PID of the instrumented HeadSwap process, reported by the app itself. */
        public Integer appPid;
        public boolean mediaControl, playbackAudio;
        public boolean ready;
        public String failure;
        private boolean bundleReady, bundleMedia, bundleAudio;
        private Integer bundlePid;

        public void feed(String raw) {
            if (raw == null || raw.length() > 8192) throw new IllegalArgumentException("Некорректный ответ запуска");
            String line = raw.trim();
            if (line.startsWith("HEADSWAP_ACCESS_LAUNCHER_PID=")) {
                pid = (int) parsePid(line.substring("HEADSWAP_ACCESS_LAUNCHER_PID=".length()), pid, "запуска");
            } else if (line.startsWith("INSTRUMENTATION_RESULT: headswap_access_error=")
                    || line.startsWith("INSTRUMENTATION_STATUS: headswap_access_error=")) {
                String detail = line.substring(line.indexOf('=') + 1).trim();
                failure = conciseText(detail.isEmpty() ? "HeadSwap отклонил активацию" : detail);
                ready = false;
                resetBundle();
            } else if (line.equals("INSTRUMENTATION_STATUS: headswap_access=ready")) {
                bundleReady = true;
            } else if (line.startsWith("INSTRUMENTATION_STATUS: headswap_pid=")) {
                bundlePid = (int) parsePid(line.substring(line.indexOf('=') + 1), null, "приложения");
            } else if (line.equals("INSTRUMENTATION_STATUS: headswap_media_control=true")) {
                bundleMedia = true;
            } else if (line.equals("INSTRUMENTATION_STATUS: headswap_playback_audio=true")) {
                bundleAudio = true;
            } else if (line.startsWith("INSTRUMENTATION_STATUS_CODE:")) {
                if (failure == null && line.equals("INSTRUMENTATION_STATUS_CODE: 0") && bundleReady && bundlePid != null) {
                    ready = true;
                    appPid = bundlePid;
                    mediaControl = bundleMedia;
                    playbackAudio = bundleAudio;
                }
                resetBundle();
            } else if (line.startsWith("INSTRUMENTATION_FAILED:") || line.startsWith("INSTRUMENTATION_RESULT: shortMsg=")) {
                if (failure == null) failure = conciseText(line);
                ready = false;
                resetBundle();
            }
        }

        private void resetBundle() { bundleReady = bundleMedia = bundleAudio = false; bundlePid = null; }

        private static long parsePid(String value, Integer previous, String what) {
            if (!value.matches("[1-9][0-9]{0,9}")) throw new IllegalArgumentException("Некорректный PID " + what);
            long candidate = Long.parseLong(value);
            if (candidate <= 1 || candidate > Integer.MAX_VALUE || (previous != null && previous != candidate))
                throw new IllegalArgumentException("PID " + what + " изменился");
            return candidate;
        }
    }
}
