package com.newoether.agora.viewmodel

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.newoether.agora.util.AttachmentSourceReader
import java.io.File
import java.net.URI
import java.net.URLConnection
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class VideoSliceConfig(
    val intervalMicros: Long,
    val frameCount: Int,
)

class ImageProcessor(
    private val app: Application,
) {
    suspend fun processImagesAndVideos(
        uris: List<String>,
        sliceConfigs: Map<String, VideoSliceConfig> = emptyMap(),
    ): List<String> = withContext(Dispatchers.IO) {
        uris.flatMap { source ->
            coroutineContext.ensureActive()
            val sliceConfig = sliceConfigs[source]
            val mimeType = resolveMimeType(source)
            when {
                sliceConfig != null -> extractVideoFrames(source, sliceConfig)
                mimeType?.startsWith("video/") == true ->
                    extractVideoFrames(source, VideoSliceConfig(intervalMicros = 0L, frameCount = 1))
                mimeType?.startsWith("image/") == true || mimeType == null ->
                    normalizeImage(source)?.let(::listOf).orEmpty()
                else -> emptyList()
            }
        }
    }

    suspend fun normalizeImage(source: String): String? = withContext(Dispatchers.IO) {
        var output: File? = null
        try {
            coroutineContext.ensureActive()
            val bounds = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            openStream(source)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

            var scale = 1
            while (
                bounds.outWidth / scale / 2 >= 1024 &&
                bounds.outHeight / scale / 2 >= 1024
            ) {
                scale *= 2
            }

            coroutineContext.ensureActive()
            val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = scale
            }
            val bitmap = openStream(source)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return@withContext null

            try {
                coroutineContext.ensureActive()
                val target = File(app.filesDir, "img_${UUID.randomUUID()}.jpg")
                output = target
                val encoded = target.outputStream().use { stream ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, stream)
                }
                check(encoded) { "Image encoding failed" }
                coroutineContext.ensureActive()
                target.absolutePath
            } finally {
                bitmap.recycle()
            }
        } catch (cancelled: CancellationException) {
            output?.delete()
            throw cancelled
        } catch (_: Exception) {
            output?.delete()
            null
        }
    }

    suspend fun extractVideoFrames(
        source: String,
        config: VideoSliceConfig,
    ): List<String> = withContext(Dispatchers.IO) {
        val paths = mutableListOf<String>()
        val retriever = MediaMetadataRetriever()
        try {
            setRetrieverSource(retriever, source)
            val frameCount = config.frameCount.coerceAtLeast(1)
            var timeUs = 0L
            repeat(frameCount) { index ->
                coroutineContext.ensureActive()
                val bitmap = retriever.getFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                )
                if (bitmap != null) {
                    val output = File(app.filesDir, "vid_${UUID.randomUUID()}_$index.jpg")
                    try {
                        val encoded = output.outputStream().use { stream ->
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, stream)
                        }
                        check(encoded) { "Video frame encoding failed" }
                        coroutineContext.ensureActive()
                        paths += output.absolutePath
                    } catch (failure: Exception) {
                        output.delete()
                        throw failure
                    } finally {
                        bitmap.recycle()
                    }
                }
                timeUs += config.intervalMicros.coerceAtLeast(0L)
            }
            paths
        } catch (cancelled: CancellationException) {
            paths.forEach { File(it).delete() }
            throw cancelled
        } catch (_: Exception) {
            paths.forEach { File(it).delete() }
            emptyList()
        } finally {
            retriever.release()
        }
    }

    private fun resolveMimeType(source: String): String? = runCatching {
        when {
            File(source).isAbsolute -> URLConnection.guessContentTypeFromName(source)
            source.startsWith("file:", ignoreCase = true) ->
                URLConnection.guessContentTypeFromName(File(URI(source)).name)
            else -> app.contentResolver.getType(Uri.parse(source))
        }
    }.getOrNull()

    private fun setRetrieverSource(
        retriever: MediaMetadataRetriever,
        source: String,
    ) {
        when {
            File(source).isAbsolute -> retriever.setDataSource(source)
            source.startsWith("file:", ignoreCase = true) ->
                retriever.setDataSource(File(URI(source)).absolutePath)
            else -> retriever.setDataSource(app, Uri.parse(source))
        }
    }

    private fun openStream(source: String): java.io.InputStream? =
        AttachmentSourceReader.open(app, source)
}
