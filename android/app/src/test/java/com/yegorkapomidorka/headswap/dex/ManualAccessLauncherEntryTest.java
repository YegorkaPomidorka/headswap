// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import static org.junit.Assert.*;

/** These early-return cases do not contact Android or start a process. */
public final class ManualAccessLauncherEntryTest {
    @Test public void activationCommandsUseTheInstalledIdAndActualImplementationClasses() {
        String installed = com.yegorkapomidorka.headswap.BuildConfig.APPLICATION_ID;
        assertEquals(installed + "/" + DelegatedDesktopInstrumentation.class.getName(), ManualAccessPolicy.component(installed));
        assertEquals(ManualAccessLauncher.class.getName(), ManualAccessPolicy.ENTRY);
    }
    @Test public void helpReturnsZeroWithoutExitingTheCaller() throws Exception {
        PrintStream previous = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, "UTF-8"));
            assertEquals(0, ManualAccessLauncher.run(new String[]{"--help"}));
        } finally { System.setOut(previous); }
        assertTrue(output.toString("UTF-8").contains("HeadSwap ADB activation"));
        assertFalse(output.toString("UTF-8").contains("HEADSWAP_READY"));
    }
    @Test public void invalidArgumentsReturnFailureWithoutActivation() throws Exception {
        PrintStream previous = System.err;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(output, true, "UTF-8"));
            assertEquals(1, ManualAccessLauncher.run(new String[]{"--unknown"}));
        } finally { System.setErr(previous); }
        assertTrue(output.toString("UTF-8").contains("Активация не завершена"));
        assertFalse(output.toString("UTF-8").contains("HEADSWAP_READY"));
    }
    @Test public void missingTokenReturnsFailureWithoutTouchingAndroid() throws Exception {
        PrintStream previous = System.err;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(output, true, "UTF-8"));
            assertEquals(1, ManualAccessLauncher.run(new String[]{"--package", "com.yegorkapomidorka.headswap"}));
            assertEquals(1, ManualAccessLauncher.run(new String[]{}));
        } finally { System.setErr(previous); }
        assertTrue(output.toString("UTF-8").contains("--token"));
        assertFalse(output.toString("UTF-8").contains("HEADSWAP_READY"));
    }
}
