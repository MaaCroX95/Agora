package com.newoether.agora.remote

import com.newoether.agora.model.ToolImageAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/** Private disposable files only; native images and ordinary durable tool media are untouched. */
internal class RemoteImageCache(private val directory: File, private val maxBytes: Long = 128L * 1024 * 1024) {
    private val slots = Semaphore(2)
    private val entries = LinkedHashMap<String, ToolImageAttachment>(16, .75f, true)
    private val lock = Any()

    suspend fun load(key: String, fetch: suspend () -> ToolImageAttachment): ToolImageAttachment =
        withContext(Dispatchers.IO) {
            slots.withPermit {
                synchronized(lock) { entries[key]?.takeIf { File(it.path).isFile } }?.let { return@withPermit it }
                val image = fetch()
                synchronized(lock) {
                    entries[key] = image
                    File(image.path).setLastModified(System.currentTimeMillis())
                    val files = directory.listFiles().orEmpty().filter { it.isFile && !it.name.startsWith(".") }
                        .sortedBy { it.lastModified() }.toMutableList()
                    var bytes = files.sumOf { it.length() }
                    while ((bytes > maxBytes || files.size > 64) && files.size > 1) {
                        val old = files.removeAt(0)
                        bytes -= old.length()
                        if (old.absolutePath != image.path) old.delete()
                    }
                    entries.entries.removeAll { !File(it.value.path).isFile }
                    image
                }
            }
        }
}
