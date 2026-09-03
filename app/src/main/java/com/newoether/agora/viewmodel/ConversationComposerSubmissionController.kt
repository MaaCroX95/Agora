package com.newoether.agora.viewmodel

import com.newoether.agora.model.SelectedAttachment
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class ComposerSubmissionPhase {
    IDLE,
    WAITING,
    SUBMITTING,
}

internal data class ConversationComposerSubmissionSnapshot(
    val phase: ComposerSubmissionPhase = ComposerSubmissionPhase.IDLE,
    val requestId: Long? = null,
    val frozenText: String = "",
    val frozenAttachmentIds: List<String> = emptyList(),
    val acceptedVersion: Long = 0L,
) {
    val isFrozen: Boolean
        get() = phase != ComposerSubmissionPhase.IDLE
    val isWaiting: Boolean
        get() = phase == ComposerSubmissionPhase.WAITING
    val isSubmitting: Boolean
        get() = phase == ComposerSubmissionPhase.SUBMITTING
}

internal typealias ComposerSubmissionTargetCapture =
    (ownerId: String) -> ForegroundSendTarget?
internal typealias ComposerSubmissionPrepare =
    suspend (ForegroundSendTarget, String) -> ForegroundSendAdmission?
internal typealias ComposerSubmissionSend = suspend (
    admission: ForegroundSendAdmission,
    text: String,
    attachments: List<SelectedAttachment>,
    onAccepted: suspend (SendAcceptance) -> Unit,
) -> SendAcceptance?

