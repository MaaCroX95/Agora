package com.newoether.agora.ui.chat

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.viewmodel.MessagePayloadProjector
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalMessageHydrationTest {
    @Test
    fun fourBackgroundWorkersProjectOldest460InInputOrder() {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val threadIndex = AtomicInteger()
        val dispatcher = Executors.newFixedThreadPool(4) { runnable ->
            Thread(runnable, "payload-bg-${threadIndex.incrementAndGet()}").apply {
                isDaemon = true
            }
        }.asCoroutineDispatcher()
        try {
            val projected = runBlocking {
                MessagePayloadProjector(dispatcher).projectAll((0 until 460).toList()) { value ->
                    assertTrue(Thread.currentThread().name.startsWith("payload-bg-"))
                    val simultaneous = active.incrementAndGet()
                    peak.getAndUpdate { previous -> maxOf(previous, simultaneous) }
                    try {
                        Thread.sleep(2)
                        "message-$value"
                    } finally {
                        active.decrementAndGet()
                    }
                }
            }

            assertEquals((0 until 460).map { "message-$it" }, projected)
            assertTrue(peak.get() in 2..4)
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun exactIdOwnsLoadedMissingAndMismatchedPayloadStates() {
        val expected = message("expected")
        assertEquals(
            HistoricalMessageHydrationPhase.LOADING,
            initialHistoricalMessageHydrationState(null, streamingOverlay = false).phase,
        )
        val cached = initialHistoricalMessageHydrationState(expected, streamingOverlay = false)
        assertEquals(HistoricalMessageHydrationPhase.READY, cached.phase)
        assertSame(expected, cached.message)

        val loaded = observedHistoricalMessageHydrationState(expected.id, expected)
        assertEquals(HistoricalMessageHydrationPhase.READY, loaded.phase)
        assertSame(expected, loaded.message)
        assertEquals(
            HistoricalMessageHydrationPhase.FAILED,
            observedHistoricalMessageHydrationState(expected.id, null).phase,
        )
        assertEquals(
            HistoricalMessageHydrationPhase.FAILED,
            observedHistoricalMessageHydrationState(expected.id, message("wrong")).phase,
        )
    }

    @Test
    fun conversationVisibilityWaitsForPayloadButNeverForMarkdownSettlement() {
        assertFalse(
            historicalMessagePayloadReady(
                HistoricalMessageHydrationPhase.LOADING,
                streamingOverlay = false,
            ),
        )
        assertFalse(
            historicalMessagePayloadReady(
                HistoricalMessageHydrationPhase.FAILED,
                streamingOverlay = false,
            ),
        )
        assertTrue(
            historicalMessagePayloadReady(
                HistoricalMessageHydrationPhase.READY,
                streamingOverlay = false,
            ),
        )
        assertTrue(
            historicalMessagePayloadReady(
                HistoricalMessageHydrationPhase.LOADING,
                streamingOverlay = true,
            ),
        )
    }

    @Test
    fun evictionAndRapidReentryCannotReuseAnotherRowsPayload() {
        val cache = HydratedMessagePayloadLru(
            maxEntries = 16,
            maxWeightBytes = Long.MAX_VALUE,
            weightOf = { 1L },
        )
        val messages = (0 until 460).map { message("message-$it") }
        messages.forEach(cache::put)

        assertFalse(cache.contains(messages.first().id))
        assertTrue(cache.contains(messages.last().id))
        val reentered = initialHistoricalMessageHydrationState(
            cache[messages.first().id],
            streamingOverlay = false,
        )
        assertEquals(HistoricalMessageHydrationPhase.LOADING, reentered.phase)
        val loaded = observedHistoricalMessageHydrationState(messages.first().id, messages.first())
        assertSame(messages.first(), loaded.message)
    }

    @Test
    fun canceledViewportCorrectionStillSettlesHydrationExactlyOnce() {
        val gate = HistoricalMessageSettlementGate()

        assertTrue(gate.trySchedule())
        assertFalse(gate.trySchedule())
        assertTrue(gate.settle())
        assertFalse(gate.settle())
        assertFalse(gate.trySchedule())
    }

    private fun message(id: String) = ChatMessage(
        id = id,
        text = "payload-$id",
        participant = Participant.MODEL,
    )
}
