// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import org.junit.Assert.*
import org.junit.Test

class DesktopMusicResumePolicyTest {
    private val playing = DesktopMusicResumePolicy.Playback("session-A", "item-A",
        DesktopMusicResumePolicy.State.PLAYING, 10_000)
    private val paused = playing.copy(state = DesktopMusicResumePolicy.State.PAUSED, position = 10_020)
    private fun pausedPolicy() = DesktopMusicResumePolicy().apply {
        assertTrue(pause(playing, 100))
        observe(paused, playing.token, false, 150)
    }

    @Test fun alreadyPausedOrStoppedMusicNeverArmsAutoplay() {
        for (state in listOf(DesktopMusicResumePolicy.State.PAUSED, DesktopMusicResumePolicy.State.OTHER)) {
            val policy = DesktopMusicResumePolicy()
            assertFalse(policy.pause(playing.copy(state = state), 100))
            assertNull(policy.takeResume(true, paused, playing.token, false, 200))
        }
    }
    @Test fun pauseMustBeAcknowledgedBeforeOneExactSessionCanResume() {
        val policy = DesktopMusicResumePolicy()
        assertTrue(policy.pause(playing, 100))
        assertNull(policy.takeResume(true, playing, playing.token, false, 110))
        policy.observe(paused, playing.token, false, 150)
        assertNull(policy.takeResume(false, paused, playing.token, false, 200))
        assertEquals(playing.token, policy.takeResume(true, paused, playing.token, false, 250))
        assertNull(policy.takeResume(true, paused, playing.token, false, 300))
    }
    @Test fun userCanReturnHoursLaterAndSlowHeadUnitDoesNotCausePrematurePlay() {
        val policy = pausedPolicy()
        val hours = 3 * 60 * 60 * 1_000L
        assertNull(policy.takeResume(false, paused, playing.token, false, hours))
        assertNull(policy.takeResume(false, paused, playing.token, false, hours + 90_000))
        assertEquals(playing.token, policy.takeResume(true, paused, playing.token, false, hours + 100_000))
    }
    @Test fun missingOrLatePauseAcknowledgementIsNotInvented() {
        val policy = DesktopMusicResumePolicy().apply { pause(playing, 100) }
        policy.observe(playing, playing.token, false, 3_101)
        assertFalse(policy.hasPending)
        assertNull(policy.takeResume(true, paused, playing.token, false, 3_102))
    }
    @Test fun detachedPlaybackNeedsOnlyAckOrExpiryWakeups() {
        val policy = DesktopMusicResumePolicy().apply { pause(playing, 100) }
        assertEquals(3_001L, policy.nextDeadlineDelay(100))
        policy.observe(paused, playing.token, false, 200)
        assertEquals(DesktopMusicResumePolicy.MAX_PENDING_MS - 99, policy.nextDeadlineDelay(200))
        policy.cancel()
        assertNull(policy.nextDeadlineDelay(300))
    }
    @Test fun samePackageWithAnotherSessionTokenNeverInheritsResume() {
        val policy = pausedPolicy()
        val replacement = paused.copy(token = "session-B")
        assertNull(policy.takeResume(true, replacement, replacement.token, false, 300))
        assertFalse(policy.hasPending)
    }
    @Test fun anotherPlayingOrSelectedPlayerCancelsEvenIfOriginalRemainsPaused() {
        for (another in listOf(false, true)) {
            val policy = pausedPolicy()
            assertNull(policy.takeResume(true, paused, if (another) playing.token else "other", another, 300))
            assertFalse(policy.hasPending)
        }
    }
    @Test fun initialListenerCacheCannotCancelTheActuallySelectedPlayer() {
        for (cached in listOf(null, "old-session")) {
            val policy = pausedPolicy()
            assertTrue(policy.acceptsSelectionEvent(cached) { playing.token })
            assertTrue(policy.acceptsSelectionEvent(playing.token) { error("Only the initial cache is re-read") })
            assertFalse(policy.acceptsSelectionEvent("other") { playing.token })
            assertFalse(policy.acceptsSelectionEvent(null) { playing.token })
        }
    }
    @Test fun initialCachedOwnerDoesNotMaskALiveSelectionChange() {
        for (live in listOf(null, "other")) {
            assertFalse(pausedPolicy().acceptsSelectionEvent(playing.token) { live })
        }
    }
    @Test fun changingTrackOrSeekingCancelsResume() {
        for (changed in listOf(paused.copy(item = "item-B"), paused.copy(position = 5_000))) {
            val policy = pausedPolicy()
            assertNull(policy.takeResume(true, changed, playing.token, false, 300))
            assertFalse(policy.hasPending)
        }
    }
    @Test fun userPlayingThenPausingDoesNotRestoreOurOldIntention() {
        val policy = pausedPolicy()
        policy.observe(playing, playing.token, false, 200)
        policy.observe(paused, playing.token, false, 250)
        assertNull(policy.takeResume(true, paused, playing.token, false, 300))
    }
    @Test fun repeatedPausedPublicationsAndPollsKeepTheOriginalIntention() {
        val policy = pausedPolicy()
        policy.observe(paused, playing.token, false, 200)
        assertTrue(policy.hasPending)
        policy.observe(paused, playing.token, false, 201)
        assertTrue(policy.hasPending)
        assertEquals(playing.token, policy.takeResume(true, paused, playing.token, false, 202))
    }
    @Test fun pollCanSeeOurPauseBeforeItsFirstCallbackArrives() {
        val policy = DesktopMusicResumePolicy().apply { pause(playing, 100) }
        policy.observe(paused, playing.token, false, 150)
        policy.observe(paused, playing.token, false, 151)
        assertTrue(policy.hasPending)
        assertEquals(playing.token, policy.takeResume(true, paused, playing.token, false, 200))
    }
    @Test fun roundedPausedPlayheadDoesNotMoveTheAnchorOrHideASeek() {
        val reasons = mutableListOf<String>()
        val policy = DesktopMusicResumePolicy(reasons::add).apply { pause(playing, 100) }
        policy.observe(paused, playing.token, false, 150)
        policy.observe(paused.copy(position = paused.position + 800), playing.token, false, 200)
        assertTrue(policy.acknowledged)
        assertTrue(reasons.isEmpty())
        policy.observe(paused.copy(position = paused.position + 1_400), playing.token, false, 300)
        assertFalse(policy.hasPending)
        assertEquals(listOf("paused position changed"), reasons)
        assertNull(policy.takeResume(true, paused, playing.token, false, 400))
        assertEquals(1, reasons.size)
    }
    @Test fun aPlayerCanPublishItsFirstKnownPositionAfterPause() {
        val policy = DesktopMusicResumePolicy().apply { pause(playing, 100) }
        policy.observe(paused.copy(position = -1), playing.token, false, 150)
        policy.observe(paused, playing.token, false, 200)
        assertEquals(playing.token, policy.takeResume(true, paused, playing.token, false, 250))
    }
    @Test fun cancellationReasonsDistinguishRealUserChangesWithoutLoggingItemOrSessionData() {
        val cases = listOf(
            Triple(playing, playing.token, "player left PAUSED state"),
            Triple(paused.copy(item = "private new item"), playing.token, "item changed"),
            Triple(paused, "private other token", "selected player changed"),
            Triple(null, null, "player session unavailable"),
        )
        for ((change, selected, reason) in cases) {
            val reasons = mutableListOf<String>()
            val policy = DesktopMusicResumePolicy(reasons::add).apply { pause(playing, 100) }
            policy.observe(paused, playing.token, false, 150)
            policy.observe(change, selected, false, 200)
            assertEquals(listOf(reason), reasons)
            assertFalse(policy.hasPending)
        }
    }
    @Test fun noItemIdentityAllowsPauseButNeverGuessesWhatToResume() {
        val policy = DesktopMusicResumePolicy()
        assertTrue(policy.pause(playing.copy(item = null), 100))
        assertFalse(policy.hasPending)
        assertNull(policy.takeResume(true, paused.copy(item = null), playing.token, false, 200))
    }
    @Test fun endedSessionStopOrExpiredActivationCannotResume() {
        val missing = pausedPolicy()
        assertNull(missing.takeResume(true, null, null, false, 200))
        val stopped = pausedPolicy().apply { cancel() }
        assertNull(stopped.takeResume(true, paused, playing.token, false, 200))
        val expired = pausedPolicy()
        assertNull(expired.takeResume(true, paused, playing.token, false, 101 + DesktopMusicResumePolicy.MAX_PENDING_MS))
    }
}
