package com.newoether.agora.diagnostics

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.math.max

internal data class DiagnosticStoredState(
    val metadata: DiagnosticCaptureMetadata = DiagnosticCaptureMetadata(),
    val events: List<DiagnosticEvent> = emptyList(),
    val retainedPayloadBytes: Long = 0L,
)

/** Owns the app-private durable representation of the active diagnostic capture session. */
internal class DiagnosticCaptureStore(
    private val directory: File,
    private val maxPayloadBytes: Int = DEFAULT_MAX_PAYLOAD_BYTES,
    private val maxRetainedPayloadBytes: Long = DEFAULT_MAX_RETAINED_PAYLOAD_BYTES,
) {
    private val eventsDirectory = File(directory, EVENTS_DIRECTORY)
    private val metadataFile = File(directory, METADATA_FILE)
    private val json = Json {
        classDiscriminator = "payloadType"
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    init {
        require(maxPayloadBytes > 0)
        require(maxRetainedPayloadBytes > 0L)
    }

    fun load(): DiagnosticStoredState {
        ensureDirectories()
        cleanupTemporaryFiles(directory)
        cleanupTemporaryFiles(eventsDirectory)

        val loadedMetadata = readMetadata() ?: DiagnosticCaptureMetadata()
        if (loadedMetadata.schemaVersion != SCHEMA_VERSION) return deleteAll()

        var dropped = 0L
        var newlyTruncated = 0L
        val events = buildList {
            eventFiles().forEach { file ->
                val sequence = file.name.removeSuffix(EVENT_EXTENSION).toLongOrNull()
                val event = runCatching {
                    json.decodeFromString<DiagnosticEvent>(file.readText(Charsets.UTF_8))
                }.getOrNull()
                if (sequence == null || event == null || event.sequence != sequence) {
                    dropped++
                    file.delete()
                } else {
                    val normalized = normalize(event)
                    if (normalized.event != event) {
                        writeAtomically(file, json.encodeToString(normalized.event))
                        newlyTruncated += normalized.newlyTruncatedCount
                    }
                    add(normalized.event)
                }
            }
        }.sortedBy(DiagnosticEvent::sequence)

        val retainedPayloadBytes = events.sumOf(::payloadByteSize)
        val capacityLimitReached =
            loadedMetadata.capacityLimitReached ||
                retainedPayloadBytes >= maxRetainedPayloadBytes
        val nextSequence = max(
            loadedMetadata.nextSequence,
            (events.lastOrNull()?.sequence ?: 0L) + 1L,
        )
        val state = DiagnosticStoredState(
            metadata = loadedMetadata.copy(
                state = if (capacityLimitReached && loadedMetadata.sessionId != null) {
                    DiagnosticCaptureState.PAUSED
                } else {
                    loadedMetadata.state
                },
                nextSequence = nextSequence,
                droppedEventCount = loadedMetadata.droppedEventCount + dropped,
                truncatedPayloadCount = loadedMetadata.truncatedPayloadCount + newlyTruncated,
                capacityLimitReached = capacityLimitReached,
            ),
            events = events,
            retainedPayloadBytes = retainedPayloadBytes,
        )
        persistMetadata(state)
        return state
    }

    fun append(state: DiagnosticStoredState, event: DiagnosticEvent): DiagnosticStoredState =
        appendBatch(state, listOf(event))

    fun appendBatch(
        state: DiagnosticStoredState,
        events: List<DiagnosticEvent>,
    ): DiagnosticStoredState {
        if (events.isEmpty() || state.metadata.capacityLimitReached) return state
        ensureDirectories()
        events.forEachIndexed { index, event ->
            require(event.sequence > 0L) {
                "Diagnostic event sequence must be positive: ${event.sequence}"
            }
            require(event.sequence == state.metadata.nextSequence + index) {
                "Diagnostic event sequence is not contiguous: ${event.sequence}"
            }
        }

        val accepted = mutableListOf<NormalizedEvent>()
        var retainedPayloadBytes = state.retainedPayloadBytes
        var capacityLimitReached = retainedPayloadBytes >= maxRetainedPayloadBytes
        for (event in events) {
            if (capacityLimitReached) break
            val normalized = normalize(event)
            val nextPayloadBytes = retainedPayloadBytes + normalized.payloadBytes
            if (nextPayloadBytes > maxRetainedPayloadBytes) {
                capacityLimitReached = true
                break
            }
            val target = eventFile(normalized.event.sequence)
            require(!target.exists()) {
                "Diagnostic event sequence already exists: ${normalized.event.sequence}"
            }
            writeAtomically(target, json.encodeToString(normalized.event))
            accepted += normalized
            retainedPayloadBytes = nextPayloadBytes
            if (retainedPayloadBytes >= maxRetainedPayloadBytes) {
                capacityLimitReached = true
            }
        }

        val acceptedEvents = accepted.map(NormalizedEvent::event)
        val next = DiagnosticStoredState(
            metadata = state.metadata.copy(
                state = if (capacityLimitReached) {
                    DiagnosticCaptureState.PAUSED
                } else {
                    state.metadata.state
                },
                nextSequence = acceptedEvents.lastOrNull()?.sequence?.plus(1L)
                    ?: state.metadata.nextSequence,
                truncatedPayloadCount = state.metadata.truncatedPayloadCount +
                    accepted.sumOf(NormalizedEvent::truncatedCount),
                capacityLimitReached = capacityLimitReached,
            ),
            events = state.events + acceptedEvents,
            retainedPayloadBytes = retainedPayloadBytes,
        )
        return persistMetadata(next)
    }

    fun persistMetadata(state: DiagnosticStoredState): DiagnosticStoredState {
        ensureDirectories()
        writeAtomically(metadataFile, json.encodeToString(state.metadata))
        return state
    }

    fun clear(state: DiagnosticStoredState): DiagnosticStoredState {
        ensureDirectories()
        eventFiles().forEach(File::delete)
        cleanupTemporaryFiles(eventsDirectory)
        return persistMetadata(
            DiagnosticStoredState(
                metadata = state.metadata.copy(
                    droppedEventCount = 0L,
                    evictedEventCount = 0L,
                    truncatedPayloadCount = 0L,
                    capacityLimitReached = false,
                ),
            ),
        )
    }

    fun deleteAll(): DiagnosticStoredState {
        directory.deleteRecursively()
        return DiagnosticStoredState()
    }

    private fun normalize(event: DiagnosticEvent): NormalizedEvent {
        var truncatedCount = 0L
        var newlyTruncatedCount = 0L
        fun CapturedDiagnosticText.bounded(): CapturedDiagnosticText {
            val bytes = value.toByteArray(Charsets.UTF_8)
            val oversized = bytes.size > maxPayloadBytes
            val bounded = if (!oversized) {
                this
            } else {
                newlyTruncatedCount++
                copy(
                    value = decodeUtf8Prefix(bytes, maxPayloadBytes),
                    truncated = true,
                )
            }
            if (bounded.truncated) truncatedCount++
            return bounded
        }

        val payload = when (val current = event.payload) {
            is DiagnosticEventPayload.HttpRequest -> current.copy(
                url = current.url.bounded(),
                body = current.body.bounded(),
            )
            is DiagnosticEventPayload.HttpResponseBody -> current.copy(body = current.body.bounded())
            is DiagnosticEventPayload.WireLine -> current.copy(line = current.line.bounded())
            is DiagnosticEventPayload.ParsedStreamEvent -> current.copy(
                content = current.content?.bounded(),
            )
            is DiagnosticEventPayload.HttpStage,
            is DiagnosticEventPayload.RuntimeTransition -> current
        }
        val normalized = event.copy(payload = payload)
        return NormalizedEvent(
            event = normalized,
            truncatedCount = truncatedCount,
            newlyTruncatedCount = newlyTruncatedCount,
            payloadBytes = payloadByteSize(normalized),
        )
    }

    private fun payloadByteSize(event: DiagnosticEvent): Long {
        fun String.bytes(): Long = toByteArray(Charsets.UTF_8).size.toLong()
        fun Map<String, String>.bytes(): Long = entries.sumOf { (key, value) ->
            key.bytes() + value.bytes()
        }
        return when (val payload = event.payload) {
            is DiagnosticEventPayload.RuntimeTransition ->
                payload.oldState.bytes() + payload.commandType.bytes() + payload.newState.bytes() +
                    payload.effectId.orEmpty().bytes() + payload.effectTypes.sumOf(String::bytes)
            is DiagnosticEventPayload.HttpStage ->
                payload.stage.bytes() + payload.attributes.bytes()
            is DiagnosticEventPayload.HttpRequest ->
                payload.method.bytes() + payload.url.value.bytes() + payload.headers.bytes() +
                    payload.body.value.bytes()
            is DiagnosticEventPayload.HttpResponseBody -> payload.body.value.bytes()
            is DiagnosticEventPayload.WireLine -> payload.line.value.bytes()
            is DiagnosticEventPayload.ParsedStreamEvent ->
                payload.eventType.bytes() + payload.attributes.bytes() +
                    payload.content?.value.orEmpty().bytes()
        }
    }

    private fun readMetadata(): DiagnosticCaptureMetadata? {
        val backup = backupFile(metadataFile)
        return sequenceOf(metadataFile, backup)
            .filter(File::isFile)
            .mapNotNull { file ->
                runCatching {
                    json.decodeFromString<DiagnosticCaptureMetadata>(file.readText(Charsets.UTF_8))
                }.getOrNull()
            }
            .firstOrNull()
    }

    private fun eventFiles(): List<File> =
        eventsDirectory.listFiles { file ->
            file.isFile && file.name.endsWith(EVENT_EXTENSION)
        }.orEmpty().sortedBy(File::getName)

    private fun eventFile(sequence: Long): File =
        File(eventsDirectory, sequence.toString().padStart(SEQUENCE_DIGITS, '0') + EVENT_EXTENSION)

    private fun ensureDirectories() {
        require(directory.exists() || directory.mkdirs()) { "Unable to create $directory" }
        require(eventsDirectory.exists() || eventsDirectory.mkdirs()) {
            "Unable to create $eventsDirectory"
        }
    }

    private fun writeAtomically(target: File, content: String) {
        val temporary = temporaryFile(target)
        val backup = backupFile(target)
        temporary.delete()
        FileOutputStream(temporary).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        backup.delete()
        val movedOriginal = target.exists() && target.renameTo(backup)
        require(!target.exists() || movedOriginal) { "Unable to back up $target" }
        if (!temporary.renameTo(target)) {
            if (movedOriginal) backup.renameTo(target)
            throw IllegalStateException("Unable to replace $target")
        }
        backup.delete()
    }

    private fun cleanupTemporaryFiles(parent: File) {
        parent.listFiles { file ->
            file.isFile && file.name.endsWith(TEMPORARY_SUFFIX)
        }.orEmpty().forEach(File::delete)
    }

    private fun temporaryFile(target: File): File = File(target.parentFile, target.name + TEMPORARY_SUFFIX)
    private fun backupFile(target: File): File = File(target.parentFile, target.name + BACKUP_SUFFIX)

    private fun decodeUtf8Prefix(bytes: ByteArray, maxBytes: Int): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.IGNORE)
            .onUnmappableCharacter(CodingErrorAction.IGNORE)
            .decode(ByteBuffer.wrap(bytes, 0, maxBytes))
            .toString()

    private data class NormalizedEvent(
        val event: DiagnosticEvent,
        val truncatedCount: Long,
        val newlyTruncatedCount: Long,
        val payloadBytes: Long,
    )

    internal companion object {
        const val DEFAULT_MAX_PAYLOAD_BYTES = 2 * 1024 * 1024
        const val DEFAULT_MAX_RETAINED_PAYLOAD_BYTES = 64L * 1024L * 1024L
        private const val SCHEMA_VERSION = 1
        private const val EVENTS_DIRECTORY = "events"
        private const val METADATA_FILE = "capture-metadata.json"
        private const val EVENT_EXTENSION = ".json"
        private const val TEMPORARY_SUFFIX = ".tmp"
        private const val BACKUP_SUFFIX = ".bak"
        private const val SEQUENCE_DIGITS = 20
    }
}