/** Owns every pre-acceptance Composer submission independently of the visible composition. */
internal class ConversationComposerSubmissionController(
    private val scope: CoroutineScope,
    private val composers: ConversationComposerController,
    private val drafts: ComposerDraftController,
    private val captureTarget: ComposerSubmissionTargetCapture,
    private val prepare: ComposerSubmissionPrepare,
    private val send: ComposerSubmissionSend,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private class OwnerSubmission {
        val state = MutableStateFlow(ConversationComposerSubmissionSnapshot())
        var request: FrozenRequest? = null
        var job: Job? = null
        var cancelWaitingRequested = false
    }

    private class FrozenRequest(
        val id: Long,
        val target: ForegroundSendTarget,
        val text: String,
        val attachmentIds: List<String>,
    ) {
        val ownerId: String get() = target.ownerId
        val acceptanceStarted = AtomicBoolean(false)
        val acceptedAndCleared = AtomicBoolean(false)
    }

    private val ownersLock = Any()
    private val owners = mutableMapOf<String, OwnerSubmission>()
    private val nextRequestId = AtomicLong(0L)

    fun state(ownerId: String): StateFlow<ConversationComposerSubmissionSnapshot> =
        owner(ownerId).state

    fun isFrozen(ownerId: String): Boolean {
        val owner = synchronized(ownersLock) { owners[ownerId] } ?: return false
        return owner.state.value.isFrozen
    }

    fun submit(
        ownerId: String,
        text: String,
        attachmentIds: List<String>,
    ): Boolean {
        val target = captureTarget(ownerId) ?: return false
        val owner = owner(ownerId)
        val request = synchronized(owner) {
            if (owner.request != null) return false
            FrozenRequest(
                id = nextRequestId.incrementAndGet(),
                target = target,
                text = text,
                attachmentIds = attachmentIds.toList(),
            ).also { frozen ->
                owner.request = frozen
                owner.cancelWaitingRequested = false
                owner.state.value = owner.state.value.copy(
                    phase = ComposerSubmissionPhase.WAITING,
                    requestId = frozen.id,
                    frozenText = frozen.text,
                    frozenAttachmentIds = frozen.attachmentIds,
                )
            }
        }
        val job = scope.launch { runSubmission(owner, request) }
        job.invokeOnCompletion { completeIdle(owner, request) }
        synchronized(owner) {
            if (owner.request === request) {
                owner.job = job
                if (owner.cancelWaitingRequested) job.cancel()
            } else {
                job.cancel()
            }
        }
        return true
    }

    fun cancelWaiting(ownerId: String): Boolean {
        val owner = owner(ownerId)
        val job = synchronized(owner) {
            if (owner.state.value.phase != ComposerSubmissionPhase.WAITING) return false
            owner.cancelWaitingRequested = true
            owner.job
        }
        job?.cancel()
        return true
    }

    private suspend fun runSubmission(owner: OwnerSubmission, request: FrozenRequest) {
        var retained = false
        try {
            composers.load(request.ownerId)
            retained = true
            composers.freezeSubmission(
                request.ownerId,
                request.id,
                request.text,
                request.attachmentIds,
            ) ?: return
            val admission = prepare(request.target, request.text) ?: return
            composers.awaitProcessing(request.ownerId, request.attachmentIds.toSet())
            val readyAttachments = composers.state(request.ownerId).value.attachments
                .associateBy(SelectedAttachment::localId)
                .let { currentById ->
                    request.attachmentIds.mapNotNull { id ->
                        currentById[id]?.takeIf(
                            SelectedAttachment::hasCanonicalReadyArtifact,
                        )
                    }
                }
                .map { attachment ->
                    attachment.copy(storage = attachment.storage.transferForSend())
                }
            if (request.text.isBlank() && readyAttachments.isEmpty()) return
            if (!startSubmitting(owner, request)) return
            val runtimeAttachmentIds = readyAttachments.asSequence()
                .filterNot { it.storage.reclaimWhenAbandoned }
                .mapTo(hashSetOf(), SelectedAttachment::localId)
            send(admission, request.text, readyAttachments) { acceptance ->
                if (!request.acceptanceStarted.compareAndSet(false, true)) return@send
                val clearResult = withContext(NonCancellable) {
                    composers.clearAccepted(
                        ownerId = request.ownerId,
                        reclaimAttachments = false,
                        submissionId = request.id,
                    )
                }
                check(clearResult.succeeded) { "Accepted Composer draft did not clear" }
                request.acceptedAndCleared.set(true)
                val reclaimable = clearResult.attachments.filterNot { attachment ->
                    attachment.localId in runtimeAttachmentIds
                }
                if (reclaimable.isNotEmpty() && acceptance.hasDurableAttachmentOwner()) {
                    scope.launch(ioDispatcher) { drafts.reclaimAttachments(reclaimable) }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            com.newoether.agora.util.DebugLog.w(
                "ChatViewModel",
                "Composer submission failed for ${request.ownerId}",
                failure,
            )
        } finally {
            try {
                if (retained) {
                    withContext(NonCancellable) {
                        try {
                            composers.releaseSubmission(request.ownerId, request.id)
                        } finally {
                            composers.release(request.ownerId)
                        }
                    }
                }
            } finally {
                if (request.acceptedAndCleared.get()) {
                    completeAccepted(owner, request)
                } else {
                    completeIdle(owner, request)
                }
            }
        }
    }

    private fun startSubmitting(owner: OwnerSubmission, request: FrozenRequest): Boolean =
        synchronized(owner) {
            if (
                owner.request !== request ||
                owner.state.value.phase != ComposerSubmissionPhase.WAITING
            ) {
                return@synchronized false
            }
            owner.state.value = owner.state.value.copy(
                phase = ComposerSubmissionPhase.SUBMITTING,
            )
            true
        }

    private fun completeAccepted(owner: OwnerSubmission, request: FrozenRequest) {
        synchronized(owner) {
            if (owner.request !== request) return
            val acceptedVersion = owner.state.value.acceptedVersion + 1L
            owner.request = null
            owner.job = null
            owner.cancelWaitingRequested = false
            owner.state.value = owner.state.value.toIdle().copy(
                acceptedVersion = acceptedVersion,
            )
        }
    }

    private fun completeIdle(owner: OwnerSubmission, request: FrozenRequest) {
        synchronized(owner) {
            if (owner.request !== request) return
            owner.request = null
            owner.job = null
            owner.cancelWaitingRequested = false
            owner.state.value = owner.state.value.toIdle()
        }
    }

    private fun owner(ownerId: String): OwnerSubmission = synchronized(ownersLock) {
        owners.getOrPut(ownerId, ::OwnerSubmission)
    }

    private fun ConversationComposerSubmissionSnapshot.toIdle() = copy(
        phase = ComposerSubmissionPhase.IDLE,
        requestId = null,
        frozenText = "",
        frozenAttachmentIds = emptyList(),
    )
}
