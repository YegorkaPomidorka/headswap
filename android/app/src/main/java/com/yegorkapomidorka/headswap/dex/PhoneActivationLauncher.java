package com.yegorkapomidorka.headswap.dex;

import androidx.annotation.Keep;
import java.util.List;

/**
 * Detached entry of the on-phone activation: runs the same bounded activation as the computer command and,
 * on failure, opens the app, because the instrumentation may already have restarted it and the user would
 * otherwise stare at a home screen. The failure text stays in this process's output (a shell-owned log file).
 */
@Keep
public final class PhoneActivationLauncher {
    private PhoneActivationLauncher() { }

    public static void main(String[] args) {
        int exit = ManualAccessLauncher.run(args);
        if (exit != 0) openApp(args);
        System.exit(exit);
    }

    private static void openApp(String[] args) {
        try {
            ManualAccessPolicy.Options options = ManualAccessPolicy.Options.parse(args);
            List<String> arguments = ManualAccessPolicy.openAppArguments(options.packageName);
            String[] command = new String[arguments.size() + 1];
            command[0] = "am";
            for (int n = 0; n < arguments.size(); n++) command[n + 1] = arguments.get(n);
            // Only the short child created here; never an app or daemon by name.
            java.lang.Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (Exception ignored) { }
    }
}
