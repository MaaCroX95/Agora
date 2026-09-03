package com.newoether.agora.api

import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock

internal enum class LlamaGenerationStopReason(val nativeValue: String) {
    EOG("eog"),
    MAX_TOKENS("max_tokens"),
    CONTEXT_FULL("context_full"),
    CANCELLED("cancelled");

    companion object {
        fun fromNative(value: String): LlamaGenerationStopReason? = entries
            .firstOrNull { it.nativeValue == value }
    }
}

internal sealed interface LlamaGenerationEvent {
    data class Text(val value: String) : LlamaGenerationEvent
    data class Completed(
        val reason: LlamaGenerationStopReason,
        val inputTokenCount: Int,
        val outputTokenCount: Int,
    ) : LlamaGenerationEvent

    data class Failed(
        val message: String,
        val inputTokenCount: Int,
        val outputTokenCount: Int,
    ) : LlamaGenerationEvent
}

interface NativeChatCallback {
    fun onToken(token: String): Boolean
    fun onDone(reason: String, inputTokenCount: Int, outputTokenCount: Int)
    fun onError(message: String, inputTokenCount: Int, outputTokenCount: Int)
}

class ChatTemplateMessage(val role: String, val content: String)

class LlamaChatEngine(
    val modelPath: String,
    val nCtx: Int = 2048
) : Closeable {
    companion object {
        private const val TAG = "LlamaChatEngine"

        init {
            System.loadLibrary("c++_shared")
            System.loadLibrary("agora_llama")
        }
    }

    @Volatile
    private var nativeHandle: Long = 0
    @Volatile
    private var loadedMmprojPath: String? = null
    private val lock = ReentrantReadWriteLock()

    private external fun nativeChatLoadModel(path: String, nCtx: Int): Long
    private external fun nativeChatGetTemplate(handle: Long): String?
    private external fun nativeChatApplyTemplate(
        handle: Long,
        messages: Array<ChatTemplateMessage>,
        addAss: Boolean,
        enableThinking: Boolean,
    ): String?
    private external fun nativeChatLoadMmproj(handle: Long, mmprojPath: String): Boolean
    private external fun nativeChatUnloadMmproj(handle: Long)
    private external fun nativeChatHasMmproj(handle: Long): Boolean
    private external fun nativeChatGenerateWithImages(
        handle: Long, prompt: String, imagePaths: Array<String>,
        temperature: Float, topP: Float, frequencyPenalty: Float, presencePenalty: Float,
        maxTokens: Int, callback: NativeChatCallback,
    ): Int
    private external fun nativeChatGenerate(
        handle: Long, prompt: String, temperature: Float, topP: Float,
        frequencyPenalty: Float, presencePenalty: Float, maxTokens: Int,
        callback: NativeChatCallback,
    ): Int
    private external fun nativeChatReset(handle: Long)
    private external fun nativeChatFreeModel(handle: Long)
    private external fun nativeChatCancel(handle: Long)

    fun isLoaded(): Boolean = nativeHandle != 0L

    fun matches(path: String, contextSize: Int): Boolean =
        nativeHandle != 0L && modelPath == path && nCtx == contextSize

    fun load(): Boolean {
        if (!File(modelPath).exists()) {
            DebugLog.e(TAG, "Model file not found")
            return false
        }
        lock.writeLock().lock()
        try {
            nativeHandle = nativeChatLoadModel(modelPath, nCtx)
            if (nativeHandle == 0L) {
                DebugLog.e(TAG, "Failed to load model")
                return false
            }
            DebugLog.d(TAG, "Model loaded, nCtx=$nCtx")
            return true
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun getChatTemplate(): String? {
        lock.readLock().lock()
        try {
            if (nativeHandle == 0L) return null
            return nativeChatGetTemplate(nativeHandle)
        } finally {
            lock.readLock().unlock()
        }
    }

    fun applyTemplate(
        messages: List<ChatTemplateMessage>,
        addAss: Boolean = true,
        enableThinking: Boolean = true,
    ): String? {
        lock.readLock().lock()
        try {
            if (nativeHandle == 0L) return null
            return nativeChatApplyTemplate(
                nativeHandle,
                messages.toTypedArray(),
                addAss,
                enableThinking,
            )
        } finally {
            lock.readLock().unlock()
        }
    }

    internal fun generate(
        prompt: String,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        frequencyPenalty: Float = 0f,
        presencePenalty: Float = 0f,
        maxTokens: Int = 4096,
    ): Flow<LlamaGenerationEvent> = callbackFlow {
        if (nativeHandle == 0L) {
            close(RuntimeException("Model not loaded"))
            return@callbackFlow
        }

        val terminalSignalled = AtomicBoolean(false)
        val callback = object : NativeChatCallback {
            override fun onToken(token: String): Boolean =
                !terminalSignalled.get() &&
                    trySendBlocking(LlamaGenerationEvent.Text(token)).isSuccess

            override fun onDone(reason: String, inputTokenCount: Int, outputTokenCount: Int) {
                if (!terminalSignalled.compareAndSet(false, true)) return
                val parsedReason = LlamaGenerationStopReason.fromNative(reason)
                if (parsedReason == null) {
                    trySendBlocking(
                        LlamaGenerationEvent.Failed(
                            message = "Unknown native stop reason: $reason",
                            inputTokenCount = inputTokenCount,
                            outputTokenCount = outputTokenCount,
                        )
                    )
                } else {
                    trySendBlocking(
                        LlamaGenerationEvent.Completed(
                            reason = parsedReason,
                            inputTokenCount = inputTokenCount,
                            outputTokenCount = outputTokenCount,
                        )
                    )
                }
                this@callbackFlow.close()
            }

            override fun onError(message: String, inputTokenCount: Int, outputTokenCount: Int) {
                if (!terminalSignalled.compareAndSet(false, true)) return
                DebugLog.e(TAG, "Generation error reported by native backend")
                trySendBlocking(
                    LlamaGenerationEvent.Failed(message, inputTokenCount, outputTokenCount)
                )
                this@callbackFlow.close()
            }
        }

        launch(Dispatchers.IO) {
            lock.readLock().lock()
            try {
                val handle = nativeHandle
                if (handle != 0L) {
                    val result = nativeChatGenerate(
                        handle, prompt, temperature, topP, frequencyPenalty, presencePenalty,
                        maxTokens, callback,
                    )
                    if (result < 0 && !terminalSignalled.get()) {
                        callback.onError("Native generation ended without a terminal result", 0, 0)
                    }
                } else {
                    callback.onError("Model closed before generation started", 0, 0)
                }
            } catch (e: Exception) {
                DebugLog.e(TAG, "nativeChatGenerate crashed", e)
                close(e)
            } finally {
                lock.readLock().unlock()
            }
        }

        awaitClose {
            lock.readLock().lock()
            try {
                if (nativeHandle != 0L) {
                    nativeChatCancel(nativeHandle)
                }
            } finally {
                lock.readLock().unlock()
            }
        }
    }

    fun loadMmproj(mmprojPath: String): Boolean {
        if (!File(mmprojPath).exists()) {
            DebugLog.e(TAG, "mmproj file not found")
            return false
        }
        lock.writeLock().lock()
        try {
            if (nativeHandle == 0L) return false
            if (loadedMmprojPath == mmprojPath && nativeChatHasMmproj(nativeHandle)) {
                return true
            }
            val loaded = nativeChatLoadMmproj(nativeHandle, mmprojPath)
            if (loaded) loadedMmprojPath = mmprojPath
            return loaded
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun hasMmproj(): Boolean {
        lock.readLock().lock()
        try {
            return nativeHandle != 0L && nativeChatHasMmproj(nativeHandle)
        } finally {
            lock.readLock().unlock()
        }
    }

    fun unloadMmproj() {
        lock.writeLock().lock()
        try {
            if (nativeHandle != 0L && loadedMmprojPath != null) {
                nativeChatUnloadMmproj(nativeHandle)
            }
            loadedMmprojPath = null
        } finally {
            lock.writeLock().unlock()
        }
    }

    internal fun generateWithImages(
        prompt: String,
        imagePaths: List<String>,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        frequencyPenalty: Float = 0f,
        presencePenalty: Float = 0f,
        maxTokens: Int = 4096,
    ): Flow<LlamaGenerationEvent> = callbackFlow {
        if (nativeHandle == 0L) {
            close(RuntimeException("Model not loaded"))
            return@callbackFlow
        }

        val terminalSignalled = AtomicBoolean(false)
        val callback = object : NativeChatCallback {
            override fun onToken(token: String): Boolean =
                !terminalSignalled.get() &&
                    trySendBlocking(LlamaGenerationEvent.Text(token)).isSuccess

            override fun onDone(reason: String, inputTokenCount: Int, outputTokenCount: Int) {
                if (!terminalSignalled.compareAndSet(false, true)) return
                val parsedReason = LlamaGenerationStopReason.fromNative(reason)
                val event = if (parsedReason == null) {
                    LlamaGenerationEvent.Failed(
                        message = "Unknown native stop reason: $reason",
                        inputTokenCount = inputTokenCount,
                        outputTokenCount = outputTokenCount,
                    )
                } else {
                    LlamaGenerationEvent.Completed(
                        reason = parsedReason,
                        inputTokenCount = inputTokenCount,
                        outputTokenCount = outputTokenCount,
                    )
                }
                trySendBlocking(event)
                this@callbackFlow.close()
            }

            override fun onError(message: String, inputTokenCount: Int, outputTokenCount: Int) {
                if (!terminalSignalled.compareAndSet(false, true)) return
                DebugLog.e(TAG, "Generation error reported by native backend")
                trySendBlocking(
                    LlamaGenerationEvent.Failed(message, inputTokenCount, outputTokenCount)
                )
                this@callbackFlow.close()
            }
        }

        launch(Dispatchers.IO) {
            lock.readLock().lock()
            try {
                val handle = nativeHandle
                if (handle != 0L) {
                    val result = nativeChatGenerateWithImages(
                        handle, prompt, imagePaths.toTypedArray(),
                        temperature, topP, frequencyPenalty, presencePenalty,
                        maxTokens, callback,
                    )
                    if (result < 0 && !terminalSignalled.get()) {
                        callback.onError("Native generation ended without a terminal result", 0, 0)
                    }
                } else {
                    callback.onError("Model closed before generation started", 0, 0)
                }
            } catch (e: Exception) {
                DebugLog.e(TAG, "nativeChatGenerateWithImages crashed", e)
                close(e)
            } finally {
                lock.readLock().unlock()
            }
        }

        awaitClose {
            lock.readLock().lock()
            try {
                if (nativeHandle != 0L) nativeChatCancel(nativeHandle)
            } finally {
                lock.readLock().unlock()
            }
        }
    }

    fun cancel() {
        lock.readLock().lock()
        try {
            if (nativeHandle != 0L) {
                nativeChatCancel(nativeHandle)
            }
        } finally {
            lock.readLock().unlock()
        }
    }

    fun resetContext() {
        lock.writeLock().lock()
        try {
            if (nativeHandle != 0L) {
                nativeChatReset(nativeHandle)
            }
        } finally {
            lock.writeLock().unlock()
        }
    }

    override fun close() {
        lock.readLock().lock()
        try {
            if (nativeHandle != 0L) {
                nativeChatCancel(nativeHandle)
            }
        } finally {
            lock.readLock().unlock()
        }

        lock.writeLock().lock()
        try {
            if (nativeHandle != 0L) {
                nativeChatFreeModel(nativeHandle)
                nativeHandle = 0L
                loadedMmprojPath = null
                DebugLog.d(TAG, "Model closed")
            }
        } finally {
            lock.writeLock().unlock()
        }
    }

    protected fun finalize() {
        close()
    }
}
