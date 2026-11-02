package com.yegorkapomidorka.headswap.dex;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PhoneActivationPolicyTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String APP = "com.yegorkapomidorka.headswap";
    private static final String APK = "/data/app/~~Ab1_-xYz==/com.yegorkapomidorka.headswap-Qw3==/base.apk";
    private static final String NONCE = "0123456789abcdef";

    private static ManualAccessPolicy.Options options(boolean audio) {
        return new ManualAccessPolicy.Options(audio, false, APP, TOKEN);
    }

    private static void rejected(Runnable run) {
        try { run.run(); fail("Expected rejection"); } catch (IllegalArgumentException expected) { }
    }

    @Test public void dispatchCarriesPackageTokenAndAudioFromTheArguments() {
        String command = PhoneActivationPolicy.dispatchCommand(APK, options(false), NONCE);
        assertTrue(command, command.contains("--package " + APP + " --token " + TOKEN + " </dev/null"));
        assertFalse(command.contains("--audio"));
        assertTrue(PhoneActivationPolicy.dispatchCommand(APK, options(true), NONCE).contains("--token " + TOKEN + " --audio </dev/null"));
        // the installed applicationId (a debug suffix, say) is passed as is, never a fixed name
        ManualAccessPolicy.Options suffixed = new ManualAccessPolicy.Options(false, false, APP + ".smoke", TOKEN);
        assertTrue(PhoneActivationPolicy.dispatchCommand(APK, suffixed, NONCE).contains("--package " + APP + ".smoke "));
    }

    @Test public void dispatchDetachesFromTheAdbStreamAndEchoesTheNonce() {
        String[] lines = PhoneActivationPolicy.dispatchCommand(APK, options(false), NONCE).split("\n");
        assertEquals(3, lines.length);
        assertEquals("rm -f /data/local/tmp/headswap-phone-*.log", lines[0]);
        assertEquals("CLASSPATH='" + APK + "' /system/bin/setsid /system/bin/nohup /system/bin/app_process / "
            + PhoneActivationPolicy.ENTRY + " --package " + APP + " --token " + TOKEN
            + " </dev/null >/data/local/tmp/headswap-phone-" + NONCE + ".log 2>&1 &", lines[1]);
        assertEquals("echo HEADSWAP_PHONE_DISPATCHED=" + NONCE, lines[2]);
        assertTrue(PhoneActivationPolicy.ENTRY.endsWith(".dex.PhoneActivationLauncher"));
    }

    @Test public void dispatchRejectsShellMetacharactersAndMalformedInput() {
        for (String path : new String[] {null, "", "/data/app/x/base.apk; reboot", "/data/app/x y/base.apk", "/data/app/$(id)/base.apk",
                "/data/app/x/base.apk'", "data/app/x/base.apk", "/data/app/x/base.apk\nreboot"})
            rejected(() -> PhoneActivationPolicy.dispatchCommand(path, options(false), NONCE));
        rejected(() -> PhoneActivationPolicy.dispatchCommand(APK, new ManualAccessPolicy.Options(false, false, "bad pkg;id", TOKEN), NONCE));
        rejected(() -> PhoneActivationPolicy.dispatchCommand(APK, new ManualAccessPolicy.Options(false, false, APP, "short"), NONCE));
        rejected(() -> PhoneActivationPolicy.dispatchCommand(APK, new ManualAccessPolicy.Options(false, false, APP, null), NONCE));
        rejected(() -> PhoneActivationPolicy.dispatchCommand(APK, null, NONCE));
        rejected(() -> PhoneActivationPolicy.dispatchCommand(APK, new ManualAccessPolicy.Options(false, true, null, null), NONCE));
        for (String nonce : new String[] {null, "", "xyz", "0123456789ABCDEF", "0123456789abcde", "0123456789abcdef0", "../../etc/x1234"})
            rejected(() -> PhoneActivationPolicy.dispatchCommand(APK, options(false), nonce));
    }

    @Test public void newNoncesAreValidAndDiffer() {
        String first = PhoneActivationPolicy.newNonce(), second = PhoneActivationPolicy.newNonce();
        assertTrue(PhoneActivationPolicy.isValidNonce(first));
        assertNotEquals(first, second);
        assertEquals("/data/local/tmp/headswap-phone-" + first + ".log", PhoneActivationPolicy.logPath(first));
    }

    @Test public void dispatchIsAcceptedOnlyForThisNonceWithExitZero() {
        assertTrue(PhoneActivationPolicy.dispatchAccepted(0, "HEADSWAP_PHONE_DISPATCHED=" + NONCE + "\n", NONCE));
        assertTrue(PhoneActivationPolicy.dispatchAccepted(0, "noise\r\n  HEADSWAP_PHONE_DISPATCHED=" + NONCE + "  \r\n", NONCE));
        assertFalse(PhoneActivationPolicy.dispatchAccepted(1, "HEADSWAP_PHONE_DISPATCHED=" + NONCE, NONCE));
        assertFalse(PhoneActivationPolicy.dispatchAccepted(0, "HEADSWAP_PHONE_DISPATCHED=fedcba9876543210", NONCE));
        assertFalse(PhoneActivationPolicy.dispatchAccepted(0, "HEADSWAP_PHONE_DISPATCHED=" + NONCE + "x", NONCE));
        assertFalse(PhoneActivationPolicy.dispatchAccepted(0, "", NONCE));
        assertFalse(PhoneActivationPolicy.dispatchAccepted(0, null, NONCE));
        assertFalse(PhoneActivationPolicy.dispatchAccepted(0, "HEADSWAP_PHONE_DISPATCHED=", "bad"));
    }

    @Test public void shellSessionAndInstalledCopyAreChecked() {
        assertTrue(PhoneActivationPolicy.isShellUid("2000\n"));
        assertFalse(PhoneActivationPolicy.isShellUid("0"));
        assertFalse(PhoneActivationPolicy.isShellUid("10234"));
        assertFalse(PhoneActivationPolicy.isShellUid(""));
        assertFalse(PhoneActivationPolicy.isShellUid(null));
        String listing = "package:" + APK + "\npackage:/data/app/x/split_config.arm64_v8a.apk\n";
        assertTrue(PhoneActivationPolicy.packageListed(listing, APK));
        assertFalse(PhoneActivationPolicy.packageListed(listing, "/data/app/other/base.apk"));
        assertFalse(PhoneActivationPolicy.packageListed("package:" + APK + "x\n", APK));
        assertFalse(PhoneActivationPolicy.packageListed("", APK));
        assertFalse(PhoneActivationPolicy.packageListed(null, APK));
    }

    @Test public void portAndCodeValidation() {
        assertTrue(PhoneActivationPolicy.isValidPort(1));
        assertTrue(PhoneActivationPolicy.isValidPort(65535));
        assertFalse(PhoneActivationPolicy.isValidPort(0));
        assertFalse(PhoneActivationPolicy.isValidPort(65536));
        assertFalse(PhoneActivationPolicy.isValidPort(-5));
        assertTrue(PhoneActivationPolicy.isValidCode("012345"));
        for (String code : new String[] {null, "", "12345", "1234567", "12345a", " 12345", "12 456", "١٢٣٤٥٦"})
            assertFalse(String.valueOf(code), PhoneActivationPolicy.isValidCode(code));
    }

    @Test public void launcherLogGivesTheFailureLineOrNothing() {
        String log = "Запрошен USB-звук: только воспроизводимое аудио, без микрофона.\n"
            + "Активация не завершена: За 30 секунд HeadSwap не подтвердил доступ.\n"
            + "HeadSwap не останавливался принудительно.\n";
        assertEquals("За 30 секунд HeadSwap не подтвердил доступ.", PhoneActivationPolicy.failureMessage(log));
        assertEquals("ошибка Android", PhoneActivationPolicy.failureMessage("Активация не завершена:   \u0000\n"));
        assertEquals("a b", PhoneActivationPolicy.failureMessage("Активация не завершена: a\t\u0007b"));
        assertEquals(400, PhoneActivationPolicy.failureMessage("Активация не завершена: " + "я".repeat(900)).length());
        assertNull(PhoneActivationPolicy.failureMessage("HEADSWAP_READY\nГотово.\n"));
        assertNull(PhoneActivationPolicy.failureMessage(""));
        assertNull(PhoneActivationPolicy.failureMessage(null));
        assertTrue(PhoneActivationPolicy.logShowsReady("Доступ…\nHEADSWAP_READY\nГотово."));
        assertFalse(PhoneActivationPolicy.logShowsReady(log));
        assertFalse(PhoneActivationPolicy.logShowsReady(null));
        assertEquals("tail -c 2000 /data/local/tmp/headswap-phone-" + NONCE + ".log 2>/dev/null", PhoneActivationPolicy.readLogCommand(NONCE));
    }

    @Test public void attemptProgressFollowsAccessAndTheDeadline() {
        long start = 1_000_000;
        assertEquals(PhoneActivationPolicy.Progress.PENDING, PhoneActivationPolicy.progress(start, start, false));
        assertEquals(PhoneActivationPolicy.Progress.PENDING, PhoneActivationPolicy.progress(start, start + PhoneActivationPolicy.DEADLINE_MS, false));
        assertEquals(PhoneActivationPolicy.Progress.EXPIRED, PhoneActivationPolicy.progress(start, start + PhoneActivationPolicy.DEADLINE_MS + 1, false));
        assertEquals(PhoneActivationPolicy.Progress.READY, PhoneActivationPolicy.progress(start, start + 5, true));
        assertEquals(PhoneActivationPolicy.Progress.READY, PhoneActivationPolicy.progress(start, start + 10 * PhoneActivationPolicy.DEADLINE_MS, true));
        // a clock set back, or no attempt on record, must never read as "still waiting"
        assertEquals(PhoneActivationPolicy.Progress.EXPIRED, PhoneActivationPolicy.progress(start, start - 1, false));
        assertEquals(PhoneActivationPolicy.Progress.EXPIRED, PhoneActivationPolicy.progress(0, start, false));
    }
}
