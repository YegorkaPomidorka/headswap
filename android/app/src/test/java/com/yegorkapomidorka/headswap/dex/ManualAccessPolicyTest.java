// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public final class ManualAccessPolicyTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String APP = "com.yegorkapomidorka.headswap";
    private static final String APK = "/data/app/~~Ab1_-xYz==/com.yegorkapomidorka.headswap-Qw3==/base.apk";
    private static ManualAccessPolicy.Options options(String... values) {
        List<String> all = new ArrayList<>(Arrays.asList("--package", APP, "--token", TOKEN));
        all.addAll(Arrays.asList(values));
        return ManualAccessPolicy.Options.parse(all.toArray(new String[0]));
    }
    private static byte[] cmdline(List<String> args) { return (String.join("\0", args) + "\0").getBytes(StandardCharsets.UTF_8); }
    private static String stat(int pid, long ticks) {
        String[] fields = new String[25]; Arrays.fill(fields, "0"); fields[0] = "S"; fields[19] = Long.toString(ticks);
        return pid + " (am worker (test)) " + String.join(" ", fields);
    }
    private static List<String> launcherArgs(ManualAccessPolicy.Options opts) {
        List<String> values = new ArrayList<>(Arrays.asList("app_process", "/system/bin", ManualAccessPolicy.AM_CLASS));
        values.addAll(ManualAccessPolicy.arguments(opts)); return values;
    }
    private static ManualAccessPolicy.Identity identity(List<String> args, String uid, long ticks) {
        return ManualAccessPolicy.parseIdentity(314, "Name:\tam\nUid:\t" + uid + "\n", cmdline(args), stat(314, ticks));
    }
    private static void rejected(Runnable run) {
        try { run.run(); fail("Expected rejection"); } catch (IllegalArgumentException expected) { }
    }

    @Test public void optionsRequireExplicitPackageTokenAndIndependentAudio() {
        assertFalse(options().audio);
        assertTrue(options("--audio").audio);
        assertEquals(APP, options().packageName);
        assertEquals(TOKEN, options().token);
        rejected(() -> options("--all")); rejected(() -> options("--audio", "--audio"));
        rejected(() -> options("--screen")); // The panel option is not part of this port.
        rejected(() -> ManualAccessPolicy.Options.parse(new String[]{"--token", TOKEN}));
        rejected(() -> ManualAccessPolicy.Options.parse(new String[]{"--package", APP}));
        rejected(() -> ManualAccessPolicy.Options.parse(new String[]{"--package", APP, "--token"}));
        rejected(() -> ManualAccessPolicy.Options.parse(new String[]{"--package", APP, "--package", APP, "--token", TOKEN}));
        rejected(() -> ManualAccessPolicy.Options.parse(new String[]{"--help", "--audio"}));
        assertTrue(ManualAccessPolicy.Options.parse(new String[]{"--help"}).help);
    }

    @Test public void packageIsValidatedBeforeItReachesAShellString() {
        for (String bad : Arrays.asList("", "app", "com.example;id", "com.example app", "com.example\nx", "com..example",
                "1com.example", "com.example/", "$(id)", "com.example`id`")) {
            rejected(() -> ManualAccessPolicy.Options.parse(new String[]{"--package", bad, "--token", TOKEN}));
        }
        // A suffixed applicationId is a normal package name.
        assertEquals(APP + ".smoke", ManualAccessPolicy.Options.parse(
            new String[]{"--package", APP + ".smoke", "--token", TOKEN}).packageName);
    }

    @Test public void instrumentationCarriesTokenAudioAndDisablesHiddenApiChecks() {
        List<String> args = ManualAccessPolicy.arguments(options("--audio"));
        assertEquals(Arrays.asList("instrument", "-w", "-r", "--no-hidden-api-checks", "-e", "headswap_activation_token", TOKEN,
            "-e", "headswap_audio", "true", APP + "/" + DelegatedDesktopInstrumentation.class.getName()), args);
        List<String> plain = ManualAccessPolicy.arguments(options());
        assertFalse(plain.contains("headswap_audio"));
        assertTrue(plain.contains("--no-hidden-api-checks")); // task display/user are hidden API (embedded app state)
        assertFalse(plain.contains("headswap_screen"));
    }

    @Test public void componentUsesRuntimePackageAndNamespaceClass() {
        String suffixed = ManualAccessPolicy.component(APP + ".smoke");
        assertEquals(APP + ".smoke/com.yegorkapomidorka.headswap.dex.DelegatedDesktopInstrumentation", suffixed);
        assertEquals(DelegatedDesktopInstrumentation.class.getName(), ManualAccessPolicy.INSTRUMENTATION_CLASS);
        assertEquals(ManualAccessLauncher.class.getName(), ManualAccessPolicy.ENTRY);
        assertEquals(DesktopAudioPolicy.ACTIVATION_ARGUMENT, ManualAccessPolicy.AUDIO_ARGUMENT);
        rejected(() -> ManualAccessPolicy.component("x;y"));
    }

    @Test public void launchUsesExecAndRejectsShellInjectedTokens() {
        String command = ManualAccessPolicy.launchCommand(options());
        assertTrue(command.startsWith("echo HEADSWAP_ACCESS_LAUNCHER_PID=$$; exec am instrument -w -r"));
        assertFalse(command.contains("force-stop")); assertFalse(command.contains("pkill"));
        for (String bad : Arrays.asList(TOKEN + ";id", "", TOKEN.toUpperCase(), TOKEN.substring(1), TOKEN + "0")) {
            rejected(() -> ManualAccessPolicy.launchCommand(new ManualAccessPolicy.Options(false, false, APP, bad)));
        }
    }

    @Test public void openAppTargetsTheInstalledPackageLauncher() {
        List<String> args = ManualAccessPolicy.openAppArguments(APP + ".smoke");
        assertEquals("-p", args.get(args.size() - 2));
        assertEquals(APP + ".smoke", args.get(args.size() - 1));
        assertTrue(args.contains("android.intent.category.LAUNCHER"));
        assertFalse(args.contains("-n")); // No hardcoded activity class.
    }

    @Test public void generatedTokensAreRandom128BitHex() {
        String first = ManualAccessPolicy.newToken(), second = ManualAccessPolicy.newToken();
        assertTrue(ManualAccessPolicy.isValidToken(first));
        assertEquals(32, first.length());
        assertEquals(16, ManualAccessPolicy.TOKEN_BYTES);
        assertNotEquals(first, second);
    }

    @Test public void tokenComparisonFailsClosedOnAnyMissingOrMalformedSide() {
        assertTrue(ManualAccessPolicy.tokensMatch(TOKEN, TOKEN));
        assertFalse(ManualAccessPolicy.tokensMatch(TOKEN, "fedcba9876543210fedcba9876543210"));
        assertFalse(ManualAccessPolicy.tokensMatch(TOKEN, TOKEN.substring(0, 31) + "0")); // Last nibble differs.
        assertFalse(ManualAccessPolicy.tokensMatch(null, TOKEN));
        assertFalse(ManualAccessPolicy.tokensMatch(TOKEN, null));
        assertFalse(ManualAccessPolicy.tokensMatch(null, null));
        assertFalse(ManualAccessPolicy.tokensMatch("", ""));
        assertFalse(ManualAccessPolicy.tokensMatch("short", "short"));
        assertFalse(ManualAccessPolicy.tokensMatch(TOKEN.toUpperCase(), TOKEN.toUpperCase()));
        assertFalse(ManualAccessPolicy.tokensMatch(TOKEN, TOKEN + "0"));
    }

    @Test public void computerCommandCarriesPackageApkAndTokenWithoutShellMetacharacters() {
        String shell = ManualAccessPolicy.shellCommand(APK, options("--audio"));
        assertEquals("CLASSPATH=" + APK + " app_process / com.yegorkapomidorka.headswap.dex.ManualAccessLauncher"
            + " --package " + APP + " --token " + TOKEN + " --audio", shell);
        assertEquals("adb -d shell " + shell, ManualAccessPolicy.adbCommand(APK, options("--audio")));
        assertFalse(ManualAccessPolicy.adbCommand(APK, options()).endsWith("--audio"));
        for (char c : "$|&;<>`\"' ()\\*?!{}".toCharArray()) {
            assertFalse("metacharacter " + c, shell.substring(0, shell.indexOf(" app_process")).indexOf(c) >= 0);
        }
        // The command must round-trip through the launcher's own parser.
        String[] args = shell.substring(shell.indexOf("--package")).split(" ");
        assertEquals(TOKEN, ManualAccessPolicy.Options.parse(args).token);
        assertEquals(APP, ManualAccessPolicy.Options.parse(args).packageName);
        assertTrue(ManualAccessPolicy.Options.parse(args).audio);
    }

    @Test public void smokeApplicationIdIsUsedAsIs() {
        ManualAccessPolicy.Options smoke = new ManualAccessPolicy.Options(false, false, APP + ".smoke", TOKEN);
        String shell = ManualAccessPolicy.shellCommand(APK, smoke);
        assertTrue(shell.contains("--package " + APP + ".smoke "));
        assertTrue(shell.contains("ManualAccessLauncher")); // Class name stays in the namespace.
        assertFalse(shell.contains("com.yegorkapomidorka.headswap.smoke.dex"));
    }

    @Test public void unsafeApkPathsAreRefused() {
        for (String bad : Arrays.asList(null, "", "base.apk", "/data/app/x y/base.apk", "/data/app/$(id)/base.apk",
                "/data/app/a;b/base.apk", "/data/app/a|b/base.apk", "/data/app/a'b/base.apk", "/data/app/base.dex",
                "/data/app/\"/base.apk", "/data/app/a&b/base.apk")) {
            rejected(() -> ManualAccessPolicy.shellCommand(bad, options()));
        }
    }

    @Test public void exactOwnUidAndArgumentsAreRequired() {
        ManualAccessPolicy.Options opts = options("--audio");
        ManualAccessPolicy.Identity own = identity(launcherArgs(opts), "2000 2000 2000 2000", 12345);
        assertTrue(ManualAccessPolicy.ownsLauncher(own, opts));
        ManualAccessPolicy.Options otherToken = new ManualAccessPolicy.Options(true, false, APP, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertFalse(ManualAccessPolicy.ownsLauncher(own, otherToken));
        assertFalse(ManualAccessPolicy.ownsLauncher(own, options()));
        assertFalse(ManualAccessPolicy.ownsLauncher(own, new ManualAccessPolicy.Options(true, false, APP + ".smoke", TOKEN)));
        assertFalse(ManualAccessPolicy.ownsLauncher(identity(launcherArgs(opts), "0 2000 2000 2000", 12345), opts));
        assertFalse(ManualAccessPolicy.ownsLauncher(identity(launcherArgs(opts), "10001 10001 10001 10001", 12345), opts));
    }

    @Test public void wrongComponentOrExtraArgumentCannotBeKilled() {
        List<String> wrong = launcherArgs(options()); wrong.set(wrong.size()-1, "foreign/.Instrumentation");
        ManualAccessPolicy.Identity other = identity(wrong, "2000 2000 2000 2000", 12);
        assertFalse(ManualAccessPolicy.ownsLauncher(other, options()));
        rejected(() -> ManualAccessPolicy.guardedKillCommand(other, options()));
        wrong = launcherArgs(options()); wrong.add("extra");
        assertFalse(ManualAccessPolicy.ownsLauncher(identity(wrong, "2000 2000 2000 2000", 12), options()));
    }

    @Test public void guardRechecksUidExactArgumentsAndKernelStartTime() {
        ManualAccessPolicy.Identity own = identity(launcherArgs(options()), "2000 2000 2000 2000", 12345);
        String guard = ManualAccessPolicy.guardedKillCommand(own, options());
        assertTrue(guard.contains("p=314\n")); assertTrue(guard.contains("2000:2000:2000:2000"));
        assertTrue(guard.contains("test \"$a\" = \"$expected\""));
        assertTrue(guard.contains("test \"${20}\" = 12345"));
        assertTrue(guard.endsWith("kill -KILL \"$p\""));
        assertFalse(guard.contains("pkill")); assertFalse(guard.contains("killall")); assertFalse(guard.contains("force-stop"));
    }

    @Test public void changedKernelStartTimeChangesGuard() {
        String first = ManualAccessPolicy.guardedKillCommand(identity(launcherArgs(options()), "2000 2000 2000 2000", 12), options());
        String second = ManualAccessPolicy.guardedKillCommand(identity(launcherArgs(options()), "2000 2000 2000 2000", 13), options());
        assertNotEquals(first, second);
    }

    @Test public void processParsingHandlesAppNameNulPaddingAndParentheses() {
        byte[] padded = (APP + "\0\0\0\0").getBytes(StandardCharsets.UTF_8);
        ManualAccessPolicy.Identity app = ManualAccessPolicy.parseIdentity(314, "Uid:\t10123 10123 10123 10123\n", padded, stat(314, 8));
        assertEquals(Arrays.asList(APP), app.argv); assertEquals(8, app.startTicks);
    }

    @Test public void reportedAppProcessIsCheckedAgainstTheInstalledUidAndName() {
        byte[] name = (APP + "\0").getBytes(StandardCharsets.UTF_8);
        ManualAccessPolicy.Identity app = ManualAccessPolicy.parseIdentity(314, "Uid:\t10123 10123 10123 10123\n", name, stat(314, 8));
        assertTrue(ManualAccessPolicy.isAppProcess(app, 10123, APP));
        assertFalse(ManualAccessPolicy.isAppProcess(app, 10124, APP)); // Someone else's process
        assertFalse(ManualAccessPolicy.isAppProcess(app, 10123, APP + ".smoke"));
        assertFalse(ManualAccessPolicy.isAppProcess(app, 2000, APP));
        assertFalse(ManualAccessPolicy.isAppProcess(null, 10123, APP));
        ManualAccessPolicy.Identity mixed = ManualAccessPolicy.parseIdentity(314, "Uid:\t10123 0 10123 10123\n", name, stat(314, 8));
        assertFalse(ManualAccessPolicy.isAppProcess(mixed, 10123, APP));
        ManualAccessPolicy.Identity reused = ManualAccessPolicy.parseIdentity(314, "Uid:\t10123 10123 10123 10123\n", name, stat(314, 9));
        assertTrue(ManualAccessPolicy.sameProcess(app, app));
        assertFalse(ManualAccessPolicy.sameProcess(app, reused)); // PID reuse changes the start time.
        assertFalse(ManualAccessPolicy.sameProcess(app, null));
    }

    @Test public void malformedOrDifferentProcIdentityFailsClosed() {
        rejected(() -> ManualAccessPolicy.parseIdentity(1, "Uid: 2000 2000 2000 2000", cmdline(launcherArgs(options())), stat(1, 2)));
        rejected(() -> ManualAccessPolicy.parseIdentity(314, "Uid: 2000 2000", cmdline(launcherArgs(options())), stat(314, 2)));
        rejected(() -> ManualAccessPolicy.parseIdentity(314, "Uid: 2000 2000 2000 2000", cmdline(launcherArgs(options())), stat(315, 2)));
        rejected(() -> ManualAccessPolicy.parseIdentity(314, "Uid: 2000 2000 2000 2000", new byte[]{'x'}, stat(314, 2)));
        rejected(() -> identity(Arrays.asList("bad\narg"), "2000 2000 2000 2000", 2));
        rejected(() -> identity(launcherArgs(options()), "2000 2000 2000 2000", 0));
    }

    @Test public void readyNeedsSuccessfulStatusBundleWithAppPidInAnyKeyOrder() {
        ManualAccessPolicy.ReadyOutput out = new ManualAccessPolicy.ReadyOutput();
        out.feed("HEADSWAP_ACCESS_LAUNCHER_PID=314");
        out.feed("INSTRUMENTATION_STATUS_CODE: 0"); assertFalse(out.ready);
        out.feed("INSTRUMENTATION_STATUS: headswap_access=ready"); assertFalse(out.ready);
        out.feed("INSTRUMENTATION_STATUS_CODE: -1"); assertFalse(out.ready);
        out.feed("INSTRUMENTATION_STATUS: headswap_access=ready");
        out.feed("INSTRUMENTATION_STATUS_CODE: 0"); assertFalse(out.ready); // No app PID in the bundle.
        out.feed("INSTRUMENTATION_STATUS: headswap_pid=4711");
        out.feed("INSTRUMENTATION_STATUS: headswap_playback_audio=true");
        out.feed("INSTRUMENTATION_STATUS: headswap_access=ready");
        out.feed("INSTRUMENTATION_STATUS: headswap_media_control=true");
        out.feed("INSTRUMENTATION_STATUS_CODE: 0");
        assertTrue(out.ready); assertEquals(Integer.valueOf(314), out.pid); assertEquals(Integer.valueOf(4711), out.appPid);
        assertTrue(out.mediaControl); assertTrue(out.playbackAudio);
    }

    @Test public void capabilitiesDoNotLeakAcrossBundles() {
        ManualAccessPolicy.ReadyOutput out = new ManualAccessPolicy.ReadyOutput();
        out.feed("INSTRUMENTATION_STATUS: headswap_media_control=true");
        out.feed("INSTRUMENTATION_STATUS_CODE: 1");
        out.feed("INSTRUMENTATION_STATUS: headswap_access=ready");
        out.feed("INSTRUMENTATION_STATUS: headswap_pid=4711");
        out.feed("INSTRUMENTATION_STATUS: headswap_media_control=false");
        out.feed("INSTRUMENTATION_STATUS_CODE: 0");
        assertTrue(out.ready); assertFalse(out.mediaControl); assertFalse(out.playbackAudio);
    }

    @Test public void conflictingPidAndFailureAreNotAcceptedAsSuccess() {
        ManualAccessPolicy.ReadyOutput out = new ManualAccessPolicy.ReadyOutput();
        out.feed("HEADSWAP_ACCESS_LAUNCHER_PID=314");
        rejected(() -> out.feed("HEADSWAP_ACCESS_LAUNCHER_PID=315"));
        rejected(() -> out.feed("HEADSWAP_ACCESS_LAUNCHER_PID=2147483648"));
        rejected(() -> out.feed("INSTRUMENTATION_STATUS: headswap_pid=1"));
        rejected(() -> out.feed("INSTRUMENTATION_STATUS: headswap_pid=12x"));
        out.feed("INSTRUMENTATION_FAILED: rejected"); assertNotNull(out.failure); assertFalse(out.ready);
    }

    @Test public void tokenMismatchReportedByTheAppIsAFailureAndNeverReady() {
        ManualAccessPolicy.ReadyOutput out = new ManualAccessPolicy.ReadyOutput();
        out.feed("HEADSWAP_ACCESS_LAUNCHER_PID=314");
        out.feed("INSTRUMENTATION_RESULT: headswap_access=failed");
        out.feed("INSTRUMENTATION_RESULT: headswap_access_error=Токен активации не совпал. Скопируйте свежую команду");
        out.feed("INSTRUMENTATION_CODE: 0");
        assertEquals("Токен активации не совпал. Скопируйте свежую команду", out.failure);
        assertFalse(out.ready);
    }

    @Test public void failureCannotBeOverriddenByLaterReadyOrGenericShortMessage() {
        ManualAccessPolicy.ReadyOutput out = new ManualAccessPolicy.ReadyOutput();
        out.feed("INSTRUMENTATION_STATUS: headswap_access_error=Сначала включите «Звук по USB» в HeadSwap");
        out.feed("INSTRUMENTATION_STATUS: headswap_access=ready");
        out.feed("INSTRUMENTATION_STATUS: headswap_pid=4711");
        out.feed("INSTRUMENTATION_STATUS_CODE: 0");
        out.feed("INSTRUMENTATION_RESULT: shortMsg=generic failure");
        assertFalse(out.ready);
        assertEquals("Сначала включите «Звук по USB» в HeadSwap", out.failure);
    }

    @Test public void exceptionDetailsAreBoundedSingleLineAndKeepRootCause() {
        Throwable cause = new NoSuchMethodException("UiAutomation.method");
        String result = ManualAccessPolicy.conciseFailure(new IllegalStateException("Права не выданы\n\tсистемой", cause));
        assertTrue(result.contains("IllegalStateException"));
        assertTrue(result.contains("NoSuchMethodException: UiAutomation.method"));
        assertFalse(result.contains("\n")); assertFalse(result.contains("\t"));
        String huge = ManualAccessPolicy.conciseFailure(new IllegalStateException(String.join("", java.util.Collections.nCopies(2000, "x"))));
        assertTrue(huge.length() <= 640); assertTrue(huge.endsWith("..."));
        assertEquals("IllegalStateException", ManualAccessPolicy.conciseFailure(new IllegalStateException()));
    }

    @Test public void emptyFailureStillFailsClosedAndUnrecognizedOutputCannotBecomeReady() {
        ManualAccessPolicy.ReadyOutput out = new ManualAccessPolicy.ReadyOutput();
        out.feed("INSTRUMENTATION_RESULT: headswap_access_error=");
        assertNotNull(out.failure); assertFalse(out.ready);
        ManualAccessPolicy.ReadyOutput unrelated = new ManualAccessPolicy.ReadyOutput();
        unrelated.feed("some log: headswap_access=ready"); unrelated.feed("INSTRUMENTATION_CODE: 0");
        assertFalse(unrelated.ready);
    }

}
