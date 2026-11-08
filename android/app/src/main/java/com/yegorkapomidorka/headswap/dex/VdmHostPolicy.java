package com.yegorkapomidorka.headswap.dex;

/** Чистые правила запуска shell-хоста VirtualDevice (этап B): команда и разбор аргументов, без shell-ввода извне. */
public final class VdmHostPolicy {
    public static final int MIN_SDK = 37;
    public static final String ENTRY = ManualAccessPolicy.DEX_CLASS_PACKAGE + ".VdmHostMain";
    public static final String LOG = "/data/local/tmp/headswap-vdm-host.log";

    private VdmHostPolicy() { }

    public static String startCommand(String apkPath, String packageName, int appPid) {
        ManualAccessPolicy.requireApkPath(apkPath);
        ManualAccessPolicy.requirePackage(packageName);
        if (appPid <= 1) throw new IllegalArgumentException("Некорректный PID приложения");
        return "rm -f " + LOG + "; CLASSPATH='" + apkPath + "' /system/bin/setsid /system/bin/nohup /system/bin/app_process / " + ENTRY
            + " --package " + packageName + " --pid " + appPid + " </dev/null >" + LOG + " 2>&1 &";
    }

    public static final class Args {
        public final String packageName;
        public final int appPid;
        Args(String packageName, int appPid) { this.packageName = packageName; this.appPid = appPid; }

        public static Args parse(String[] args) {
            String pkg = null; int pid = 0;
            for (int n = 0; n < args.length; n++) {
                if ("--package".equals(args[n]) && n + 1 < args.length && pkg == null) pkg = args[++n];
                else if ("--pid".equals(args[n]) && n + 1 < args.length && pid == 0) pid = Integer.parseInt(args[++n]);
                else throw new IllegalArgumentException("Неизвестный параметр: " + args[n]);
            }
            ManualAccessPolicy.requirePackage(pkg);
            if (pid <= 1) throw new IllegalArgumentException("Не указан --pid");
            return new Args(pkg, pid);
        }
    }
}
