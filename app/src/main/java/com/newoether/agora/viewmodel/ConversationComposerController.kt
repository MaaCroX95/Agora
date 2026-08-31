package com.newoether.agora.viewmodel

import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.util.DebugLog
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

internal data class ConversationComposerSnapshot(
    val text: String = "",
    val attachments: List<SelectedAttachment> = emptyList(),
    val pdfPreviewProgress: Map<String, Pair<Int, Int>> = emptyMap(),
    val revision: Long = 0L,
    val textProjectionVersion: Long = 0L,
    val loaded: Boolean = false,
)

/** Owns durable attachment import sessions independently for every composer draft owner. */
internal class ConversationComposerController(
    private val scope: CoroutineScope,
    private val drafts: ComposerDraftController,
    private val processor: AttachmentImportProcessor,
    private val sandboxHomeDir: () -> File? = { null },
) {
    private class OwnerSession {
        val mutex = Mutex()
        val state = kotlinx.coroutines.flow.MutableStateFlow(ConversationComposerSnapshot())
        var durable = ConversationComposerSnapshot()
        var retainCount = 0
        var commandCount = 0
        val jobCount = AtomicInteger()
        val transientAttachmentIds = mutableSetOf<String>()
        val generations = mutableMapOf<String, Long>()
        val jobs = mutableMapOf<String, Job>()
    }

    private val sessionsMutex = Mutex()
    private val sessions = mutableMapOf<String, OwnerSession>()

    /** Admits one exact owner until the matching [release] call. */
    suspend fun load(ownerId: String): ConversationComposerSnapshot {
        val session = withContext(NonCancellable) {
            sessionsMutex.withLock {
                sessions.getOrPut(ownerId, ::OwnerSession).also {
                    it.retainCount += 1
                    it.commandCount += 1
                }
            }
        }
        var admitted = false
        return try {
            ensureLoaded(ownerId, session).also { admitted = true }
        } finally {
            withContext(NonCancellable) {
                if (!admitted) releaseRetain(ownerId, session)
                releaseCommand(ownerId, session)
            }
        }
    }

    suspend fun release(ownerId: String) = withContext(NonCancellable) {
        val session = sessionsMutex.withLock {
            val current = sessions[ownerId] ?: return@withLock null
            check(current.retainCount > 0) { "Composer owner is not retained" }
            current.retainCount -= 1
            current
        } ?: return@withContext
        evictIfInactive(ownerId, session)
    }

    suspend fun state(
        ownerId: String,
    ): kotlinx.coroutines.flow.StateFlow<ConversationComposerSnapshot> = sessionsMutex.withLock {
        checkNotNull(sessions[ownerId]) { "Composer owner is not admitted" }.state
    }

    private suspend fun <T> withSession(
        ownerId: String,
        block: suspend (OwnerSession) -> T,
    ): T {
        val session = sessionsMutex.withLock {
            checkNotNull(sessions[ownerId]) { "Composer owner is not admitted" }.also {
                it.commandCount += 1
            }
        }
        return try {
            block(session)
        } finally {
            withContext(NonCancellable) {
                releaseCommand(ownerId, session)
            }
        }
    }

    private suspend fun releaseRetain(ownerId: String, session: OwnerSession) {
        sessionsMutex.withLock {
            if (sessions[ownerId] !== session) return@withLock
            check(session.retainCount > 0) { "Composer owner is not retained" }
            session.retainCount -= 1
        }
    }

    private suspend fun releaseCommand(ownerId: String, session: OwnerSession) {
        sessionsMutex.withLock {
            if (sessions[ownerId] !== session) return@withLock
            check(session.commandCount > 0) { "Composer command is not pinned" }
            session.commandCount -= 1
        }
        evictIfInactive(ownerId, session)
    }

    private suspend fun evictIfInactive(ownerId: String, session: OwnerSession) {
        sessionsMutex.withLock {
            if (
                sessions[ownerId] !== session ||
                session.retainCount != 0 ||
                session.commandCount != 0 ||
                session.jobCount.get() != 0
            ) {
                return@withLock
            }
            sessions.remove(ownerId)
            drafts.evictCached(ownerId)
        }
    }

    private suspend fun ensureLoaded(
        ownerId: String,
        session: OwnerSession,
    ): ConversationComposerSnapshot = session.mutex.withLock {
        if (session.state.value.loaded) return@withLock session.state.value
        val loaded = drafts.load(ownerId).toSnapshot()
        session.durable = loaded
        session.state.value = loaded
        loaded.attachments
            .filter { it.shouldStartProcessingJob() }
            .forEach { attachment ->
                val generation = nextGeneration(session, attachment.localId)
                registerJobLocked(
                    session = session,
                    attachmentId = attachment.localId,
                    generation = generation,
                    job = processingJob(ownerId, session, attachment, generation),
                )
            }
        session.state.value
    }

    suspend fun importAttachment(ownerId: String, attachment: SelectedAttachment) =
        withSession(ownerId) { session ->
            ensureLoaded(ownerId, session)
            session.mutex.withLock {
                check(session.state.value.attachments.none { it.localId == attachment.localId }) {
                    "Attachment identity already belongs to this composer"
                }
                val processing = attachment.asProcessing()
                session.transientAttachmentIds += attachment.localId
                session.state.value = session.state.value.copy(
                    attachments = session.state.value.attachments + processing,
                )
                val generation = nextGeneration(session, attachment.localId)
                registerJobLocked(
                    session = session,
                    attachmentId = attachment.localId,
                    generation = generation,
                    job = stagingJob(ownerId, session, processing, generation),
                )
            }
        }

    suspend fun updateText(ownerId: String, text: String) = withSession(ownerId) { session ->
        ensureLoaded(ownerId, session)
        session.mutex.withLock {
            session.state.value = session.state.value.copy(text = text)
        }
    }

    suspend fun persistText(ownerId: String, text: String): Boolean =
        withSession(ownerId) { session ->
            ensureLoaded(ownerId, session)
            withContext(NonCancellable) {
                session.mutex.withLock {
                    val current = session.state.value
                    if (current.text != text) return@withLock false
                    val result = drafts.persist(
                        conversationId = ownerId,
                        expectedRevision = session.durable.revision,
                        text = text,
                        attachments = session.durable.attachments,
                    )
                    if (!result.succeeded) return@withLock false
                    if (!result.matchesRequested) {
                        reloadAndMergeLocked(ownerId, session)
                        return@withLock false
                    }
                    session.durable = session.durable.copy(text = text, revision = result.revision)
                    session.state.value = session.state.value.copy(revision = result.revision)
                    true
                }
            }
        }

    suspend fun configurePdf(
        ownerId: String,
        attachmentId: String,
        selectedPages: Set<Int>,
    ): Boolean = configureAttachment(ownerId, attachmentId, expectedType = "pdf") { attachment ->
        attachment.copy(selectedPages = selectedPages)
    }

    suspend fun configureVideo(
        ownerId: String,
        attachmentId: String,
        frameCount: Int,
        intervalMs: Long,
    ): Boolean = configureAttachment(ownerId, attachmentId, expectedType = "video") { attachment ->
        attachment.copy(frameCount = frameCount, sliceIntervalMs = intervalMs)
    }

    suspend fun clearAccepted(ownerId: String): DraftClearResult =
        withSession(ownerId) { session ->
            ensureLoaded(ownerId, session)
            withContext(NonCancellable) {
                session.mutex.withLock {
                    val result = drafts.clearAccepted(ownerId)
                    if (!result.succeeded) return@withLock result
                    session.jobs.values.forEach(Job::cancel)
                    session.jobs.clear()
                    session.generations.clear()
                    session.transientAttachmentIds.clear()
                    val nextTextProjectionVersion = session.state.value.textProjectionVersion + 1L
                    session.durable = ConversationComposerSnapshot(
                        revision = result.revision,
                        textProjectionVersion = nextTextProjectionVersion,
                        loaded = true,
                    )
                    session.state.value = session.durable
                    result
                }
            }
        }

    suspend fun retry(ownerId: String, attachmentId: String): Boolean =
        withSession(ownerId) { session ->
            ensureLoaded(ownerId, session)
            session.mutex.withLock {
                val failed = session.state.value.attachments
                    .firstOrNull { it.localId == attachmentId }
                    ?.takeIf { it.importState == AttachmentImportState.FAILED }
                    ?: return@withLock false
                val processing = failed.asProcessing()
                val generation = nextGeneration(session, attachmentId)
                session.transientAttachmentIds += attachmentId
                session.state.value = session.state.value.replaceAttachment(processing)
                registerJobLocked(
                    session = session,
                    attachmentId = attachmentId,
                    generation = generation,
                    job = stagingJob(ownerId, session, processing, generation),
                )
                true
            }
        }

    suspend fun remove(ownerId: String, attachmentId: String): Boolean =
        withSession(ownerId) { session ->
            ensureLoaded(ownerId, session)
            withContext(NonCancellable) {
                session.mutex.withLock {
                    val removed = session.state.value.attachments
                        .firstOrNull { it.localId == attachmentId }
                        ?: return@withLock false
                    while (true) {
                        val current = session.state.value
                        if (current.attachments.none { it.localId == attachmentId }) {
                            completeRemovalLocked(session, attachmentId)
                            return@withLock true
                        }
                        val wasDurable = session.durable.attachments.any {
                            it.localId == attachmentId
                        }
                        val requested = session.durable.copy(
                            text = current.text,
                            attachments = session.durable.attachments.filterNot {
                                it.localId == attachmentId
                            },
                        )
                        val result = drafts.persist(
                            conversationId = ownerId,
                            expectedRevision = session.durable.revision,
                            text = requested.text,
                            attachments = requested.attachments,
                        )
                        if (!result.succeeded) return@withLock false
                        if (!result.matchesRequested) {
                            reloadAndMergeLocked(ownerId, session)
                            continue
                        }
                        session.durable = requested.copy(revision = result.revision)
                        session.state.value = current.copy(
                            attachments = current.attachments.filterNot {
                                it.localId == attachmentId
                            },
                            pdfPreviewProgress = current.pdfPreviewProgress - attachmentId,
                            revision = result.revision,
                        )
                        completeRemovalLocked(session, attachmentId)
                        if (!wasDurable) drafts.reclaimAttachments(listOf(removed))
                        return@withLock true
                    }
                    false
                }
            }
        }

    suspend fun awaitProcessing(ownerId: String, attachmentIds: Set<String>? = null) =
        withSession(ownerId) { session ->
            ensureLoaded(ownerId, session)
            while (true) {
                val (jobs, processingRemains) = session.mutex.withLock {
                    val selected: (String) -> Boolean = { id ->
                        attachmentIds == null || id in attachmentIds
                    }
                    session.jobs.filterKeys(selected).values.toList() to
                        session.state.value.attachments.any { attachment ->
                            selected(attachment.localId) && attachment.shouldStartProcessingJob()
                        }
                }
                if (jobs.isEmpty() && !processingRemains) return@withSession
                if (jobs.isEmpty()) yield() else jobs.joinAll()
            }
        }

    private suspend fun configureAttachment(
        ownerId: String,
        attachmentId: String,
        expectedType: String,
        configure: (SelectedAttachment) -> SelectedAttachment,
    ): Boolean = withSession(ownerId) { session ->
        ensureLoaded(ownerId, session)
        withContext(NonCancellable) {
            var configuredBeforeStagingCompleted = false
            val pending = session.mutex.withLock {
                val current = session.state.value.attachments
                    .firstOrNull { it.localId == attachmentId }
                    ?.takeIf {
                        it.type == expectedType &&
                            it.importState == AttachmentImportState.PROCESSING
                    }
                    ?: return@withLock null
                val configured = configure(current)
                session.state.value = session.state.value
                    .replaceAttachment(configured)
                    .copy(
                        pdfPreviewProgress = session.state.value.pdfPreviewProgress - attachmentId,
                    )
                if (attachmentId in session.transientAttachmentIds) {
                    configuredBeforeStagingCompleted = true
                    null
                } else {
                    val generation = nextGeneration(session, attachmentId)
                    session.jobs[attachmentId]?.cancel()
                    configured to generation
                }
            }
            if (configuredBeforeStagingCompleted) return@withContext true
            val (configured, generation) = pending ?: return@withContext false
            val durable = persistReplacement(
                ownerId = ownerId,
                session = session,
                attachmentId = attachmentId,
                generation = generation,
                replacement = configured,
            ) ?: return@withContext false
            session.mutex.withLock {
                registerJobLocked(
                    session = session,
                    attachmentId = attachmentId,
                    generation = generation,
                    job = processingJob(ownerId, session, durable, generation),
                )
            }
            true
        }
    }

    private suspend fun currentProcessingAttachment(
        session: OwnerSession,
        attachmentId: String,
        generation: Long,
    ): SelectedAttachment? = session.mutex.withLock {
        session.state.value.attachments.firstOrNull { attachment ->
            attachment.localId == attachmentId &&
                session.generations[attachmentId] == generation &&
                attachment.shouldStartProcessingJob()
        }
    }

    private fun stagingJob(
        ownerId: String,
        session: OwnerSession,
        source: SelectedAttachment,
        generation: Long,
    ): Job = scope.launch(start = CoroutineStart.LAZY) {
        try {
            when (val staged = processor.stage(source)) {
                is AttachmentImportProcessor.StageResult.Success -> {
                    val durable = persistReplacement(
                        ownerId = ownerId,
                        session = session,
                        attachmentId = source.localId,
                        generation = generation,
                        replacement = staged.attachment,
                        obsoleteTransient = source,
                        createdPaths = staged.createdPaths,
                    )
                    if (durable != null) {
                        currentProcessingAttachment(
                            session = session,
                            attachmentId = source.localId,
                            generation = generation,
                        )?.let { configured ->
                            runProcessing(ownerId, session, configured, generation)
                        }
                    }
                }
                AttachmentImportProcessor.StageResult.TooLarge ->
                    persistFailure(ownerId, session, source.localId, generation)
                is AttachmentImportProcessor.StageResult.Failure -> {
                    val replacement = staged.attachment
                    if (replacement == null) {
                        persistFailure(ownerId, session, source.localId, generation)
                    } else {
                        persistReplacement(
                            ownerId = ownerId,
                            session = session,
                            attachmentId = source.localId,
                            generation = generation,
                            replacement = replacement,
                            obsoleteTransient = source,
                            createdPaths = staged.createdPaths,
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            DebugLog.w("ChatViewModel", "Attachment staging failed", failure)
            persistFailure(ownerId, session, source.localId, generation)
        } finally {
            finishJob(ownerId, session, source.localId, currentCoroutineContext()[Job]!!)
        }
    }

    private fun processingJob(
        ownerId: String,
        session: OwnerSession,
        attachment: SelectedAttachment,
        generation: Long,
    ): Job = scope.launch(start = CoroutineStart.LAZY) {
        try {
            runProcessing(ownerId, session, attachment, generation)
        } finally {
            finishJob(
                ownerId,
                session,
                attachment.localId,
                currentCoroutineContext()[Job]!!,
            )
        }
    }

    private suspend fun runProcessing(
        ownerId: String,
        session: OwnerSession,
        attachment: SelectedAttachment,
        generation: Long,
    ) {
        if (attachment.shouldPreparePdfPreview()) {
            runPdfPreview(ownerId, session, attachment, generation)
            return
        }
        when (val result = processor.process(attachment, sandboxHomeDir())) {
            is AttachmentImportProcessor.ProcessResult.Ready ->
                persistReplacement(
                    ownerId = ownerId,
                    session = session,
                    attachmentId = attachment.localId,
                    generation = generation,
                    replacement = result.attachment,
                    createdPaths = result.createdPaths,
                )
            is AttachmentImportProcessor.ProcessResult.Failure ->
                persistFailure(ownerId, session, attachment.localId, generation)
        }
    }

    private suspend fun runPdfPreview(
        ownerId: String,
        session: OwnerSession,
        attachment: SelectedAttachment,
        generation: Long,
    ) {
        updatePdfPreviewProgress(
            session = session,
            attachmentId = attachment.localId,
            generation = generation,
            current = 0,
            total = attachment.pageCount ?: 0,
        )
        when (
            val result = processor.preparePdfPreview(attachment) { current, total ->
                updatePdfPreviewProgress(
                    session = session,
                    attachmentId = attachment.localId,
                    generation = generation,
                    current = current,
                    total = total,
                )
            }
        ) {
            is AttachmentImportProcessor.ProcessResult.Ready ->
                persistReplacement(
                    ownerId = ownerId,
                    session = session,
                    attachmentId = attachment.localId,
                    generation = generation,
                    replacement = result.attachment,
                    createdPaths = result.createdPaths,
                )
            is AttachmentImportProcessor.ProcessResult.Failure ->
                persistFailure(ownerId, session, attachment.localId, generation)
        }
    }

    private suspend fun updatePdfPreviewProgress(
        session: OwnerSession,
        attachmentId: String,
        generation: Long,
        current: Int,
        total: Int,
    ) {
        session.mutex.withLock {
            if (session.generations[attachmentId] != generation) return@withLock
            val attachment = session.state.value.attachments
                .firstOrNull { it.localId == attachmentId }
                ?.takeIf { it.shouldPreparePdfPreview() }
                ?: return@withLock
            session.state.value = session.state.value.copy(
                pdfPreviewProgress = session.state.value.pdfPreviewProgress +
                    (attachment.localId to (current to total)),
            )
        }
    }

    private suspend fun persistFailure(
        ownerId: String,
        session: OwnerSession,
        attachmentId: String,
        generation: Long,
    ) {
        val current = session.mutex.withLock {
            session.state.value.attachments.firstOrNull { it.localId == attachmentId }
        } ?: return
        persistReplacement(
            ownerId = ownerId,
            session = session,
            attachmentId = attachmentId,
            generation = generation,
            replacement = current.copy(importState = AttachmentImportState.FAILED),
        )
    }

    private suspend fun persistReplacement(
        ownerId: String,
        session: OwnerSession,
        attachmentId: String,
        generation: Long,
        replacement: SelectedAttachment,
        obsoleteTransient: SelectedAttachment? = null,
        createdPaths: List<String> = emptyList(),
    ): SelectedAttachment? = withContext(NonCancellable) {
        var committed = false
        try {
            session.mutex.withLock {
                while (true) {
                    if (session.generations[attachmentId] != generation) return@withLock null
                    val current = session.state.value
                    val currentAttachment = current.attachments
                        .firstOrNull { it.localId == attachmentId }
                        ?: return@withLock null
                    val effectiveReplacement = replacement.withProcessingConfiguration(
                        currentAttachment,
                    )
                    val wasDurable = session.durable.attachments.any {
                        it.localId == attachmentId
                    }
                    val requestedAttachments = durableProjectionLocked(
                        session = session,
                        attachmentId = attachmentId,
                        replacement = effectiveReplacement,
                    )
                    val result = drafts.persist(
                        conversationId = ownerId,
                        expectedRevision = session.durable.revision,
                        text = current.text,
                        attachments = requestedAttachments,
                    )
                    if (!result.succeeded) {
                        session.state.value = current.replaceAttachment(
                            currentAttachment.copy(importState = AttachmentImportState.FAILED),
                        )
                        return@withLock null
                    }
                    if (!result.matchesRequested) {
                        reloadAndMergeLocked(ownerId, session)
                        continue
                    }
                    committed = true
                    session.durable = ConversationComposerSnapshot(
                        text = current.text,
                        attachments = requestedAttachments,
                        revision = result.revision,
                        textProjectionVersion = current.textProjectionVersion,
                        loaded = true,
                    )
                    session.transientAttachmentIds -= attachmentId
                    session.state.value = current.replaceAttachment(effectiveReplacement).copy(
                        pdfPreviewProgress = current.pdfPreviewProgress - attachmentId,
                        revision = result.revision,
                    )
                    if (!wasDurable && obsoleteTransient != null) {
                        drafts.reclaimAttachments(listOf(obsoleteTransient))
                    }
                    return@withLock effectiveReplacement
                }
                null
            }
        } finally {
            if (!committed) deleteCreatedPaths(createdPaths)
        }
    }

    private fun durableProjectionLocked(
        session: OwnerSession,
        attachmentId: String,
        replacement: SelectedAttachment,
    ): List<SelectedAttachment> {
        val remaining = session.durable.attachments.associateByTo(linkedMapOf()) {
            it.localId
        }
        return buildList {
            session.state.value.attachments.forEach { attachment ->
                when {
                    attachment.localId == attachmentId -> {
                        remaining.remove(attachmentId)
                        add(replacement)
                    }
                    else -> remaining.remove(attachment.localId)?.let(::add)
                }
            }
            addAll(remaining.values)
        }
    }

    private suspend fun reloadAndMergeLocked(ownerId: String, session: OwnerSession) {
        val current = session.state.value
        val loadedDraft = drafts.load(ownerId).toSnapshot()
        val textProjectionVersion = current.textProjectionVersion +
            if (loadedDraft.text != current.text) 1L else 0L
        val loaded = loadedDraft.copy(textProjectionVersion = textProjectionVersion)
        val remaining = loaded.attachments.associateByTo(linkedMapOf()) { it.localId }
        val merged = buildList {
            session.state.value.attachments.forEach { attachment ->
                if (attachment.localId in session.transientAttachmentIds) {
                    add(attachment)
                } else {
                    remaining.remove(attachment.localId)?.let(::add)
                }
            }
            addAll(remaining.values)
        }
        session.durable = loaded
        session.state.value = loaded.copy(attachments = merged)
    }

    private suspend fun finishJob(
        ownerId: String,
        session: OwnerSession,
        attachmentId: String,
        job: Job,
    ) = withContext(NonCancellable) {
        session.mutex.withLock {
            if (session.jobs[attachmentId] === job) {
                session.jobs.remove(attachmentId)
            }
        }
        check(session.jobCount.decrementAndGet() >= 0) { "Composer job count underflow" }
        evictIfInactive(ownerId, session)
    }

    private fun completeRemovalLocked(session: OwnerSession, attachmentId: String) {
        session.transientAttachmentIds -= attachmentId
        nextGeneration(session, attachmentId)
        session.jobs[attachmentId]?.cancel()
    }

    private fun registerJobLocked(
        session: OwnerSession,
        attachmentId: String,
        generation: Long,
        job: Job,
    ) {
        if (session.generations[attachmentId] != generation) {
            job.cancel()
            return
        }
        val previous = session.jobs.put(attachmentId, job)
        session.jobCount.incrementAndGet()
        previous?.cancel()
        if (!job.start()) {
            if (session.jobs[attachmentId] === job) session.jobs.remove(attachmentId)
            check(session.jobCount.decrementAndGet() >= 0) { "Composer job count underflow" }
        }
    }

    private fun nextGeneration(session: OwnerSession, attachmentId: String): Long {
        val next = (session.generations[attachmentId] ?: 0L) + 1L
        session.generations[attachmentId] = next
        return next
    }

    private fun LoadedComposerDraft.toSnapshot() = ConversationComposerSnapshot(
        text = text,
        attachments = attachments,
        revision = revision,
        loaded = true,
    )

    private fun ConversationComposerSnapshot.replaceAttachment(
        replacement: SelectedAttachment,
    ) = copy(
        attachments = attachments.map { current ->
            if (current.localId == replacement.localId) replacement else current
        },
    )

    private fun SelectedAttachment.asProcessing() = copy(
        processedFrames = null,
        preRenderedPaths = null,
        preparedText = null,
        importState = AttachmentImportState.PROCESSING,
        unavailable = false,
    )

    private fun SelectedAttachment.isConfiguredForProcessing(): Boolean = when (type) {
        "pdf" -> selectedPages != null
        "video" -> frameCount != null && sliceIntervalMs != null
        else -> true
    }

    private fun SelectedAttachment.shouldPreparePdfPreview(): Boolean =
        type == "pdf" &&
            importState == AttachmentImportState.PROCESSING &&
            selectedPages == null &&
            preRenderedPaths.isNullOrEmpty()

    private fun SelectedAttachment.shouldStartProcessingJob(): Boolean =
        importState == AttachmentImportState.PROCESSING &&
            (isConfiguredForProcessing() || shouldPreparePdfPreview())

    private fun SelectedAttachment.withProcessingConfiguration(
        current: SelectedAttachment,
    ): SelectedAttachment {
        if (importState != AttachmentImportState.PROCESSING) return this
        return when (type) {
            "pdf" -> copy(selectedPages = current.selectedPages ?: selectedPages)
            "video" -> copy(
                frameCount = current.frameCount ?: frameCount,
                sliceIntervalMs = current.sliceIntervalMs ?: sliceIntervalMs,
            )
            else -> this
        }
    }

    private fun deleteCreatedPaths(paths: List<String>) {
        paths.distinct().forEach { path ->
            runCatching {
                val file = File(path)
                file.delete()
                file.parentFile?.takeIf { it.isDirectory && it.list().isNullOrEmpty() }?.delete()
            }
        }
    }
}
