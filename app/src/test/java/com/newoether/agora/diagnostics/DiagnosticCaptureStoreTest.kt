package com.newoether.agora.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DiagnosticCaptureStoreTest {
    private val root = Files.createTempDirectory("agora-diagnostic-store").toFile()

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `running session and events survive reload`() {
        val store = DiagnosticCaptureStore(root)
        var state = DiagnosticStoredState(
            metadata = metadata(
                state = DiagnosticCaptureState.RUNNING,
                sessionId = "session-1",
                startedAtMillis = 100L,
            ),
        )
        state = store.persistMetadata(state)
        state = store.append(state, wireEvent(sequence = 1L, value = "event-one"))

        val restored = DiagnosticCaptureStore(root).load()

        assertEquals(DiagnosticCaptureState.RUNNING, restored.metadata.state)
        assertEquals("session-1", restored.metadata.sessionId)
        assertEquals(2L, restored.metadata.nextSequence)
        assertEquals(listOf(1L), restored.events.map(DiagnosticEvent::sequence))
        assertEquals("event-one", restored.events.single().wireText())
        assertEquals(9L, restored.retainedPayloadBytes)
    }

    @Test
    fun `payload and retained limits truncate then evict oldest`() {
        val store = DiagnosticCaptureStore(
            directory = root,
            maxEvents = 2,
            maxPayloadBytes = 4,
            maxRetainedPayloadBytes = 6L,
        )
        var state = DiagnosticStoredState(metadata = metadata())

        state = store.append(state, wireEvent(1L, "abcdef"))
        assertEquals("abcd", state.events.single().wireText())
        assertTrue(state.events.single().wireCapture().truncated)
        assertEquals(1L, state.metadata.truncatedPayloadCount)

        state = store.append(state, wireEvent(2L, "12"))
        state = store.append(state, wireEvent(3L, "34"))

        assertEquals(listOf(2L, 3L), state.events.map(DiagnosticEvent::sequence))
        assertEquals(1L, state.metadata.evictedEventCount)
        assertEquals(4L, state.retainedPayloadBytes)
        assertEquals(listOf(2L, 3L), DiagnosticCaptureStore(root, 2, 4, 6L).load()
            .events.map(DiagnosticEvent::sequence))
    }

    @Test
    fun `reload reapplies tighter payload and event limits without double counting`() {
        val permissive = DiagnosticCaptureStore(
            directory = root,
            maxEvents = 10,
            maxPayloadBytes = 100,
            maxRetainedPayloadBytes = 1_000L,
        )
        var state = DiagnosticStoredState(metadata = metadata())
        state = permissive.append(state, wireEvent(1L, "abcdef"))
        state = permissive.append(state, wireEvent(2L, "12"))
        permissive.append(state, wireEvent(3L, "34"))

        val tightened = DiagnosticCaptureStore(root, 2, 4, 100L).load()
        val secondReload = DiagnosticCaptureStore(root, 2, 4, 100L).load()

        assertEquals(listOf(2L, 3L), tightened.events.map(DiagnosticEvent::sequence))
        assertEquals(1L, tightened.metadata.evictedEventCount)
        assertEquals(0L, tightened.metadata.truncatedPayloadCount)
        assertEquals(tightened.metadata, secondReload.metadata)
    }

    @Test
    fun `reload truncates oversized persisted payload once`() {
        val permissive = DiagnosticCaptureStore(root, 10, 100, 1_000L)
        val state = permissive.append(
            DiagnosticStoredState(metadata = metadata()),
            wireEvent(1L, "abcdef"),
        )

        val tightened = DiagnosticCaptureStore(root, 10, 4, 1_000L).load()
        val secondReload = DiagnosticCaptureStore(root, 10, 4, 1_000L).load()

        assertEquals("abcd", tightened.events.single().wireText())
        assertEquals(1L, tightened.metadata.truncatedPayloadCount)
        assertEquals(state.metadata.evictedEventCount, tightened.metadata.evictedEventCount)
        assertEquals(1L, secondReload.metadata.truncatedPayloadCount)
    }

    @Test
    fun `clear preserves paused session and monotonic sequence while resetting counters`() {
        val store = DiagnosticCaptureStore(root)
        var state = DiagnosticStoredState(
            metadata = metadata(
                state = DiagnosticCaptureState.PAUSED,
                sessionId = "paused-session",
                startedAtMillis = 50L,
                nextSequence = 7L,
                dropped = 2L,
                evicted = 3L,
                truncated = 4L,
            ),
        )
        state = store.append(state, wireEvent(7L, "retained"))

        val cleared = store.clear(state)
        val restored = DiagnosticCaptureStore(root).load()

        assertTrue(cleared.events.isEmpty())
        assertEquals(DiagnosticCaptureState.PAUSED, restored.metadata.state)
        assertEquals("paused-session", restored.metadata.sessionId)
        assertEquals(8L, restored.metadata.nextSequence)
        assertEquals(0L, restored.metadata.droppedEventCount)
        assertEquals(0L, restored.metadata.evictedEventCount)
        assertEquals(0L, restored.metadata.truncatedPayloadCount)
        assertTrue(restored.events.isEmpty())
    }

    @Test
    fun `load drops malformed event and ignores incomplete temporary write`() {
        val events = File(root, "events").apply { mkdirs() }
        File(events, "00000000000000000001.json").writeText("not-json")
        File(events, "00000000000000000002.json.tmp").writeText("partial")

        val restored = DiagnosticCaptureStore(root).load()

        assertTrue(restored.events.isEmpty())
        assertEquals(1L, restored.metadata.droppedEventCount)
        assertFalse(File(events, "00000000000000000001.json").exists())
        assertFalse(File(events, "00000000000000000002.json.tmp").exists())
    }

    @Test
    fun `metadata backup is used when primary is corrupt`() {
        val store = DiagnosticCaptureStore(root)
        store.persistMetadata(
            DiagnosticStoredState(
                metadata = metadata(
                    state = DiagnosticCaptureState.RUNNING,
                    sessionId = "backup-session",
                ),
            ),
        )
        val metadata = File(root, "capture-metadata.json")
        val backup = File(root, "capture-metadata.json.bak")
        assertTrue(metadata.renameTo(backup))
        metadata.writeText("corrupt")

        val restored = DiagnosticCaptureStore(root).load()

        assertEquals(DiagnosticCaptureState.RUNNING, restored.metadata.state)
        assertEquals("backup-session", restored.metadata.sessionId)
    }

    @Test
    fun `delete all removes persisted session`() {
        val store = DiagnosticCaptureStore(root)
        store.persistMetadata(
            DiagnosticStoredState(
                metadata = metadata(
                    state = DiagnosticCaptureState.RUNNING,
                    sessionId = "to-delete",
                ),
            ),
        )

        val deleted = store.deleteAll()

        assertEquals(DiagnosticCaptureMetadata(), deleted.metadata)
        assertFalse(root.exists())
    }

    private fun metadata(
        state: DiagnosticCaptureState = DiagnosticCaptureState.RUNNING,
        sessionId: String? = "session",
        startedAtMillis: Long? = 1L,
        nextSequence: Long = 1L,
        dropped: Long = 0L,
        evicted: Long = 0L,
        truncated: Long = 0L,
    ) = DiagnosticCaptureMetadata(
        state = state,
        sessionId = sessionId,
        startedAtMillis = startedAtMillis,
        nextSequence = nextSequence,
        droppedEventCount = dropped,
        evictedEventCount = evicted,
        truncatedPayloadCount = truncated,
    )

    private fun wireEvent(sequence: Long, value: String) = DiagnosticEvent(
        sequence = sequence,
        timestampMillis = sequence * 10L,
        context = DiagnosticRequestContext(requestKind = "chat"),
        payload = DiagnosticEventPayload.WireLine(
            lineNumber = sequence,
            line = CapturedDiagnosticText(
                value = value,
                originalLength = value.length,
                truncated = false,
                redacted = true,
            ),
        ),
    )

    private fun DiagnosticEvent.wireCapture(): CapturedDiagnosticText =
        (payload as DiagnosticEventPayload.WireLine).line

    private fun DiagnosticEvent.wireText(): String = wireCapture().value
}
