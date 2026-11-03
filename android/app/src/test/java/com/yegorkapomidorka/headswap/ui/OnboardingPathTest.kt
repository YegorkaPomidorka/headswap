package com.yegorkapomidorka.headswap.ui

import com.yegorkapomidorka.headswap.aa.BridgeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingPathTest {
    private val none: (StepId) -> Boolean = { false }

    @Test
    fun aaPathIsTheOldTenStepsAfterMode() {
        assertEquals(Readiness.STEPS.map { it.id }, OnboardingPath.AA)
        val q = OnboardingPath.queue(BridgeMode.ANDROID_AUTO, true, false, none)
        assertEquals(listOf(StepId.MODE) + OnboardingPath.AA, q)
        assertEquals(11, q.size)
    }

    @Test
    fun launcherPathSkipsAaSteps() {
        val q = OnboardingPath.queue(BridgeMode.DESKTOP, true, false, none)
        assertEquals(
            listOf(
                StepId.MODE, StepId.DEVMODE, StepId.ACTIVATE, StepId.NOTIF, StepId.BACKGROUND,
                StepId.MIC, StepId.BLUETOOTH, StepId.LOCATION, StepId.FIRST, StepId.CAR,
            ),
            q,
        )
        for (aaOnly in listOf(StepId.AA_DEV, StepId.AA_UNKNOWN, StepId.AUTOSTART)) assertFalse(aaOnly.key, aaOnly in q)
    }

    @Test
    fun androidAutoPathHasNoLauncherSteps() {
        val q = OnboardingPath.queue(BridgeMode.ANDROID_AUTO, true, true, none)
        assertFalse(StepId.DEVMODE in q)
        assertFalse(StepId.ACTIVATE in q)
    }

    @Test
    fun noModeStepBelowAndroid13() {
        // DESKTOP там недоступен, выбирать нечего: прежние десять шагов, «из 10»
        val q = OnboardingPath.queue(BridgeMode.ANDROID_AUTO, false, false, none)
        assertEquals(OnboardingPath.AA, q)
        assertFalse(StepId.MODE in q)
    }

    @Test
    fun developerModeAlreadyOnIsSkippedOnFirstRun() {
        val q = OnboardingPath.queue(BridgeMode.DESKTOP, true, false, { it == StepId.DEVMODE })
        assertFalse(StepId.DEVMODE in q)
        assertEquals(StepId.ACTIVATE, q[1])
        // номер шага считается по очереди без пропущенного
        assertEquals(2 to 9, OnboardingPath.progress(q, StepId.ACTIVATE))
    }

    @Test
    fun rerunShowsDoneSteps() {
        val q = OnboardingPath.queue(BridgeMode.DESKTOP, true, true, { true })
        assertEquals(OnboardingPath.full(BridgeMode.DESKTOP, true), q)
    }

    @Test
    fun resumesAfterActivationRestart() {
        // активация перезапускает приложение: режим отмечен, режим разработчика включён, доступ есть - идём дальше
        val done = setOf(StepId.MODE, StepId.DEVMODE, StepId.ACTIVATE)
        val q = OnboardingPath.queue(BridgeMode.DESKTOP, true, false, { it in done })
        assertEquals(StepId.NOTIF, q.first())
    }

    @Test
    fun keptStepStaysEvenIfDone() {
        val q = OnboardingPath.queue(BridgeMode.DESKTOP, true, false, { true }, keep = StepId.MODE)
        assertEquals(listOf(StepId.MODE), q)
    }

    @Test
    fun launcherStepsOpenFromDebugCommandEvenWithAaStored() {
        assertEquals(BridgeMode.DESKTOP, OnboardingPath.modeFor(BridgeMode.ANDROID_AUTO, StepId.DEVMODE))
        assertEquals(BridgeMode.DESKTOP, OnboardingPath.modeFor(BridgeMode.ANDROID_AUTO, StepId.ACTIVATE))
        assertEquals(BridgeMode.ANDROID_AUTO, OnboardingPath.modeFor(BridgeMode.ANDROID_AUTO, StepId.MODE))
        assertEquals(BridgeMode.ANDROID_AUTO, OnboardingPath.modeFor(BridgeMode.ANDROID_AUTO, null))
        assertEquals(BridgeMode.DESKTOP, OnboardingPath.modeFor(BridgeMode.DESKTOP, StepId.AA_DEV))
        // и шаг не выпадает, когда он уже выполнен (режим разработчика включён на отладочном телефоне)
        val mode = OnboardingPath.modeFor(BridgeMode.ANDROID_AUTO, StepId.DEVMODE)
        val q = OnboardingPath.queue(mode, true, false, { true }, keep = StepId.DEVMODE)
        assertTrue(StepId.DEVMODE in q)
    }

    @Test
    fun progressCountsByPath() {
        val aa = OnboardingPath.full(BridgeMode.ANDROID_AUTO, true)
        val launcher = OnboardingPath.full(BridgeMode.DESKTOP, true)
        assertEquals(1 to 11, OnboardingPath.progress(aa, StepId.MODE))
        assertEquals(2 to 11, OnboardingPath.progress(aa, StepId.AA_DEV))
        assertEquals(1 to 10, OnboardingPath.progress(launcher, StepId.MODE))
        assertEquals(3 to 10, OnboardingPath.progress(launcher, StepId.ACTIVATE))
        assertEquals(10 to 10, OnboardingPath.progress(launcher, StepId.CAR))
        assertEquals(1 to 10, OnboardingPath.progress(launcher, StepId.AA_DEV))      // нет в пути - первый
    }

    @Test
    fun everyPathStepHasInfo() {
        for (id in OnboardingPath.full(BridgeMode.ANDROID_AUTO, true) + OnboardingPath.full(BridgeMode.DESKTOP, true)) {
            assertNotNull(id.key, Readiness.info(id))
        }
        assertEquals(setOf("mode", "devmode", "activate"), Readiness.EXTRA_STEPS.map { it.id.key }.toSet())
    }
}
