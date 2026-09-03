package com.newoether.agora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopLevelPresentationStateTest {
    @Test
    fun ownerIsRetainedUntilMatchingExitCompletes() {
        val owners = mutableListOf<TopLevelPresentation>()
        val state = TopLevelPresentationState(onOwnerChanged = owners::add)
        state.present(TopLevelPresentation.SETTINGS)

        assertEquals(TopLevelPresentation.SETTINGS, state.owner)
        assertTrue(state.release(TopLevelPresentation.SETTINGS))
        assertEquals(TopLevelPresentation.CHAT, state.owner)
        assertEquals(
            listOf(TopLevelPresentation.CHAT, TopLevelPresentation.SETTINGS, TopLevelPresentation.CHAT),
            owners,
        )
    }

    @Test
    fun restoredBlockingOwnerStartsFailClosed() {
        val owners = mutableListOf<TopLevelPresentation>()
        val state = TopLevelPresentationState(TopLevelPresentation.TASKS, owners::add)

        assertEquals(TopLevelPresentation.TASKS, state.owner)
        assertEquals(listOf(TopLevelPresentation.TASKS), owners)
    }

    @Test
    fun staleExitCannotReleaseNewerPresentation() {
        val owners = mutableListOf<TopLevelPresentation>()
        val state = TopLevelPresentationState(onOwnerChanged = owners::add)
        state.present(TopLevelPresentation.SETTINGS)
        state.present(TopLevelPresentation.MEDIA_PREVIEW)

        assertFalse(state.release(TopLevelPresentation.SETTINGS))
        assertEquals(TopLevelPresentation.MEDIA_PREVIEW, state.owner)
        assertEquals(
            listOf(TopLevelPresentation.CHAT, TopLevelPresentation.SETTINGS, TopLevelPresentation.MEDIA_PREVIEW),
            owners,
        )
    }
}
