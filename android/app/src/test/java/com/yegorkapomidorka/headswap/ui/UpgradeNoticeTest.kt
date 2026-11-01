package com.yegorkapomidorka.headswap.ui

import com.yegorkapomidorka.headswap.ui.UpgradeNotice.Launch
import org.junit.Assert.assertEquals
import org.junit.Test

class UpgradeNoticeTest {
    @Test
    fun updatedUserSeesNoticeOnce() {
        assertEquals(Launch.NOTICE, UpgradeNotice.onLaunch(onboardingDone = true, v30Shown = true, noticeShown = false))
        assertEquals(Launch.NONE, UpgradeNotice.onLaunch(onboardingDone = true, v30Shown = true, noticeShown = true))
    }

    @Test
    fun updatedUserSeesNotice30First() {
        assertEquals(Launch.NOTICE_30, UpgradeNotice.onLaunch(onboardingDone = true, v30Shown = false, noticeShown = true))
    }

    @Test
    fun oldUserSeesOneWindowPerLaunch() {
        // обновился с версии до 2.6.0: окно 3.0 сейчас, окно 2.6.0 при следующем запуске
        var v30 = false
        var a11y = false
        assertEquals(Launch.NOTICE_30, UpgradeNotice.onLaunch(true, v30, a11y))
        v30 = true
        assertEquals(Launch.NOTICE, UpgradeNotice.onLaunch(true, v30, a11y))
        a11y = true
        assertEquals(Launch.NONE, UpgradeNotice.onLaunch(true, v30, a11y))
    }

    @Test
    fun newcomerGetsOnboardingNotNotice() {
        for (v30 in listOf(false, true)) {
            for (a11y in listOf(false, true)) {
                assertEquals(Launch.ONBOARDING, UpgradeNotice.onLaunch(onboardingDone = false, v30Shown = v30, noticeShown = a11y))
            }
        }
    }
}
