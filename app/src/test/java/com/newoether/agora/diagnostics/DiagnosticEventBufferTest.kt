package com.newoether.agora.diagnostics

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DiagnosticEventBufferTest {
    private val root = Files.createTempDirectory("agora-diagnostic-buffer").toFile()

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `disabled capture does not invoke event factory`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val buffer = DiagnosticEventBuffer(queueCapacity = 4, ioDispatcher = dispatcher)
        buffer.initialize(DiagnosticCaptureStore(root), backgroundScope)
        var invoked = false

        buffer.record { sequence, timestamp ->
            invoked = true
            event(sequence, timestamp)
        }

        assertFalse(invoked)
        assertEquals(DiagnosticSnapshot(), buffer.snapshots.value)
    }

    @Test
    fun `initialize restores running session and persisted events`() = runTest {
        val store = DiagnosticCaptureStore(root)
        var stored = store.persistMetadata(
            DiagnosticStoredState(
                metadata = DiagnosticCaptureMetadata(
                    state = DiagnosticCaptureState.RUNNING,
                    sessionId = "restored-session",
                    startedAtMillis = 100L,
                ),
            ),
        )
        stored = store.append(stored, event(sequence = 1L, timestamp = 120L))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val buffer = DiagnosticEventBuffer(queueCapacity = 4, ioDispatcher = dispatcher)

        buffer.initialize(store, backgroundScope)

        val snapshot = buffer.snapshots.value
        assertEquals(DiagnosticCaptureState.RUNNING, snapshot.state)
        assertEquals("restored-session", snapshot.session?.id)
        assertEquals(100L, snapshot.session?.startedAtMillis)
        assertEquals(listOf(1L), snapshot.events.map(DiagnosticEvent::sequence))
        assertEquals(2L, snapshot.nextSequence)
        assertTrue(buffer.isCaptureActive)
    }

    @Test
    fun `start pause resume and clear preserve session and monotonic sequence`() = runTest {
        var now = 10L
        val dispatcher = StandardTestDispatcher(testScheduler)
        val buffer = DiagnosticEventBuffer(
            queueCapacity = 8,
            clock = { now++ },
            sessionIdFactory = { "session-1" },
            ioDispatcher = dispatcher,
        )
        val store = DiagnosticCaptureStore(root)
        buffer.initialize(store, backgroundScope)

        val started = buffer.start()
        buffer.record(::event)
        val first = buffer.flush()
        val paused = buffer.pause()
        var invokedWhilePaused = false
        buffer.record { sequence, timestamp ->
            invokedWhilePaused = true
            event(sequence, timestamp)
        }
        val resumed = buffer.start()
        buffer.record(::event)
        val beforeClear = buffer.flush()
        val cleared = buffer.clear()
        buffer.record(::event)
        val afterClear = buffer.flush()
        val restored = store.load()

        assertEquals(DiagnosticCaptureState.RUNNING, started.state)
        assertEquals("session-1", started.session?.id)
        assertEquals(10L, started.session?.startedAtMillis)
        assertEquals(listOf(1L), first.events.map(DiagnosticEvent::sequence))
        assertEquals(DiagnosticCaptureState.PAUSED, paused.state)
        assertFalse(invokedWhilePaused)
        assertEquals("session-1", resumed.session?.id)
        assertEquals(listOf(1L, 2L), beforeClear.events.map(DiagnosticEvent::sequence))
        assertEquals(3L, beforeClear.nextSequence)
        assertEquals(DiagnosticCaptureState.RUNNING, cleared.state)
        assertEquals("session-1", cleared.session?.id)
        assertTrue(cleared.events.isEmpty())
        assertEquals(3L, cleared.nextSequence)
        assertEquals(listOf(3L), afterClear.events.map(DiagnosticEvent::sequence))
        assertEquals(4L, afterClear.nextSequence)
        assertEquals(listOf(3L), restored.events.map(DiagnosticEvent::sequence))
        assertEquals(4L, restored.metadata.nextSequence)
    }

    @Test
    fun `bounded command queue records overflow as dropped without blocking producer`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val buffer = DiagnosticEventBuffer(queueCapacity = 1, ioDispatcher = dispatcher)
        buffer.initialize(DiagnosticCaptureStore(root), backgroundScope)
        buffer.start()

        repeat(4) {
            buffer.record(::event)
        }
        val snapshot = buffer.flush()

        assertTrue(snapshot.events.isNotEmpty())
        assertTrue(snapshot.droppedEventCount > 0L)
        assertEquals(4L, snapshot.events.size.toLong() + snapshot.droppedEventCount)
        val sequences = snapshot.events.map(DiagnosticEvent::sequence)
        assertEquals(sequences.sorted(), sequences)
        assertEquals(sequences.distinct(), sequences)
    }

    @Test
    fun `event factory failure is counted and next valid event keeps the sequence`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val buffer = DiagnosticEventBuffer(queueCapacity = 4, ioDispatcher = dispatcher)
        buffer.initialize(DiagnosticCaptureStore(root), backgroundScope)
        buffer.start()

        buffer.record { _, _ -> error("fixture failure") }
        val failed = buffer.flush()
        buffer.record(::event)
        val recovered = buffer.flush()

        assertTrue(failed.events.isEmpty())
        assertEquals(1L, failed.droppedEventCount)
        assertEquals(1L, failed.nextSequence)
        assertEquals(listOf(1L), recovered.events.map(DiagnosticEvent::sequence))
        assertEquals(2L, recovered.nextSequence)
    }

    @Test
    fun `disable deletes persisted session and returns idle snapshot`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val buffer = DiagnosticEventBuffer(queueCapacity = 4, ioDispatcher = dispatcher)
        buffer.initialize(DiagnosticCaptureStore(root), backgroundScope)
        buffer.start()
        buffer.record(::event)
        buffer.flush()
        assertTrue(root.exists())

        val disabled = buffer.disableAndClear()

        assertEquals(DiagnosticSnapshot(), disabled)
        assertFalse(buffer.isCaptureActive)
        assertFalse(root.exists())
    }

    @Test
    fun `http detail parser keeps only allowlisted metadata`() {
        val attributes = DeveloperDiagnostics.safeHttpAttributes(
            "code=200 messages=3 authorization=secret endpoint=/private proxy=DIRECT",
        )

        assertEquals(
            mapOf("code" to "200", "messages" to "3", "proxy" to "DIRECT"),
            attributes,
        )
        assertFalse(attributes.containsKey("authorization"))
        assertFalse(attributes.containsKey("endpoint"))
        assertNotNull(attributes["code"])
    }

    private fun event(sequence: Long, timestamp: Long) = DiagnosticEvent(
        sequence = sequence,
        timestampMillis = timestamp,
        context = DiagnosticRequestContext(requestKind = "chat"),
        payload = DiagnosticEventPayload.HttpStage(
            stage = "test",
            elapsedMillis = 0L,
            attributes = emptyMap(),
        ),
    )
}
