package com.yegorkapomidorka.headswap.dex

import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class ActivationTokenTest {
    private class MemoryContext(private val id: String = "com.yegorkapomidorka.headswap") : ContextWrapper(null) {
        val values = mutableMapOf<String, Any>()
        var commits = 0
        var commitSucceeds = true
        private val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when {
                method.name == "putString" -> { values[args!![0] as String] = args[1]; proxy }
                method.name == "commit" -> { commits++; commitSucceeds }
                method.name == "apply" -> error("The token must be committed, not applied")
                else -> error("Unexpected editor ${method.name}")
            }
        } as SharedPreferences.Editor
        private val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0] as String] ?: args[1]
                "edit" -> editor
                else -> error("Unexpected preferences ${method.name}")
            }
        } as SharedPreferences
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        override fun getPackageName(): String = id
        override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo().apply {
            sourceDir = "/data/app/~~Zz9==/$id-Ab1==/base.apk"
        }
    }

    @Test fun firstUseCreatesAValid128BitTokenAndPersistsItSynchronously() {
        val context = MemoryContext()
        val token = ActivationToken(context).current()
        assertTrue(ManualAccessPolicy.isValidToken(token))
        assertEquals(32, token.length)
        assertEquals(1, context.commits)
        assertEquals(token, context.values["token"])
    }

    @Test fun tokenIsStableAcrossInstancesSoTheSameCommandWorksAfterReboot() {
        val context = MemoryContext()
        val first = ActivationToken(context).current()
        assertEquals(first, ActivationToken(context).current())
        assertEquals(first, ActivationToken(context).current())
        assertEquals(1, context.commits)
    }

    @Test fun matchingNeverCreatesATokenAndFailsClosedWithoutOne() {
        val context = MemoryContext()
        val stored = ActivationToken(context)
        assertFalse(stored.matches("0123456789abcdef0123456789abcdef"))
        assertFalse(stored.matches(null))
        assertEquals(0, context.commits)
        assertTrue(context.values.isEmpty())
    }

    @Test fun onlyTheIssuedTokenMatches() {
        val context = MemoryContext()
        val store = ActivationToken(context)
        val token = store.current()
        assertTrue(store.matches(token))
        assertTrue(ActivationToken(context).matches(token))
        assertFalse(store.matches(null))
        assertFalse(store.matches(""))
        assertFalse(store.matches(token.uppercase()))
        assertFalse(store.matches(token.dropLast(1)))
        assertFalse(store.matches(token + "0"))
        val flipped = token.take(31) + (if (token.last() == '0') '1' else '0')
        assertFalse(store.matches(flipped))
    }

    @Test fun corruptStoredValueIsReplacedAndNeverMatchesItself() {
        val context = MemoryContext()
        context.values["token"] = "not-a-token"
        val store = ActivationToken(context)
        assertFalse(store.matches("not-a-token"))
        val fresh = store.current()
        assertTrue(ManualAccessPolicy.isValidToken(fresh))
        assertTrue(store.matches(fresh))
    }

    @Test fun explicitRegenerationInvalidatesTheOldCommand() {
        val context = MemoryContext()
        val store = ActivationToken(context)
        val old = store.current()
        val renewed = store.regenerate()
        assertNotEquals(old, renewed)
        assertFalse(store.matches(old))
        assertTrue(store.matches(renewed))
        assertEquals(renewed, store.current())
    }

    @Test fun failedCommitIsReportedAndNoCommandIsHandedOut() {
        val context = MemoryContext()
        context.commitSucceeds = false
        assertThrows(IllegalStateException::class.java) { ActivationToken(context).current() }
    }

    @Test fun commandCarriesTheInstalledPackageApkPathAndTheStoredToken() {
        val context = MemoryContext()
        val store = ActivationToken(context)
        val token = store.current()
        val line = store.adbCommand(audio = false)
        assertEquals("adb -d shell CLASSPATH=/data/app/~~Zz9==/com.yegorkapomidorka.headswap-Ab1==/base.apk " +
            "app_process / com.yegorkapomidorka.headswap.dex.ManualAccessLauncher " +
            "--package com.yegorkapomidorka.headswap --token $token", line)
        assertTrue(store.adbCommand(audio = true).endsWith(" --audio"))
        assertEquals(line.removePrefix("adb -d shell "), store.shellCommand(audio = false))
    }

    @Test fun suffixedApplicationIdFlowsIntoTheCommandUnchanged() {
        val smoke = "com.yegorkapomidorka.headswap.smoke"
        val line = ActivationToken(MemoryContext(smoke)).adbCommand(audio = true)
        assertTrue(line.contains("--package $smoke --token "))
        assertTrue(line.contains("/$smoke-Ab1==/base.apk"))
        // The launcher class is addressed by its namespace name, not by the suffixed id.
        assertTrue(line.contains(" com.yegorkapomidorka.headswap.dex.ManualAccessLauncher "))
    }

    @Test fun theCommandParsesBackIntoTheSameActivationRequest() {
        val context = MemoryContext()
        val store = ActivationToken(context)
        val args = store.shellCommand(audio = true).substringAfter("ManualAccessLauncher ").split(' ').toTypedArray()
        val options = ManualAccessPolicy.Options.parse(args)
        assertEquals("com.yegorkapomidorka.headswap", options.packageName)
        assertTrue(options.audio)
        assertTrue(store.matches(options.token))
    }
}
