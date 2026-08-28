package com.newoether.agora.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Serializes capture state changes and durable event writes on one bounded IO command queue. */
internal class DiagnosticEventBuffer(
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sessionIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val initializationLock = Any()
    private val pendingDroppedEvents = AtomicLong(0L)
    private val mutableSnapshot = MutableStateFlow(DiagnosticSnapshot())

    @Volatile
    private var initializationStarted = false

    @Volatile
    private var commandChannel: Channel<Command>? = null

    @Volatile
    private var captureState = DiagnosticCaptureState.IDLE

    private var consumer: Job? = null

    val snapshots: StateFlow<DiagnosticSnapshot> = mutableSnapshot.asStateFlow()
    val isCaptureActive: Boolean get() = captureState == DiagnosticCaptureState.RUNNING

    init {
        require(queueCapacity in 1..MAX_QUEUE_CAPACITY)
    }

    suspend fun initialize(
        store: DiagnosticCaptureStore,
        scope: CoroutineScope,
    ) {
        synchronized(initializationLock) {
            check(!initializationStarted) { "Diagnostic capture is already initialized" }
            initializationStarted = true
        }
        require(scope.isActive) { "Diagnostic capture scope is not active" }
        val restored = try {
            withContext(ioDispatcher) { store.load() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DiagnosticStoredState()
        }
        val channel = Channel<Command>(
            capacity = queueCapacity,
            onUndeliveredElement = { command ->
                command.fail(CancellationException("Diagnostic capture queue stopped"))
            },
        )
        commandChannel = channel
        publish(restored)
        consumer = scope.launch(ioDispatcher) {
            consume(store, restored, channel)
        }
    }

    suspend fun start(): DiagnosticSnapshot = submit { response -> Command.Start(response) }

    suspend fun pause(): DiagnosticSnapshot = submit { response -> Command.Pause(response) }

    suspend fun clear(): DiagnosticSnapshot = submit { response -> Command.Clear(response) }

    suspend fun disableAndClear(): DiagnosticSnapshot =
        submit { response -> Command.DisableAndClear(response) }

    suspend fun flush(): DiagnosticSnapshot = submit { response -> Command.Flush(response) }

    /** The factory must close only over credential-sanitized values. */
    fun record(eventFactory: (sequence: Long, timestampMillis: Long) -> DiagnosticEvent) {
        if (!isCaptureActive) return
        val channel = commandChannel
        if (channel == null || channel.trySend(Command.Record(eventFactory)).isFailure) {
            pendingDroppedEvents.incrementAndGet()
        }
    }

    private suspend fun submit(
        commandFactory: (CompletableDeferred<DiagnosticSnapshot>) -> ControlCommand,
    ): DiagnosticSnapshot {
        val channel = checkNotNull(commandChannel) { "Diagnostic capture is not initialized" }
        val response = CompletableDeferred<DiagnosticSnapshot>()
        channel.send(commandFactory(response))
        return response.await()
    }

    private suspend fun consume(
        store: DiagnosticCaptureStore,
        initialState: DiagnosticStoredState,
        channel: Channel<Command>,
    ) {
        var stored = initialState
        try {
            for (command in channel) {
                stored = try {
                    var next = persistPendingDrops(store, stored)
                    next = process(store, next, command)
                    persistPendingDrops(store, next)
                } catch (cancelled: CancellationException) {
                    command.fail(cancelled)
                    throw cancelled
                } catch (_: Exception) {
                    failClosed(
                        store = store,
                        state = stored,
                        command = command,
                    )
                }
                publish(stored)
                command.complete(mutableSnapshot.value)
            }
        } finally {
            channel.cancel(CancellationException("Diagnostic capture consumer stopped"))
        }
    }

    private fun process(
        store: DiagnosticCaptureStore,
        state: DiagnosticStoredState,
        command: Command,
    ): DiagnosticStoredState = when (command) {
        is Command.Start -> start(store, state)
        is Command.Pause -> pause(store, state)
        is Command.Clear -> store.clear(state)
        is Command.DisableAndClear -> store.deleteAll()
        is Command.Flush -> state
        is Command.Record -> append(store, state, command.eventFactory)
    }

    private fun start(
        store: DiagnosticCaptureStore,
        state: DiagnosticStoredState,
    ): DiagnosticStoredState = when (state.metadata.state) {
        DiagnosticCaptureState.RUNNING -> state
        DiagnosticCaptureState.PAUSED -> store.persistMetadata(
            state.copy(
                metadata = state.metadata.copy(state = DiagnosticCaptureState.RUNNING),
            ),
        )
        DiagnosticCaptureState.IDLE -> {
            val empty = store.deleteAll()
            store.persistMetadata(
                empty.copy(
                    metadata = DiagnosticCaptureMetadata(
                        state = DiagnosticCaptureState.RUNNING,
                        sessionId = sessionIdFactory(),
                        startedAtMillis = clock(),
                    ),
                ),
            )
        }
    }

    private fun pause(
        store: DiagnosticCaptureStore,
        state: DiagnosticStoredState,
    ): DiagnosticStoredState {
        if (state.metadata.state != DiagnosticCaptureState.RUNNING) return state
        return store.persistMetadata(
            state.copy(
                metadata = state.metadata.copy(state = DiagnosticCaptureState.PAUSED),
            ),
        )
    }

    private fun append(
        store: DiagnosticCaptureStore,
        state: DiagnosticStoredState,
        eventFactory: (sequence: Long, timestampMillis: Long) -> DiagnosticEvent,
    ): DiagnosticStoredState {
        if (state.metadata.state != DiagnosticCaptureState.RUNNING) return state
        val event = try {
            eventFactory(state.metadata.nextSequence, clock())
        } catch (_: Exception) {
            return store.persistMetadata(
                state.copy(
                    metadata = state.metadata.copy(
                        droppedEventCount = state.metadata.droppedEventCount + 1L,
                    ),
                ),
            )
        }
        return store.append(state, event)
    }

    private fun persistPendingDrops(
        store: DiagnosticCaptureStore,
        state: DiagnosticStoredState,
    ): DiagnosticStoredState {
        val dropped = pendingDroppedEvents.get()
        if (dropped == 0L) return state
        val persisted = store.persistMetadata(
            state.copy(
                metadata = state.metadata.copy(
                    droppedEventCount = state.metadata.droppedEventCount + dropped,
                ),
            ),
        )
        pendingDroppedEvents.addAndGet(-dropped)
        return persisted
    }

    private fun failClosed(
        store: DiagnosticCaptureStore,
        state: DiagnosticStoredState,
        command: Command,
    ): DiagnosticStoredState {
        val pending = pendingDroppedEvents.getAndSet(0L)
        val attemptedSequence = if (command is Command.Record) {
            state.metadata.nextSequence
        } else {
            null
        }
        val reconciled = try {
            store.load()
        } catch (_: Exception) {
            state
        }
        val recordRecovered = attemptedSequence != null &&
            reconciled.events.any { event -> event.sequence == attemptedSequence }
        val requiredDropped = state.metadata.droppedEventCount + pending +
            if (command is Command.Record && !recordRecovered) 1L else 0L
        val fallbackState = if (reconciled.metadata.sessionId == null) {
            DiagnosticCaptureState.IDLE
        } else {
            DiagnosticCaptureState.PAUSED
        }
        val fallback = reconciled.copy(
            metadata = reconciled.metadata.copy(
                state = fallbackState,
                droppedEventCount = maxOf(
                    reconciled.metadata.droppedEventCount,
                    requiredDropped,
                ),
            ),
        )
        return try {
            store.persistMetadata(fallback)
        } catch (_: Exception) {
            fallback
        }
    }

    private fun publish(stored: DiagnosticStoredState) {
        val metadata = stored.metadata
        val session = metadata.sessionId?.let { id ->
            metadata.startedAtMillis?.let { startedAtMillis ->
                DiagnosticSession(id = id, startedAtMillis = startedAtMillis)
            }
        }
        captureState = metadata.state
        mutableSnapshot.value = DiagnosticSnapshot(
            state = metadata.state,
            session = session,
            events = stored.events,
            nextSequence = metadata.nextSequence,
            retainedPayloadBytes = stored.retainedPayloadBytes,
            droppedEventCount = metadata.droppedEventCount,
            evictedEventCount = metadata.evictedEventCount,
            truncatedPayloadCount = metadata.truncatedPayloadCount,
        )
    }

    private sealed interface Command {
        data class Start(override val response: CompletableDeferred<DiagnosticSnapshot>) :
            ControlCommand

        data class Pause(override val response: CompletableDeferred<DiagnosticSnapshot>) :
            ControlCommand

        data class Clear(override val response: CompletableDeferred<DiagnosticSnapshot>) :
            ControlCommand

        data class DisableAndClear(
            override val response: CompletableDeferred<DiagnosticSnapshot>,
        ) : ControlCommand

        data class Flush(override val response: CompletableDeferred<DiagnosticSnapshot>) :
            ControlCommand

        data class Record(
            val eventFactory: (sequence: Long, timestampMillis: Long) -> DiagnosticEvent,
        ) : Command

        fun complete(snapshot: DiagnosticSnapshot) {
            if (this is ControlCommand) response.complete(snapshot)
        }

        fun fail(error: Throwable) {
            if (this is ControlCommand) response.completeExceptionally(error)
        }
    }

    private sealed interface ControlCommand : Command {
        val response: CompletableDeferred<DiagnosticSnapshot>
    }

    internal companion object {
        const val DEFAULT_QUEUE_CAPACITY = 128
        const val MAX_QUEUE_CAPACITY = 4_096
    }
}
