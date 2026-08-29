package com.newoether.agora.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
        assertFalse(restored.metadata.capacityLimitReached)
    }

    @Test
    fun `payload limit truncates without an event count eviction`() {
        val store = DiagnosticCaptureStore(
            directory = root,
            maxPayloadBytes = 4,
            maxRetainedPayloadBytes = 1_024L,
        )
        val events = listOf(wireEvent(1L, "abcdef")) +
            (2L..513L).map { sequence -> wireEvent(sequence, "x") }
        val state = store.appendBatch(
            DiagnosticStoredState(metadata = metadata()),
            events,
        )

        assertEquals(513, state.events.size)
        assertEquals(1L, state.events.first().sequence)
        assertEquals(513L, state.events.last().sequence)
        assertEquals("abcd", state.events.first().wireText())
        assertTrue(state.events.first().wireCapture().truncated)
        assertEquals(1L, state.metadata.truncatedPayloadCount)
        assertEquals(0L, state.metadata.evictedEventCount)
        assertEquals(516L, state.retainedPayloadBytes)
        assertFalse(state.metadata.capacityLimitReached)
    }

    @Test
    fun `batch retains the complete fitting prefix and rejects the over-limit event whole`() {
        val store = DiagnosticCaptureStore(
            directory = root,
            maxPayloadBytes = 100,
            maxRetainedPayloadBytes = 6L,
        )
        val state = store.appendBatch(
            DiagnosticStoredState(metadata = metadata()),
            listOf(
                wireEvent(1L, "abc"),
                wireEvent(2L, "de"),
                wireEvent(3L, "fg"),
            ),
        )

        assertEquals(listOf(1L, 2L), state.events.map(DiagnosticEvent::sequence))
        assertEquals(listOf("abc", "de"), state.events.map { it.wireText() })
        assertEquals(5L, state.retainedPayloadBytes)
        assertEquals(3L, state.metadata.nextSequence)
        assertEquals(DiagnosticCaptureState.PAUSED, state.metadata.state)
        assertTrue(state.metadata.capacityLimitReached)
        assertEquals(0L, state.metadata.evictedEventCount)
        assertFalse(File(root, "events/00000000000000000003.json").exists())
    }

    @Test
    fun `event reaching the exact capacity is retained and pauses capture`() {
        val store = DiagnosticCaptureStore(
            directory = root,
            maxPayloadBytes = 100,
            maxRetainedPayloadBytes = 5L,
        )
        val state = store.appendBatch(
            DiagnosticStoredState(metadata = metadata()),
            listOf(wireEvent(1L, "abc"), wireEvent(2L, "de")),
        )

        assertEquals(listOf(1L, 2L), state.events.map(DiagnosticEvent::sequence))
        assertEquals(5L, state.retainedPayloadBytes)
        assertEquals(3L, state.metadata.nextSequence)
        assertEquals(DiagnosticCaptureState.PAUSED, state.metadata.state)
        assertTrue(state.metadata.capacityLimitReached)
    }

    @Test
    fun `capacity pause survives reload without deleting retained events`() {
        val store = DiagnosticCaptureStore(root, maxPayloadBytes = 100, maxRetainedPayloadBytes = 5L)
        val limited = store.appendBatch(
            DiagnosticStoredState(metadata = metadata()),
            listOf(wireEvent(1L, "abc"), wireEvent(2L, "de")),
        )

        val restored = DiagnosticCaptureStore(
            root,
            maxPayloadBytes = 100,
            maxRetainedPayloadBytes = 5L,
        ).load()

        assertEquals(limited, restored)
        assertEquals(listOf(1L, 2L), restored.events.map(DiagnosticEvent::sequence))
        assertEquals(0L, restored.metadata.evictedEventCount)
    }

    @Test
    fun `load preserves legacy events over a tighter capacity and marks the session incomplete`() {
        val permissive = DiagnosticCaptureStore(
            directory = root,
            maxPayloadBytes = 100,
            maxRetainedPayloadBytes = 100L,
        )
        val stored = permissive.appendBatch(
            DiagnosticStoredState(metadata = metadata()),
            listOf(wireEvent(1L, "abcd"), wireEvent(2L, "efgh")),
        )

        val tightened = DiagnosticCaptureStore(
            root,
            maxPayloadBytes = 100,
            maxRetainedPayloadBytes = 6L,
        ).load()

        assertEquals(stored.events, tightened.events)
        assertEquals(8L, tightened.retainedPayloadBytes)
        assertEquals(DiagnosticCaptureState.PAUSED, tightened.metadata.state)
        assertTrue(tightened.metadata.capacityLimitReached)
        assertEquals(0L, tightened.metadata.evictedEventCount)
    }

    @Test
    fun `legacy schema one metadata defaults capacity marker to false`() {
        root.mkdirs()
        File(root, "capture-metadata.json").writeText(
            """{"schemaVersion":1,"state":"RUNNING","sessionId":"legacy","startedAtMillis":5,"nextSequence":1,"droppedEventCount":0,"evictedEventCount":2,"truncatedPayloadCount":0}""",
        )

        val restored = DiagnosticCaptureStore(root).load()

        assertEquals(DiagnosticCaptureState.RUNNING, restored.metadata.state)
        assertEquals("legacy", restored.metadata.sessionId)
        assertEquals(2L, restored.metadata.evictedEventCount)
        assertFalse(restored.metadata.capacityLimitReached)
    }

    @Test
    fun `reload truncates oversized persisted payload once`() {
        val permissive = DiagnosticCaptureStore(root, maxPayloadBytes = 100, maxRetainedPayloadBytes = 1_000L)
        val state = permissive.append(
            DiagnosticStoredState(metadata = metadata()),
            wireEvent(1L, "abcdef"),
        )

        val tightened = DiagnosticCaptureStore(
            root,
            maxPayloadBytes = 4,
            maxRetainedPayloadBytes = 1_000L,
        ).load()
        val secondReload = DiagnosticCaptureStore(
            root,
            maxPayloadBytes = 4,
            maxRetainedPayloadBytes = 1_000L,
        ).load()

        assertEquals("abcd", tightened.events.single().wireText())
        assertEquals(1L, tightened.metadata.truncatedPayloadCount)
        assertEquals(state.metadata.evictedEventCount, tightened.metadata.evictedEventCount)
        assertEquals(1L, secondReload.metadata.truncatedPayloadCount)
    }

    @Test
    fun `batch rejects non-positive event sequences`() {
        val store = DiagnosticCaptureStore(root)

        assertThrows(IllegalArgumentException::class.java) {
            store.appendBatch(
                DiagnosticStoredState(metadata = metadata(nextSequence = 0L)),
                listOf(wireEvent(0L, "invalid")),
            )
        }
        assertFalse(File(root, "events/00000000000000000000.json").exists())
    }

    @Test
    fun `clear preserves paused session and monotonic sequence while resetting completeness counters`() {
        val store = DiagnosticCaptureStore(
            directory = root,
            maxPayloadBytes = 100,
            maxRetainedPayloadBytes = 8L,
        )
        val limited = store.append(
            DiagnosticStoredState(
                metadata = metadata(
                    state = DiagnosticCaptureState.RUNNING,
                    sessionId = "paused-session",
                    startedAtMillis = 50L,
                    nextSequence = 7L,
                    dropped = 2L,
                    evicted = 3L,
                    truncated = 4L,
                ),
            ),
            wireEvent(7L, "retained"),
        )
        assertEquals(listOf(7L), limited.events.map(DiagnosticEvent::sequence))
        assertEquals(8L, limited.metadata.nextSequence)
        assertTrue(limited.metadata.capacityLimitReached)

        val cleared = store.clear(limited)
        val restored = store.load()

        assertTrue(cleared.events.isEmpty())
        assertEquals(DiagnosticCaptureState.PAUSED, restored.metadata.state)
        assertEquals("paused-session", restored.metadata.sessionId)
        assertEquals(8L, restored.metadata.nextSequence)
        assertEquals(0L, restored.metadata.droppedEventCount)
        assertEquals(0L, restored.metadata.evictedEventCount)
        assertEquals(0L, restored.metadata.truncatedPayloadCount)
        assertFalse(restored.metadata.capacityLimitReached)
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
        capacityLimitReached: Boolean = false,
    ) = DiagnosticCaptureMetadata(
        state = state,
        sessionId = sessionId,
        startedAtMillis = startedAtMillis,
        nextSequence = nextSequence,
        droppedEventCount = dropped,
        evictedEventCount = evicted,
        truncatedPayloadCount = truncated,
        capacityLimitReached = capacityLimitReached,
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
