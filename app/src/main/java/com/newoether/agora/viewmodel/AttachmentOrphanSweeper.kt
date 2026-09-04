package com.newoether.agora.viewmodel

import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.SelectedAttachment
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import java.io.File

/** Reclaims old private attachment files only after scanning every durable reference source. */
internal class AttachmentOrphanSweeper(
    private val conversations: ConversationRepository,
    private val filesDirectory: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun sweep() {
        val referenced = collectReferences()
        val cutoffNow = now()
        deleteOldUnreferencedRootAttachments(referenced, cutoffNow)
        deleteOldUnreferencedFiles(File(filesDirectory, "images"), "camera_", referenced, cutoffNow)
        listOf(
            File(filesDirectory, "run-inputs"),
            File(filesDirectory, "fork-attachments"),
        ).forEach { directory ->
            deleteOldUnreferencedFiles(directory, requiredPrefix = null, referenced, cutoffNow)
        }
    }

    suspend fun deleteExact(path: String) {
        val normalized = normalizePath(path)
        val root = filesDirectory.canonicalFile
        val file = File(normalized).canonicalFile
        require(file != root && file.path.startsWith(root.path + File.separator)) {
            "Attachment debt is outside app-private storage"
        }
        if (normalized in collectReferences()) return
        check(!file.exists() || file.delete()) { "Unable to delete attachment file $normalized" }
        val sandboxRoot = File(root, "sandbox-home").canonicalFile
        val parent = file.parentFile?.canonicalFile
        if (parent != null && parent != sandboxRoot && parent.path.startsWith(sandboxRoot.path)) {
            runCatching { parent.takeIf { it.listFiles().isNullOrEmpty() }?.delete() }
        }
    }

    private suspend fun collectReferences(): Set<String> = HashSet<String>().also { referenced ->
        collectMessageReferences(referenced)
        collectDraftReferences(referenced)
    }

    private suspend fun collectMessageReferences(referenced: MutableSet<String>) {
        var afterMessageId: String? = null
        while (true) {
            val page = conversations.getMessageAttachmentReferencesPage(
                afterId = afterMessageId,
                limit = DATABASE_SCAN_PAGE_SIZE,
            )
            page.forEach { message ->
                message.images.forEach { referenced.add(normalizePath(it)) }
                message.attachmentMeta?.let { json ->
                    runCatching { Json.decodeFromString<AttachmentMeta>(json) }.getOrNull()
                        ?.items?.forEach { item ->
                            item.originalUri?.takeIf { it.startsWith("file://") }
                                ?.let { referenced.add(normalizePath(it)) }
                        }
                }
            }
            afterMessageId = page.lastOrNull()?.id
            if (page.size < DATABASE_SCAN_PAGE_SIZE) break
            yield()
        }
    }

    private suspend fun collectDraftReferences(referenced: MutableSet<String>) {
        var afterConversationId: String? = null
        while (true) {
            val page = conversations.getConversationDraftAttachmentReferencesPage(
                afterId = afterConversationId,
                limit = DATABASE_SCAN_PAGE_SIZE,
            )
            page.forEach { conversation ->
                runCatching {
                    Json.decodeFromString<List<SelectedAttachment>>(conversation.draftAttachments)
                }.getOrNull()?.forEach { attachment ->
                    attachment.localPath?.let { referenced.add(normalizePath(it)) }
                    attachment.processedFrames?.forEach { referenced.add(normalizePath(it)) }
                    attachment.preRenderedPaths?.forEach { referenced.add(normalizePath(it)) }
                }
            }
            afterConversationId = page.lastOrNull()?.id
            if (page.size < DATABASE_SCAN_PAGE_SIZE) break
            yield()
        }

        conversations.getNewChatDraftAttachmentReference()?.let { newChat ->
            runCatching {
                Json.decodeFromString<List<SelectedAttachment>>(newChat.draftAttachments)
            }.getOrNull()?.forEach { attachment ->
                attachment.localPath?.let { referenced.add(normalizePath(it)) }
                attachment.processedFrames?.forEach { referenced.add(normalizePath(it)) }
                attachment.preRenderedPaths?.forEach { referenced.add(normalizePath(it)) }
            }
        }
    }

    private fun deleteOldUnreferencedRootAttachments(
        referenced: Set<String>,
        cutoffNow: Long,
    ) {
        val prefixes = arrayOf("att_", "vid_", "img_", "pdf_")
        filesDirectory.listFiles { file ->
            file.isFile && prefixes.any { prefix -> file.name.startsWith(prefix) }
        }?.forEach { file ->
            deleteIfOldAndUnreferenced(file, referenced, cutoffNow)
        }
    }

    private fun deleteOldUnreferencedFiles(
        directory: File,
        requiredPrefix: String?,
        referenced: Set<String>,
        cutoffNow: Long,
    ) {
        directory.listFiles { file ->
            file.isFile && (requiredPrefix == null || file.name.startsWith(requiredPrefix))
        }?.forEach { file ->
            deleteIfOldAndUnreferenced(file, referenced, cutoffNow)
        }
    }

    private fun normalizePath(path: String): String {
        val raw = path.removePrefix("file://")
        return runCatching { File(raw).canonicalPath }.getOrElse { File(raw).absolutePath }
    }

    private fun deleteIfOldAndUnreferenced(
        file: File,
        referenced: Set<String>,
        cutoffNow: Long,
    ) {
        if (
            normalizePath(file.absolutePath) !in referenced &&
            cutoffNow - file.lastModified() > MINIMUM_FILE_AGE_MS
        ) {
            check(!file.exists() || file.delete()) { "Unable to delete attachment file ${file.path}" }
        }
    }

    private companion object {
        const val DATABASE_SCAN_PAGE_SIZE = 64
        const val MINIMUM_FILE_AGE_MS = 60 * 60 * 1000L
    }
}
