package com.newoether.agora.webui

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.tool.ToolImageStore
import com.newoether.agora.ui.chat.message.mergeAdjacentSegments
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Resolves only one conversation-owned persisted tool image; never accepts a browser file path. */
internal class WebUiToolImages(
    private val directory: File,
    private val loadMessage: suspend (String, String) -> ChatMessage?,
) {
    suspend fun open(conversationId: String, messageId: String, detailIndex: Int, imageIndex: Int): WebUiToolImageFile? {
        if (detailIndex < 0 || imageIndex < 0) return null
        val message = loadMessage(conversationId, messageId)?.takeIf { it.id == messageId } ?: return null
        val segment = mergeAdjacentSegments(message.segments.orEmpty())
            .filter { it.type != "answer" && it.type != "error" }
            .getOrNull(detailIndex)?.takeIf { it.type == "tool" } ?: return null
        val image = segment.toolImages.getOrNull(imageIndex) ?: return null
        if (image.path.isBlank() || image.sizeBytes !in 1..ToolImageStore.MAX_IMAGE_BYTES) return null
        val mime = image.mimeType.lowercase()
        if (mime !in RASTER_TYPES) return null
        return withContext(Dispatchers.IO) {
            try {
                val root = directory.toPath().toRealPath()
                val path = File(image.path).toPath().toRealPath()
                val file = path.toFile()
                if (!path.startsWith(root) || path == root || !file.isFile || file.length() != image.sizeBytes) {
                    return@withContext null
                }
                WebUiToolImageFile(file, image.sizeBytes, mime)
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }
    }

    companion object {
        private val RASTER_TYPES = setOf(
            "image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif",
            "image/heic", "image/heif", "image/avif",
        )
    }
}

internal data class WebUiToolImageFile(val file: File, val size: Long, val mimeType: String)
