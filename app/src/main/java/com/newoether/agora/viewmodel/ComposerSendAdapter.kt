package com.newoether.agora.viewmodel

import com.newoether.agora.model.SelectedAttachment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal typealias ComposerSend = suspend (
    text: String,
    images: List<String>,
    attachments: List<SelectedAttachment>,
    onAccepted: suspend (SendAcceptance) -> Unit,
) -> SendAcceptance?

/** Adapts an authoritative Send acceptance to composer draft ownership and UI acknowledgement. */
internal class ComposerSendAdapter(
    private val send: ComposerSend,
    private val composers: ConversationComposerController,
    private val drafts: ComposerDraftController,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun sendMessage(
        text: String,
        images: List<String> = emptyList(),
        attachments: List<SelectedAttachment> = emptyList(),
        onAccepted: suspend () -> Unit = {},
        draftOwnerId: String,
    ): SendAcceptance? {
        composers.load(draftOwnerId)
        try {
            val submittedRuntimeIds = attachments.asSequence()
                .filterNot { it.storage.reclaimWhenAbandoned }
                .mapTo(hashSetOf(), SelectedAttachment::localId)
            return send(text, images, attachments) { acceptance ->
                // Acceptance transfers ownership before the composer clears. Direct inputs are
                // Room-owned; queued guidance remains memory-owned until its later drain boundary.
                val clearResult = withContext(NonCancellable) {
                    composers.clearAccepted(
                        ownerId = draftOwnerId,
                        reclaimAttachments = false,
                    )
                }
                // The durable draft may still contain the pre-submission pending copy. Stable localId
                // prevents that stale snapshot from deleting a submitted runtime file.
                val attachmentsToReclaim = clearResult.attachments.filterNot { attachment ->
                    attachment.localId in submittedRuntimeIds
                }
                withContext(mainDispatcher + NonCancellable) {
                    onAccepted()
                }
                if (attachmentsToReclaim.isNotEmpty() && acceptance.hasDurableAttachmentOwner()) {
                    // UI no longer waits on deletion. Repository cleanup rechecks durable references.
                    scope.launch(ioDispatcher) {
                        drafts.reclaimAttachments(attachmentsToReclaim)
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                composers.release(draftOwnerId)
            }
        }
    }
}
