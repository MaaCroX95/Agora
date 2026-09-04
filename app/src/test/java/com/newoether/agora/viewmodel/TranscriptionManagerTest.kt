package com.newoether.agora.viewmodel

import android.content.Context
import com.newoether.agora.R
import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ToolImageAttachment
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptionManagerTest {
    private val image = ToolImageAttachment(
        path = "/private/tool.png",
        mimeType = "image/png",
        sizeBytes = 1L,
        width = 1,
        height = 1,
        sha256 = "sha",
    )

    @Test
    fun `describeImage streams chunks and returns the trimmed description`() = runTest {
        val manager = manager(
            providers = mapOf(
                "transcriber" to fakeProvider(
                    events = listOf(
                        StreamEvent.TextChunk("A cat "),
                        StreamEvent.TextChunk("sitting."),
                    ),
                ),
            ),
        )
        val progress = mutableListOf<String>()

        val description = manager.describeImageWithProgress(
            image = image,
            ctx = context(providerName = "transcriber"),
            generationJob = null,
            conversationId = "conversation",
            runId = "run",
            pass = 2,
            modelMessageId = "assistant",
            onProgress = { progress += it },
        )

        assertEquals("A cat sitting.", description)
        // The transcribing state is announced before the first chunk; the block never starts
        // empty.
        assertEquals(listOf("Transcribing…", "A cat ", "A cat sitting."), progress)
    }

    @Test
    fun `describeImage fails open to null on stream errors but emits a failure notice`() = runTest {
        val manager = manager(
            providers = mapOf(
                "transcriber" to fakeProvider(
                    events = listOf(
                        StreamEvent.TextChunk("partial"),
                        StreamEvent.Error(GenerationError.Network(statusCode = 500, message = "boom")),
                    ),
                ),
            ),
        )
        val progress = mutableListOf<String>()

        val description = manager.describeImageWithProgress(
            image = image,
            ctx = context(providerName = "transcriber"),
            generationJob = null,
            conversationId = "conversation",
            runId = "run",
            pass = 2,
            modelMessageId = "assistant",
            onProgress = { progress += it },
        )

        assertNull(description)
        assertEquals(
            listOf("Transcribing…", "partial", "Image transcription failed"),
            progress,
        )
    }

    @Test
    fun `describeImage is fail closed on a missing provider but emits a failure notice`() = runTest {
        val manager = manager(providers = emptyMap())
        val progress = mutableListOf<String>()

        val description = manager.describeImageWithProgress(
            image = image,
            ctx = context(providerName = "absent"),
            generationJob = null,
            conversationId = "conversation",
            runId = "run",
            pass = 2,
            modelMessageId = "assistant",
            onProgress = { progress += it },
        )

        assertNull(description)
        assertEquals(
            listOf("Transcribing…", "Image transcription failed"),
            progress,
        )
    }

    private fun manager(providers: Map<String, LlmProvider>): TranscriptionManager {
        val context = mockk<Context>(relaxed = true)
        every { context.getString(R.string.transcription_ellipsis_single) } returns "Transcribing…"
        every {
            context.getString(R.string.generation_error_transcription, any<String>())
        } returns "Image transcription failed"
        return TranscriptionManager(
            providers = providers,
            conversations = mockk(relaxed = true),
            context = context,
        )
    }

    private fun context(providerName: String) = GenerationContext(
        imageTranscriptionEnabled = true,
        transcriptionProviderName = providerName,
        transcriptionModelId = "vision-model",
        transcriptionApiKey = "key",
    )

    private fun fakeProvider(events: List<StreamEvent>): LlmProvider = object : LlmProvider {
        override val name: String = "fake"
        override val defaultBaseUrl: String = ""
        override fun generateResponse(
            messages: List<ChatMessage>,
            config: ProviderConfig,
        ): Flow<StreamEvent> = flowOf(*events.toTypedArray())
        override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> = emptyList()
    }
}
