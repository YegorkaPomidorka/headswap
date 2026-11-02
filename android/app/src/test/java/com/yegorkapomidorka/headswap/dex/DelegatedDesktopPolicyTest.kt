// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class DelegatedDesktopPolicyTest {
    private val app = "com.yegorkapomidorka.headswap"

    @Test fun onlyExplicitDelegatedFactoryCreatesTheService() {
        assertEquals(0, DexUserService::class.java.constructors.count { !it.isSynthetic })
        assertTrue(Modifier.isStatic(DexUserService::class.java
            .getMethod("forDelegated", Context::class.java, IDelegatedInputGateway::class.java).modifiers))
        assertTrue(Modifier.isPublic(DexUserService::class.java.getMethod("shutdownDelegated").modifiers))
    }

    @Test fun acceptsOnlyOwnOrdinaryUidIncludingSecondaryUser() {
        DelegatedDesktopPolicy.requireOwner(10432, 10432, 10432)
        DelegatedDesktopPolicy.requireOwner(1010432, 1010432, 1010432)
        for ((process, target, caller) in listOf(Triple(2000, 10432, 10432), Triple(10432, 10432, 2000),
            Triple(10432, 10432, 10433), Triple(10432, 1010432, 10432), Triple(2000, 2000, 2000), Triple(0, 0, 0))) {
            try { DelegatedDesktopPolicy.requireOwner(process, target, caller); fail("Accepted mismatched/privileged UID") }
            catch (_: SecurityException) { }
        }
    }

    @Test fun losingEitherCorePermissionStopsNewPrivilegedWork() {
        DelegatedDesktopPolicy.requirePermissions(true, true)
        for ((trusted, input) in listOf(false to false, true to false, false to true)) {
            try { DelegatedDesktopPolicy.requirePermissions(trusted, input); fail("Accepted incomplete delegation") }
            catch (_: SecurityException) { }
        }
    }

    @Test fun homeTargetsOnlyOwnDesktopOnPositiveDisplayWithRuntimeApplicationId() {
        assertEquals(DelegatedDesktopPolicy.Component(app, DesktopModePolicy.ANDROID_HOME_CLASS, true),
            DelegatedDesktopPolicy.desktopComponent(DesktopModePolicy.ANDROID_DESKTOP, 42, app))
        // A suffixed applicationId (.smoke) is carried through, the class stays in our namespace.
        assertEquals(DelegatedDesktopPolicy.Component("$app.smoke", DesktopModePolicy.ANDROID_HOME_CLASS, true),
            DelegatedDesktopPolicy.desktopComponent(DesktopModePolicy.ANDROID_DESKTOP, 42, "$app.smoke"))
        for (mode in listOf(-1, DesktopModePolicy.SAMSUNG_DEX, 2, 3)) {
            try { DelegatedDesktopPolicy.desktopComponent(mode, 42, app); fail("Accepted unsupported mode") }
            catch (_: IllegalArgumentException) { }
        }
        for (id in listOf(-1, 0)) {
            try { DelegatedDesktopPolicy.desktopComponent(DesktopModePolicy.ANDROID_DESKTOP, id, app); fail("Accepted phone/invalid display") }
            catch (_: IllegalArgumentException) { }
        }
    }

    private fun task(display: Int, user: Int = 0, base: String? = "com.example.map", top: String? = base) =
        DelegatedDesktopPolicy.Task(display, user, base, top)

    @Test fun taskObserverNeverTurnsMissingPermissionOrFailedQueryIntoAbsent() {
        assertEquals(-1, DelegatedDesktopPolicy.taskState(42, 0, "com.example.map", false, emptyList()))
        assertEquals(-1, DelegatedDesktopPolicy.taskState(42, 0, "com.example.map", false, listOf(task(42))))
        assertEquals(-1, DelegatedDesktopPolicy.taskState(42, 0, "com.example.map", true, null))
        assertEquals(0, DelegatedDesktopPolicy.taskState(42, 0, "com.example.map", true, emptyList()))
    }

    @Test fun taskObserverFiltersPhoneSiblingAndOtherUserBeforeTopDecision() {
        val unrelated = listOf(task(0), task(43), task(42, user = 10))
        assertEquals(0, DelegatedDesktopPolicy.taskState(42, 0, "com.example.map", true, unrelated))
        assertEquals(2, DelegatedDesktopPolicy.taskState(42, 0, "com.example.map", true, unrelated + task(42)))
    }

    @Test fun taskObserverDistinguishesBackgroundActivityFromTop() {
        assertEquals(1, DelegatedDesktopPolicy.taskState(42, 0, "com.example.map", true,
            listOf(task(42, base = "com.example.music"), task(42))))
        assertEquals(1, DelegatedDesktopPolicy.taskState(42, 0, "com.example.map", true,
            listOf(task(42, top = "com.example.dialog"))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun taskObserverRejectsPhoneEvenWhenQueryPermissionMissing() {
        DelegatedDesktopPolicy.taskState(0, 0, "com.example.map", false, null)
    }

    @Test fun concurrentShutdownCannotAcknowledgeBeforeFirstProducerRelease() {
        val gate = DelegatedShutdownGate()
        val releasing = CountDownLatch(1)
        val mayRelease = CountDownLatch(1)
        val repeated = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(2)
        try {
            val first = threads.submit { gate.close {
                releasing.countDown()
                check(mayRelease.await(3, TimeUnit.SECONDS))
            } }
            assertTrue(releasing.await(1, TimeUnit.SECONDS))
            assertTrue(gate.started)
            try { gate.requireOpen(); fail("Late start admitted during terminal shutdown") }
            catch (_: IllegalStateException) { }
            val second = threads.submit {
                repeated.countDown()
                gate.close { fail("Repeated shutdown must not run another release") }
            }
            assertTrue(repeated.await(1, TimeUnit.SECONDS))
            try { second.get(100, TimeUnit.MILLISECONDS); fail("ACK before producer release") }
            catch (_: TimeoutException) { }
            mayRelease.countDown()
            first.get(1, TimeUnit.SECONDS)
            second.get(1, TimeUnit.SECONDS)
        } finally { mayRelease.countDown(); threads.shutdownNow() }
    }

    @Test fun failedShutdownNeverBecomesSuccessAckOnRetry() {
        val gate = DelegatedShutdownGate()
        val releaseFailure = IllegalStateException("producer still owns Surface")
        repeat(2) {
            try { gate.close { throw releaseFailure }; fail("Failed release acknowledged") }
            catch (error: IllegalStateException) { assertSame(releaseFailure, error) }
        }
    }
}
