package com.newoether.agora.viewmodel

import android.app.Application
import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.model.AttachmentStorage
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.util.AttachmentFiles
import com.newoether.agora.util.AttachmentSourceReader
import com.newoether.agora.util.Constants
import com.newoether.agora.util.PdfPageRenderer
import java.io.File
import java.io.InputStream
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Stateless, one-attempt attachment staging and preparation boundary. */
internal class AttachmentImportProcessor(
    private val app: Application,
    private val normalizeImage: suspend (source: String) -> String? = { source ->
        ImageProcessor(app).normalizeImage(source)
    },
    private val extractVideoFrames: suspend (
        source: String,
        config: VideoSliceConfig,
    ) -> List<String> = { source, config ->
        ImageProcessor(app).extractVideoFrames(source, config)
    },
    private val renderPdf: suspend (source: String, pages: Set<Int>?) -> List<String> =
        { source, pages -> PdfPageRenderer.renderAsImages(app, source, pages) },
    private val readText: (source: String, maxChars: Int) -> String? =
        { source, maxChars -> AttachmentSourceReader.readText(app, source, maxChars) },
    private val openSource: (source: String) -> InputStream? =
        { source -> AttachmentSourceReader.open(app, source) },
    private val maxAttachmentBytes: Long = AttachmentFiles.MAX_ATTACHMENT_BYTES,
) {
    sealed interface StageResult {
        data class Success(
            val attachment: SelectedAttachment,
            val createdPaths: List<String>,
            val obsoletePaths: List<String> = emptyList(),
        ) : StageResult
        data object TooLarge : StageResult
        data class Failure(val cause: Throwable? = null) : StageResult
    }

    sealed interface ProcessResult {
        data class Ready(
            val attachment: SelectedAttachment,
            val createdPaths: List<String> = emptyList(),
            val obsoletePaths: List<String> = emptyList(),
        ) : ProcessResult

        data class Failure(val cause: Throwable? = null) : ProcessResult
    }

    suspend fun stage(attachment: SelectedAttachment): StageResult = withContext(Dispatchers.IO) {
        val source = attachment.localPath ?: attachment.uri
        val directory = File(File(app.filesDir, "attachments/staged"), attachment.localId)
        val extension = sourceExtension(attachment)
        val target = File(directory, "source.$extension")
        val partial = File(directory, "source.$extension.part")
        try {
            val input = openSource(source)
                ?: return@withContext StageResult.Failure(
                    IllegalStateException("Unable to open attachment source"),
                )
            when (
                val copy = AttachmentFiles.copyBounded(
                    input = input,
                    target = partial,
                    expectedSize = attachment.fileSize,
                    maxBytes = maxAttachmentBytes,
                )
            ) {
                is AttachmentFiles.CopyResult.Success -> {
                    if (target.exists() && !target.delete()) {
                        return@withContext StageResult.Failure(
                            IllegalStateException("Unable to replace staged attachment"),
                        )
                    }
                    if (!partial.renameTo(target)) {
                        return@withContext StageResult.Failure(
                            IllegalStateException("Unable to promote staged attachment"),
                        )
                    }
                    val obsoletePaths = attachment.localPath
                        ?.takeUnless { it == target.absolutePath }
                        ?.let(::listOf)
                        .orEmpty()
                    StageResult.Success(
                        attachment = attachment.copy(
                            fileSize = copy.bytesCopied,
                            processedFrames = null,
                            preRenderedPaths = null,
                            localPath = target.absolutePath,
                            importState = AttachmentImportState.PROCESSING,
                            preparedText = null,
                            unavailable = false,
                        ),
                        createdPaths = listOf(target.absolutePath),
                        obsoletePaths = obsoletePaths,
                    )
                }
                AttachmentFiles.CopyResult.TooLarge -> StageResult.TooLarge
                is AttachmentFiles.CopyResult.Failure -> StageResult.Failure(copy.cause)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            StageResult.Failure(failure)
        } finally {
            partial.delete()
            if (directory.isDirectory && directory.list().isNullOrEmpty()) directory.delete()
        }
    }

    suspend fun process(
        attachment: SelectedAttachment,
        sandboxHomeDir: File? = null,
    ): ProcessResult = withContext(Dispatchers.IO) {
        val stagedPath = attachment.localPath
            ?: return@withContext ProcessResult.Failure(
                IllegalStateException("Attachment has no private staged source"),
            )
        val stagedFile = File(stagedPath)
        if (!stagedFile.isFile) {
            return@withContext ProcessResult.Failure(
                IllegalStateException("Attachment staged source is unavailable"),
            )
        }

        try {
            when {
                attachment.storage == AttachmentStorage.LOCAL_SANDBOX_PENDING ->
                    processSandbox(attachment, stagedFile, sandboxHomeDir)
                attachment.type == "image" -> processImage(attachment, stagedPath)
                attachment.type == "video" -> processVideo(attachment, stagedPath)
                attachment.type == "pdf" -> processPdf(attachment, stagedPath)
                attachment.type == "file" -> processFile(attachment, stagedPath)
                else -> ProcessResult.Failure(
                    IllegalArgumentException("Unsupported attachment type: ${attachment.type}"),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            ProcessResult.Failure(failure)
        }
    }

    private suspend fun processImage(
        attachment: SelectedAttachment,
        stagedPath: String,
    ): ProcessResult {
        val normalizedPath = normalizeImage(stagedPath)
            ?: return ProcessResult.Failure(IllegalStateException("Image normalization failed"))
        return ProcessResult.Ready(
            attachment = attachment.copy(
                localPath = normalizedPath,
                fileSize = File(normalizedPath).length(),
                importState = AttachmentImportState.READY,
            ),
            createdPaths = listOf(normalizedPath),
            obsoletePaths = listOf(stagedPath).filterNot { it == normalizedPath },
        )
    }

    private suspend fun processVideo(
        attachment: SelectedAttachment,
        stagedPath: String,
    ): ProcessResult {
        val frameCount = attachment.frameCount
            ?: return ProcessResult.Failure(IllegalStateException("Video slice is not selected"))
        val intervalMs = attachment.sliceIntervalMs
            ?: return ProcessResult.Failure(IllegalStateException("Video slice is not selected"))
        val frames = extractVideoFrames(
            stagedPath,
            VideoSliceConfig(intervalMicros = intervalMs * 1_000L, frameCount = frameCount),
        )
        if (frames.isEmpty()) {
            return ProcessResult.Failure(IllegalStateException("Video frame extraction failed"))
        }
        return ProcessResult.Ready(
            attachment = attachment.copy(
                processedFrames = frames,
                importState = AttachmentImportState.READY,
            ),
            createdPaths = frames,
        )
    }

    private suspend fun processPdf(
        attachment: SelectedAttachment,
        stagedPath: String,
    ): ProcessResult {
        val pages = renderPdf(stagedPath, attachment.selectedPages)
        if (pages.isEmpty()) {
            return ProcessResult.Failure(IllegalStateException("PDF rendering failed"))
        }
        return ProcessResult.Ready(
            attachment = attachment.copy(
                selectedPages = pages.indices.toSet(),
                preRenderedPaths = pages,
                importState = AttachmentImportState.READY,
            ),
            createdPaths = pages,
        )
    }

    private fun processFile(
        attachment: SelectedAttachment,
        stagedPath: String,
    ): ProcessResult {
        val text = readText(stagedPath, Constants.MAX_FILE_CONTENT_READ_LENGTH)
            ?: return ProcessResult.Failure(IllegalStateException("File text read failed"))
        return ProcessResult.Ready(
            attachment.copy(
                importState = AttachmentImportState.READY,
                preparedText = text,
            ),
        )
    }

    private fun processSandbox(
        attachment: SelectedAttachment,
        stagedFile: File,
        sandboxHomeDir: File?,
    ): ProcessResult {
        val home = sandboxHomeDir
            ?: return ProcessResult.Failure(IllegalStateException("Local Sandbox is unavailable"))
        val fileName = AttachmentFiles.sanitizeFileName(attachment.fileName)
        val relativePath = "attachments/${attachment.localId}/$fileName"
        val target = File(home, relativePath)
        return when (
            val copy = AttachmentFiles.copyBounded(
                input = stagedFile.inputStream(),
                target = target,
                expectedSize = attachment.fileSize,
                maxBytes = maxAttachmentBytes,
            )
        ) {
            is AttachmentFiles.CopyResult.Success -> ProcessResult.Ready(
                attachment = attachment.copy(
                    fileName = fileName,
                    fileSize = copy.bytesCopied,
                    localPath = target.absolutePath,
                    sandboxPath = "/home/agora/$relativePath",
                    importState = AttachmentImportState.READY,
                ),
                createdPaths = listOf(target.absolutePath),
                obsoletePaths = listOf(stagedFile.absolutePath),
            )
            AttachmentFiles.CopyResult.TooLarge ->
                ProcessResult.Failure(IllegalStateException("Attachment exceeds size limit"))
            is AttachmentFiles.CopyResult.Failure -> ProcessResult.Failure(copy.cause)
        }
    }

    private fun sourceExtension(attachment: SelectedAttachment): String {
        val preferred = attachment.fileName?.substringAfterLast('.', "")
            ?.takeIf(String::isNotBlank)
            ?: when (attachment.type) {
                "image" -> "img"
                "video" -> "video"
                "pdf" -> "pdf"
                else -> "bin"
            }
        return AttachmentFiles.sanitizeFileName(preferred).take(16)
    }
}
