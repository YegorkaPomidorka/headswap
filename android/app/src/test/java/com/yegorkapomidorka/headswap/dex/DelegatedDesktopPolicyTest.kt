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

    private fun task(display: Int, user: Int = 0, base: String? = "com.example.map", top: String? = base, visible: Boolean = true) =
        DelegatedDesktopPolicy.Task(display, user, base, top, visible)

    @Test fun taskObserverTellsMovedToBackFromTop() {
        // Back из корня: задача осталась единственной и topActivity прежний, но её не видно
        assertEquals(3, DelegatedDesktopPolicy.taskState(42, 0, "com.example.map", true, listOf(task(42, visible = false))))
        assertEquals(2, DelegatedDesktopPolicy.taskState(42, 0, "com.example.map", true, listOf(task(42, visible = true))))
        // невидимая чужая задача на том же дисплее приложение «сверху» не делает
        assertEquals(1, DelegatedDesktopPolicy.taskState(42, 0, "com.example.map", true,
            listOf(task(42, base = "com.example.music", visible = false), task(42))))
    }

    @Test fun fullscreenOnlyForVisibleTopTaskOfOwnDisplayWithoutStatusBar() {
        val yt = "com.google.android.youtube"
        fun t(display: Int, types: Int?, top: String = yt, visible: Boolean = true, user: Int = 0) =
            DelegatedDesktopPolicy.Task(display, user, top, top, visible, types)
        val full = 0b1000 // статус- и навигационные панели скрыты, остальное видно
        val normal = 0b1011
        assertEquals(1, DelegatedDesktopPolicy.fullscreen(42, 0, yt, true, listOf(t(42, full))))
        assertEquals(0, DelegatedDesktopPolicy.fullscreen(42, 0, yt, true, listOf(t(42, normal))))
        // поле не отдали, нет прав, список недоступен - неизвестно
        assertEquals(-1, DelegatedDesktopPolicy.fullscreen(42, 0, yt, true, listOf(t(42, null))))
        assertEquals(-1, DelegatedDesktopPolicy.fullscreen(42, 0, yt, false, listOf(t(42, full))))
        assertEquals(-1, DelegatedDesktopPolicy.fullscreen(42, 0, yt, true, null))
        // полноэкранный YouTube на телефоне, другом пользователе или под другим приложением не считается
        assertEquals(0, DelegatedDesktopPolicy.fullscreen(42, 0, yt, true, listOf(t(0, full), t(42, full, user = 10))))
        assertEquals(0, DelegatedDesktopPolicy.fullscreen(42, 0, yt, true, listOf(t(42, normal, top = "com.example.map"), t(42, full))))
        assertEquals(0, DelegatedDesktopPolicy.fullscreen(42, 0, yt, true, listOf(t(42, full, visible = false))))
        // экран задачи не опознан: по пакету не угадываем
        assertEquals(-1, DelegatedDesktopPolicy.fullscreen(42, 0, yt, true, listOf(t(DelegatedDesktopPolicy.UNKNOWN, full))))
    }

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

    @Test fun unreadableDisplayFallsBackToPackageInsteadOfUnknown() {
        val u = DelegatedDesktopPolicy.UNKNOWN
        // displayId/userId скрыты: состояние берём по пакету, а не -1
        assertEquals(2, DelegatedDesktopPolicy.taskState(42, 0, "com.example.map", true, listOf(task(u, u))))
        assertEquals(3, DelegatedDesktopPolicy.taskState(42, 0, "com.example.map", true, listOf(task(u, u, visible = false))))
        assertEquals(0, DelegatedDesktopPolicy.taskState(42, 0, "com.example.map", true,
            listOf(task(u, u, base = "com.example.music"))))
        assertEquals(1, DelegatedDesktopPolicy.taskState(42, 0, "com.example.map", true,
            listOf(task(u, u, base = "com.example.music"), task(u, u, base = "com.example.map", top = "com.example.dialog"))))
        // читаемые задачи по-прежнему фильтруются по дисплею, нечитаемые чужого пакета не мешают
        assertEquals(0, DelegatedDesktopPolicy.taskState(42, 0, "com.example.map", true, listOf(task(0), task(u, u, base = "x.y"))))
        assertEquals(2, DelegatedDesktopPolicy.taskState(42, 0, "com.example.map", true, listOf(task(u, u, base = "x.y"), task(42))))
    }

    @Test fun childPackagesAreThisDisplaysTasksFreshestFirstWithoutRepeats() {
        val tasks = listOf(task(42, base = "com.example.map"), task(0, base = "com.example.phone"), task(42, user = 10, base = "x.other"),
            task(42, base = "com.example.music"), task(42, base = "com.example.map"), task(42, base = null, top = "com.example.top"))
        assertEquals(listOf("com.example.map", "com.example.music", "com.example.top"),
            DelegatedDesktopPolicy.childPackages(42, 0, true, tasks))
        assertEquals(emptyList<String>(), DelegatedDesktopPolicy.childPackages(42, 0, true, emptyList()))
    }

    @Test fun childPackagesNeverGuessWhenPermissionListOrIdentityIsMissing() {
        assertNull(DelegatedDesktopPolicy.childPackages(42, 0, false, listOf(task(42))))
        assertNull(DelegatedDesktopPolicy.childPackages(42, 0, true, null))
        // нечитаемый экран задачи: пакет мог быть с телефона, списка нет вовсе
        assertNull(DelegatedDesktopPolicy.childPackages(42, 0, true, listOf(task(42), task(DelegatedDesktopPolicy.UNKNOWN, base = "x.y"))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun childPackagesRejectThePhoneDisplay() {
        DelegatedDesktopPolicy.childPackages(0, 0, true, emptyList())
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
