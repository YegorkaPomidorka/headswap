package com.yegorkapomidorka.headswap.dex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VdmHostPolicyTest {
    private val apk = "/data/app/~~x==/com.yegorkapomidorka.headswap-1/base.apk"

    @Test fun startCommandIsDetachedAndCarriesPackageAndPid() {
        val cmd = VdmHostPolicy.startCommand(apk, "com.yegorkapomidorka.headswap", 1234)
        assertTrue(cmd.startsWith("rm -f ${VdmHostPolicy.LOG}; CLASSPATH='$apk' /system/bin/setsid /system/bin/nohup /system/bin/app_process / "))
        assertTrue(cmd.contains(VdmHostPolicy.ENTRY + " --package com.yegorkapomidorka.headswap --pid 1234 "))
        assertTrue(cmd.endsWith(" 2>&1 &"))
    }

    @Test fun startCommandRejectsShellInput() {
        assertThrows(IllegalArgumentException::class.java) { VdmHostPolicy.startCommand("/x.apk;reboot", "a.b", 5) }
        assertThrows(IllegalArgumentException::class.java) { VdmHostPolicy.startCommand(apk, "a.b;id", 5) }
        assertThrows(IllegalArgumentException::class.java) { VdmHostPolicy.startCommand(apk, "a.b", 1) }
    }

    @Test fun argsRoundTrip() {
        val args = VdmHostPolicy.Args.parse(arrayOf("--package", "a.b", "--pid", "42"))
        assertEquals("a.b", args.packageName)
        assertEquals(42, args.appPid)
        assertThrows(IllegalArgumentException::class.java) { VdmHostPolicy.Args.parse(arrayOf("--package", "a.b")) }
        assertThrows(IllegalArgumentException::class.java) { VdmHostPolicy.Args.parse(arrayOf("--pid", "4", "--x")) }
    }

    @Test fun hostFlagsSwapOwnGroupForDeviceGroup() {
        val flags = DesktopModePolicy.ANDROID_DISPLAY_FLAGS or VdmDesktop.FLAG_OWN_DISPLAY_GROUP or VdmDesktop.FLAG_ALWAYS_UNLOCKED
        val host = VdmDesktop.hostFlags(flags)
        assertEquals(0, host and VdmDesktop.FLAG_OWN_DISPLAY_GROUP)
        assertEquals(0, host and VdmDesktop.FLAG_ALWAYS_UNLOCKED)
        assertEquals(VdmDesktop.FLAG_DEVICE_DISPLAY_GROUP, host and VdmDesktop.FLAG_DEVICE_DISPLAY_GROUP)
        assertEquals(DesktopModePolicy.ANDROID_DISPLAY_FLAGS, host and VdmDesktop.FLAG_DEVICE_DISPLAY_GROUP.inv())
    }
}
