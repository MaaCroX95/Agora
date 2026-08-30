package com.newoether.agora.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * On-demand reader over a validated backup ZIP. Metadata is bounded and verified when the archive
 * opens. Resource entries remain uncapped, but are capacity-checked and byte-verified before import.
 */
internal class NativeBackupArchive private constructor(
    private val zip: ZipFile,
    private val temporaryFile: File,
    private val entries: Map<String, ZipEntry>,
    private val metadataLimitBytes: Long,
) : Closeable {
    fun has(name: String): Boolean = entries.containsKey(name)

    fun size(name: String): Long = entries[name]?.size ?: -1L

    fun bytes(name: String): ByteArray? {
        val entry = entries[name] ?: return null
        if (isResourceEntry(name)) {
            throw IOException("Resource entry must be copied to storage: $name")
        }
        return zip.getInputStream(entry).use { input ->
            readBounded(input, metadataLimitBytes, name)
        }
    }

    operator fun get(name: String): ByteArray? = bytes(name)

    fun stream(name: String): InputStream? {
        val entry = entries[name] ?: return null
        if (isResourceEntry(name)) {
            throw IOException("Resource entry must be copied to storage: $name")
        }
        return zip.getInputStream(entry)
    }

    fun prefix(name: String, byteCount: Int): ByteArray? {
        require(byteCount >= 0)
        val entry = entries[name] ?: return null
        return zip.getInputStream(entry).use { input ->
            val bytes = ByteArray(byteCount)
            var total = 0
            while (total < byteCount) {
                val count = input.read(bytes, total, byteCount - total)
                if (count < 0) break
                if (count == 0) continue
                total += count
            }
            bytes.copyOf(total)
        }
    }

    fun names(): List<String> = entries.keys.toList()

    fun preflightImportResources(
        conversationsSelected: Boolean,
        settingsSelected: Boolean,
        archiveVersion: Int,
        destinationRoot: File,
        customFontLimitBytes: Long = Long.MAX_VALUE,
        availableBytes: () -> Long = { destinationRoot.usableSpace },
    ): Long {
        val selected = entries.values.filter { entry ->
            conversationsSelected && isConversationResource(entry.name)
        }.toMutableList()
        if (settingsSelected) {
            val fontEntry = if (archiveVersion >= 4) {
                entries[NativeBackupFormat.CUSTOM_FONT_ENTRY]
            } else {
                entries.values.firstOrNull { isLegacyCustomFont(it.name) }
            }
            if (fontEntry != null) selected += fontEntry
        }
        val requiredBytes = selected.fold(0L) { total, entry ->
            val extractedSize = if (
                isLegacyCustomFont(entry.name) && entry.size > customFontLimitBytes
            ) {
                0L
            } else {
                entry.size
            }
            checkedAdd(total, extractedSize, "Selected resource size is too large")
        }
        ensureAvailable(requiredBytes, availableBytes(), destinationRoot)
        selected.forEach(::validateEntryStream)
        return requiredBytes
    }

    fun copyTo(
        name: String,
        target: File,
        maxBytes: Long = Long.MAX_VALUE,
        availableBytes: () -> Long = { target.parentFile?.usableSpace ?: target.usableSpace },
    ): Long? {
        val entry = entries[name] ?: return null
        return zip.getInputStream(entry).use { input ->
            copyStreamToFile(
                input = input,
                target = target,
                declaredSize = entry.size,
                expectedCrc = entry.crc,
                maxBytes = maxBytes,
                availableBytes = availableBytes,
                sourceName = name,
            )
        }
    }

    private fun validateEntryStream(entry: ZipEntry) {
        zip.getInputStream(entry).use { input ->
            consumeChecked(input, entry.size, entry.crc, Long.MAX_VALUE, entry.name)
        }
    }

    override fun close() {
        try {
            zip.close()
        } finally {
            temporaryFile.delete()
        }
    }

    companion object {
        internal const val MAX_METADATA_BYTES = 256L * 1024L * 1024L
        private const val BUFFER_BYTES = 32 * 1024

        fun open(context: Context, uri: Uri): NativeBackupArchive? {
            val declaredSize = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull()?.takeIf { it >= 0L } ?: runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
                }?.takeIf { it >= 0L } ?: -1L
            }.getOrDefault(-1L)
            val input = context.contentResolver.openInputStream(uri) ?: return null
            return input.use { source ->
                context.cacheDir.mkdirs()
                val temporaryFile = File.createTempFile("agora_import_", ".zip", context.cacheDir)
                try {
                    copyStreamToFile(
                        input = source,
                        target = temporaryFile,
                        declaredSize = declaredSize,
                        availableBytes = { context.cacheDir.usableSpace },
                        sourceName = "backup archive",
                    )
                    open(temporaryFile)
                } catch (error: Exception) {
                    temporaryFile.delete()
                    throw error
                }
            }
        }

        internal fun open(
            temporaryFile: File,
            metadataLimitBytes: Long = MAX_METADATA_BYTES,
        ): NativeBackupArchive {
            var zip: ZipFile? = null
            try {
                zip = ZipFile(temporaryFile)
                val entries = validateArchive(zip, metadataLimitBytes)
                return NativeBackupArchive(zip, temporaryFile, entries, metadataLimitBytes)
            } catch (error: Exception) {
                runCatching { zip?.close() }
                temporaryFile.delete()
                if (error is IOException) throw error
                throw IOException("Invalid backup archive", error)
            }
        }

        internal fun copyStreamToFile(
            input: InputStream,
            target: File,
            declaredSize: Long = -1L,
            expectedCrc: Long = -1L,
            maxBytes: Long = Long.MAX_VALUE,
            availableBytes: () -> Long = {
                target.parentFile?.usableSpace ?: target.usableSpace
            },
            bufferBytes: Int = BUFFER_BYTES,
            sourceName: String = target.name,
        ): Long {
            require(bufferBytes > 0)
            try {
                if (declaredSize < -1L) throw IOException("Invalid declared size for $sourceName")
                if (declaredSize > maxBytes) {
                    throw IOException("$sourceName exceeds the import size limit")
                }
                target.parentFile?.let { parent ->
                    if (!parent.isDirectory && !parent.mkdirs()) {
                        throw IOException("Could not create import destination: ${parent.absolutePath}")
                    }
                }
                if (declaredSize >= 0L) {
                    ensureAvailable(declaredSize, availableBytes(), target)
                }
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(bufferBytes)
                    val crc = CRC32()
                    var total = 0L
                    while (true) {
                        val readLimit = if (
                            declaredSize >= 0L && declaredSize - total < buffer.size
                        ) {
                            (declaredSize - total + 1L).toInt()
                        } else {
                            buffer.size
                        }
                        val count = input.read(buffer, 0, readLimit)
                        if (count < 0) break
                        if (count == 0) continue
                        total = checkedAdd(total, count.toLong(), "$sourceName is too large")
                        if (declaredSize >= 0L && total > declaredSize) {
                            throw IOException(
                                "$sourceName size mismatch: declared $declaredSize bytes, read more",
                            )
                        }
                        if (total > maxBytes) {
                            throw IOException("$sourceName exceeds the import size limit")
                        }
                        ensureAvailable(count.toLong(), availableBytes(), target)
                        crc.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    verifyEntry(total, declaredSize, crc.value, expectedCrc, sourceName)
                    return total
                }
            } catch (error: Exception) {
                target.delete()
                throw error
            }
        }

        private fun validateArchive(
            zip: ZipFile,
            metadataLimitBytes: Long,
        ): Map<String, ZipEntry> {
            require(metadataLimitBytes >= 0L)
            val files = linkedMapOf<String, ZipEntry>()
            val canonicalNames = mutableSetOf<String>()
            var declaredMetadataBytes = 0L
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                val canonicalName = validateEntryName(entry)
                if (!canonicalNames.add(canonicalName)) {
                    throw IOException("Duplicate or ambiguous ZIP entry: ${entry.name}")
                }
                if (entry.size < 0L) {
                    throw IOException("ZIP entry has an unknown declared size: ${entry.name}")
                }
                if (entry.crc < 0L) {
                    throw IOException("ZIP entry has an unknown CRC: ${entry.name}")
                }
                if (entry.isDirectory) {
                    if (entry.size > 0L) {
                        throw IOException("ZIP directory entry contains data: ${entry.name}")
                    }
                    continue
                }
                files[entry.name] = entry
                if (!isResourceEntry(entry.name)) {
                    declaredMetadataBytes = checkedAdd(
                        declaredMetadataBytes,
                        entry.size,
                        "Backup metadata is too large",
                    )
                    if (declaredMetadataBytes > metadataLimitBytes) {
                        throw IOException("Backup metadata exceeds the 256 MiB limit")
                    }
                }
            }
            canonicalNames.forEach { name ->
                var slash = name.lastIndexOf('/')
                while (slash > 0) {
                    val parent = name.substring(0, slash)
                    if (parent in files) {
                        throw IOException("Ambiguous ZIP entry path: $name")
                    }
                    slash = parent.lastIndexOf('/')
                }
            }
            files.values.asSequence()
                .filterNot { isResourceEntry(it.name) }
                .forEach { entry ->
                    zip.getInputStream(entry).use { input ->
                        consumeChecked(
                            input,
                            entry.size,
                            entry.crc,
                            metadataLimitBytes,
                            entry.name,
                        )
                    }
                }
            return files
        }

        private fun validateEntryName(entry: ZipEntry): String {
            val name = entry.name
            if (name.isEmpty() || name.startsWith('/') || name.startsWith('\\')) {
                throw IOException("Unsafe ZIP entry path: $name")
            }
            if ('\\' in name || (name.length >= 2 && name[0].isLetter() && name[1] == ':')) {
                throw IOException("Ambiguous ZIP entry path: $name")
            }
            val canonical = if (entry.isDirectory) name.dropLast(1) else name
            if (canonical.isEmpty()) throw IOException("Unsafe ZIP entry path: $name")
            val segments = canonical.split('/')
            if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
                throw IOException("Unsafe ZIP entry path: $name")
            }
            return canonical
        }

        private fun isResourceEntry(name: String): Boolean =
            isConversationResource(name) || isLegacyCustomFont(name)

        private fun isConversationResource(name: String): Boolean =
            name.startsWith(NativeBackupFormat.IMAGE_MEDIA_PREFIX) ||
                name.startsWith(NativeBackupFormat.VIDEO_MEDIA_PREFIX) ||
                name.startsWith(NativeBackupFormat.DRAFT_MEDIA_PREFIX) ||
                name.startsWith("images/") ||
                name.startsWith("videos/")

        private fun isLegacyCustomFont(name: String): Boolean =
            name.startsWith("custom_font/") &&
                !name.removePrefix("custom_font/").contains('/')

        private fun readBounded(
            input: InputStream,
            maxBytes: Long,
            sourceName: String,
        ): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_BYTES)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total = checkedAdd(total, count.toLong(), "$sourceName is too large")
                if (total > maxBytes) {
                    throw IOException("$sourceName exceeds the metadata size limit")
                }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }

        private fun consumeChecked(
            input: InputStream,
            declaredSize: Long,
            expectedCrc: Long,
            maxBytes: Long,
            sourceName: String,
        ): Long {
            val buffer = ByteArray(BUFFER_BYTES)
            val crc = CRC32()
            var total = 0L
            while (true) {
                val readLimit = if (
                    declaredSize >= 0L && declaredSize - total < buffer.size
                ) {
                    (declaredSize - total + 1L).toInt()
                } else {
                    buffer.size
                }
                val count = input.read(buffer, 0, readLimit)
                if (count < 0) break
                if (count == 0) continue
                total = checkedAdd(total, count.toLong(), "$sourceName is too large")
                if (declaredSize >= 0L && total > declaredSize) {
                    throw IOException(
                        "$sourceName size mismatch: declared $declaredSize bytes, read more",
                    )
                }
                if (total > maxBytes) {
                    throw IOException("$sourceName exceeds the metadata size limit")
                }
                crc.update(buffer, 0, count)
            }
            verifyEntry(total, declaredSize, crc.value, expectedCrc, sourceName)
            return total
        }

        private fun verifyEntry(
            actualSize: Long,
            declaredSize: Long,
            actualCrc: Long,
            expectedCrc: Long,
            sourceName: String,
        ) {
            if (declaredSize >= 0L && actualSize != declaredSize) {
                throw IOException(
                    "$sourceName size mismatch: declared $declaredSize bytes, read $actualSize",
                )
            }
            if (expectedCrc >= 0L && actualCrc != expectedCrc) {
                throw IOException("$sourceName CRC mismatch")
            }
        }

        private fun ensureAvailable(required: Long, available: Long, destination: File) {
            if (required > available) {
                throw IOException(
                    "Insufficient storage for ${destination.absolutePath}: " +
                        "$required bytes required, $available available",
                )
            }
        }

        private fun checkedAdd(left: Long, right: Long, message: String): Long =
            try {
                Math.addExact(left, right)
            } catch (_: ArithmeticException) {
                throw IOException(message)
            }
    }
}
