package com.yegorkapomidorka.headswap.ui

import com.yegorkapomidorka.headswap.ui.UpgradeNotice.Launch
import org.junit.Assert.assertEquals
import org.junit.Test

class UpgradeNoticeTest {
    @Test
    fun updatedUserSeesNoticeOnce() {
        assertEquals(Launch.NOTICE, UpgradeNotice.onLaunch(onboardingDone = true, v30Shown = true, noticeShown = false, v40Shown = true))
        assertEquals(Launch.NONE, UpgradeNotice.onLaunch(onboardingDone = true, v30Shown = true, noticeShown = true, v40Shown = true))
    }

    @Test
    fun updatedUserSeesNotice30First() {
        assertEquals(Launch.NOTICE_30, UpgradeNotice.onLaunch(onboardingDone = true, v30Shown = false, noticeShown = true, v40Shown = true))
    }

    @Test
    fun oldUserSeesOneWindowPerLaunch() {
        // обновился с версии до 2.6.0: окно 3.0 сейчас, окно 2.6.0 при следующем запуске, окно 4.0 после них
        var v30 = false
        var a11y = false
        var v40 = false
        assertEquals(Launch.NOTICE_30, UpgradeNotice.onLaunch(true, v30, a11y, v40))
        v30 = true
        assertEquals(Launch.NOTICE, UpgradeNotice.onLaunch(true, v30, a11y, v40))
        a11y = true
        assertEquals(Launch.NOTICE_40, UpgradeNotice.onLaunch(true, v30, a11y, v40))
        v40 = true
        assertEquals(Launch.NONE, UpgradeNotice.onLaunch(true, v30, a11y, v40))
    }

    @Test
    fun userOn30SeesOnly40Once() {
        assertEquals(Launch.NOTICE_40, UpgradeNotice.onLaunch(onboardingDone = true, v30Shown = true, noticeShown = true, v40Shown = false))
        assertEquals(Launch.NONE, UpgradeNotice.onLaunch(onboardingDone = true, v30Shown = true, noticeShown = true, v40Shown = true))
    }

    @Test
    fun newcomerGetsOnboardingNotNotice() {
        for (v30 in listOf(false, true)) {
            for (a11y in listOf(false, true)) {
                for (v40 in listOf(false, true)) {
                    assertEquals(Launch.ONBOARDING, UpgradeNotice.onLaunch(onboardingDone = false, v30Shown = v30, noticeShown = a11y, v40Shown = v40))
                }
            }
        }
    }
}
