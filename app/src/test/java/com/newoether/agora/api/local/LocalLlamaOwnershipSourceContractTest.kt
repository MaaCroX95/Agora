package com.newoether.agora.api.local

import com.newoether.agora.api.LlamaGenerationStopReason
import com.newoether.agora.data.LocalChatModelConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLlamaOwnershipSourceContractTest {
    @Test
    fun `title generation delegates local serialization to the Provider`() {
        val source = mainSource("com/newoether/agora/viewmodel/ConversationTitleGenerator.kt")

        assertFalse(source.contains("LocalModelRuntime"))
    }

    @Test
    fun `Provider runs the complete request inside the process runtime`() {
        val source = mainSource("com/newoether/agora/api/local/LocalProvider.kt")
        val admission = source.indexOf("LocalModelRuntime.runChat(")
        val template = source.indexOf("engine.applyTemplate")
        val generation = source.indexOf("tokenFlow.collect")

        assertTrue(admission >= 0)
        assertTrue(template > admission)
        assertTrue(generation > template)
        assertFalse(source.contains("currentEngine"))
        assertFalse(source.contains("releaseEngineBlocking"))
    }

    @Test
    fun `native mutation is exclusive while cancellation remains concurrent`() {
        val source = mainSource("com/newoether/agora/api/LlamaChatEngine.kt")

        listOf("loadMmproj", "unloadMmproj", "resetContext").forEach { functionName ->
            assertTrue(functionSection(source, functionName).contains("lock.writeLock().lock()"))
        }
        assertTrue(functionSection(source, "cancel").contains("lock.readLock().lock()"))
    }

    @Test
    fun `stream delivery blocks for capacity and native cancellation is atomic`() {
        val engine = mainSource("com/newoether/agora/api/LlamaChatEngine.kt")
        val native = mainCppSource("llama_chat_jni.cpp")

        assertTrue(engine.contains("trySendBlocking(LlamaGenerationEvent.Text(token)).isSuccess"))
        assertFalse(engine.contains("trySend(token)"))
        assertTrue(native.contains("std::atomic<bool> cancelled"))
        assertFalse(native.contains("volatile bool cancelled"))
    }

    @Test
    fun `chat templates use the official structured tool owner and fail closed by capability`() {
        val cmake = mainCppSource("CMakeLists.txt")
        val native = mainCppSource("llama_chat_jni.cpp")
        val engine = mainSource("com/newoether/agora/api/LlamaChatEngine.kt")
        val provider = mainSource("com/newoether/agora/api/local/LocalProvider.kt")

        assertTrue(cmake.contains("set(LLAMA_BUILD_COMMON ON CACHE BOOL \"\" FORCE)"))
        assertFalse(cmake.contains("add_subdirectory(\${LLAMA_CPP_DIR}/common"))
        assertTrue(cmake.contains("target_link_libraries(agora_llama llama llama-common"))
        assertTrue(native.contains("common_chat_templates_init(handle->model"))
        assertTrue(native.contains("common_chat_templates_was_explicit"))
        assertTrue(native.contains("common_chat_templates_apply("))
        assertTrue(native.contains("inputs.enable_thinking ="))
        assertTrue(native.contains("inputs.tool_choice = COMMON_CHAT_TOOL_CHOICE_AUTO"))
        assertTrue(native.contains("inputs.parallel_tool_calls = true"))
        assertTrue(native.contains("supports(\"supports_tools\")"))
        assertTrue(native.contains("supports(\"supports_tool_calls\")"))
        assertTrue(native.contains("!inputs.tools.empty() || has_tool_history"))
        assertFalse(native.contains("llama_chat_apply_template("))

        assertTrue(engine.contains("class LlamaChatTemplateRequest("))
        assertTrue(engine.contains("class LlamaChatTemplateResult("))
        assertTrue(engine.contains("class ChatTemplateToolCall("))
        assertTrue(engine.contains("class ChatTemplateTool("))
        assertTrue(provider.contains("TEMPLATE_JSON.encodeToString(tool.function.parameters)"))
        assertTrue(provider.contains("message.toolCalls.isNotEmpty() || message.role == \"tool\""))
        assertTrue(provider.contains("if (requiresToolCapableTemplate && !template.supportsTools)"))
        assertTrue(provider.contains("role = \"assistant\""))
        assertTrue(provider.contains("toolCalls = toolCalls.toTypedArray()"))
        assertTrue(provider.contains("role = \"tool\""))
        assertTrue(provider.contains("toolName = name"))
        assertTrue(provider.contains("toolCallId ="))
        assertFalse(provider.contains("Tool call:"))
        assertFalse(provider.contains("Tool result:"))
        assertTrue(provider.contains("enableThinking = config.thinkingEnabled"))
    }

    @Test
    fun `template grammar and penalties reach the shared native sampler`() {
        val provider = mainSource("com/newoether/agora/api/local/LocalProvider.kt")
        val engine = mainSource("com/newoether/agora/api/LlamaChatEngine.kt")
        val native = mainCppSource("llama_chat_jni.cpp")
        val sampler = native
            .substringAfter("static common_sampler * init_chat_sampler(")
            .substringBefore("static bool is_preserved_token(")

        assertEquals(2, Regex("frequencyPenalty = config\\.frequencyPenalty \\?: 0f")
            .findAll(provider).count())
        assertEquals(2, Regex("presencePenalty = config\\.presencePenalty \\?: 0f")
            .findAll(provider).count())
        assertEquals(4, Regex("frequencyPenalty: Float").findAll(engine).count())
        assertEquals(4, Regex("presencePenalty: Float").findAll(engine).count())
        assertTrue(native.contains("static constexpr int32_t PENALTY_LAST_N = 64;"))
        assertEquals(2, Regex("common_sampler \\* smpl = init_chat_sampler\\(")
            .findAll(native).count())
        assertTrue(sampler.contains("params.grammar = { COMMON_GRAMMAR_TYPE_TOOL_CALLS"))
        assertTrue(sampler.contains("params.grammar_lazy = metadata.grammar_lazy"))
        assertTrue(sampler.contains("params.generation_prompt = metadata.generation_prompt"))
        assertTrue(sampler.contains("common_tokenize(handle->vocab, value, false, true)"))
        assertTrue(sampler.contains("COMMON_GRAMMAR_TRIGGER_TYPE_WORD"))
        assertTrue(sampler.contains("COMMON_GRAMMAR_TRIGGER_TYPE_TOKEN"))
        assertTrue(sampler.contains("Grammar trigger token is not preserved"))
        assertTrue(sampler.contains("Lazy grammar requires at least one trigger"))
        val penalties = sampler.indexOf("COMMON_SAMPLER_TYPE_PENALTIES")
        val minP = sampler.indexOf("COMMON_SAMPLER_TYPE_MIN_P")
        val topP = sampler.indexOf("COMMON_SAMPLER_TYPE_TOP_P")
        val temperature = sampler.indexOf("COMMON_SAMPLER_TYPE_TEMPERATURE")
        assertTrue(penalties >= 0 && penalties < minP)
        assertTrue(minP < topP && topP < temperature)
        assertFalse(native.contains("llama_sampler_init_penalties("))
        assertFalse(native.contains("llama_sampler_chain_init("))
    }

    @Test
    fun `text and multimodal loops accept template sampling before lossless delivery`() {
        val native = mainCppSource("llama_chat_jni.cpp")

        assertTrue(native.contains("CALLBACK_TOKEN_BATCH = 4"))
        assertTrue(native.contains("CALLBACK_BYTE_BATCH = 64"))
        listOf("nativeChatGenerate", "nativeChatGenerateWithImages").forEach { functionName ->
            val function = nativeFunctionSection(native, functionName)
            val loop = function.substringAfter("while (generated < generation_limit)")
            val cancellation = loop.indexOf("cancelled.load")
            val sample = loop.indexOf("common_sampler_sample")
            val accept = loop.indexOf("common_sampler_accept")
            val eog = loop.indexOf("llama_vocab_is_eog")
            val piece = loop.indexOf("token_to_piece(handle->vocab")
            val decode = loop.indexOf("llama_decode")
            val count = loop.indexOf("generated++")
            val deliver = loop.indexOf("report_token")

            assertTrue(cancellation >= 0 && cancellation < sample)
            assertTrue(sample >= 0 && sample < accept)
            assertTrue(accept < eog)
            assertTrue(eog < piece)
            assertTrue(loop.contains("!is_preserved_token(metadata, new_token_id)"))
            assertTrue(piece >= 0 && piece < decode)
            assertTrue(decode < count)
            assertTrue(count < deliver)
            assertTrue(function.contains("common_sampler_free(smpl);"))
            assertTrue(loop.contains("callback_tokens >= CALLBACK_TOKEN_BATCH"))
            assertTrue(loop.contains("callback_buffer.size() >= CALLBACK_BYTE_BATCH"))
            assertTrue(loop.contains("std::min(callback_buffer.size(), CALLBACK_BYTE_BATCH)"))
            assertTrue(loop.contains("callback_buffer[emit_len]) & 0xC0) == 0x80"))
            assertTrue(loop.contains("!consumer_closed && !callback_buffer.empty()"))
            assertTrue(loop.contains("failure = \"Stream consumer closed\""))
            assertTrue(loop.contains("if (consumer_closed) break"))
            assertTrue(loop.contains("Generated incomplete UTF-8 output"))
            assertFalse(loop.contains("llama_synchronize"))
            assertFalse(loop.contains("char piece[256]"))
        }
        assertFalse(native.contains("llama_sampler_sample("))
        assertFalse(native.contains("llama_sampler_free("))
    }

    @Test
    fun `native performance logs contain counts and timings but no private paths`() {
        val native = mainCppSource("llama_chat_jni.cpp")

        assertTrue(native.contains("Chat load: model_ms="))
        assertTrue(native.contains("Text prefill: input_tokens="))
        assertTrue(native.contains("Text decode: output_tokens="))
        assertTrue(native.contains("Multimodal prefill: input_tokens="))
        assertTrue(native.contains("Multimodal decode: output_tokens="))
        assertFalse(native.contains("Failed to load image: %s"))
    }

    @Test
    fun `native stop reason mapping is closed`() {
        LlamaGenerationStopReason.entries.forEach { reason ->
            assertEquals(reason, LlamaGenerationStopReason.fromNative(reason.nativeValue))
        }
        assertNull(LlamaGenerationStopReason.fromNative("unknown"))
    }

    @Test
    fun `typed generation events stay module internal`() {
        val engine = mainSource("com/newoether/agora/api/LlamaChatEngine.kt")

        assertTrue(engine.contains("internal fun generate("))
        assertTrue(engine.contains("internal fun generateWithImages("))
    }

    @Test
    fun `runtime unloads before switching identity and isolates embeddings`() {
        val runtime = mainSource("com/newoether/agora/api/LocalModelRuntime.kt")
        val embeddingNative = mainCppSource("llama_jni.cpp")
        val chatSwitch = runtime.indexOf("unloadResident()")
        val chatLoad = runtime.indexOf("LlamaChatEngine(identity.canonicalPath, identity.nCtx)")
        val chatFailure = runtime.indexOf("if (!loaded.load())", chatLoad)
        val chatInstall = runtime.indexOf("resident = Resident.Chat", chatFailure)
        val embeddingSwitch = runtime.indexOf("unloadResident()", chatSwitch + 1)
        val embeddingLoad = runtime.indexOf("LlamaEngine.loadResident", embeddingSwitch)

        assertTrue(runtime.contains("data class Chat("))
        assertTrue(runtime.contains("val nCtx: Int"))
        assertTrue(runtime.contains("data class Embedding("))
        assertTrue(chatSwitch >= 0 && chatLoad > chatSwitch)
        assertTrue(chatFailure > chatLoad && chatInstall > chatFailure)
        assertTrue(runtime.substring(chatFailure, chatInstall).contains("return@run false"))
        assertTrue(embeddingSwitch >= 0 && embeddingLoad > embeddingSwitch)
        assertTrue(runtime.contains("activeChatEngine = engine"))
        assertTrue(runtime.contains("activeChatEngine = null"))
        assertTrue(runtime.contains("activeChatEngine?.cancel()"))
        assertTrue(embeddingNative.contains(
            "llama_memory_clear(llama_get_memory(handle->ctx), true);"
        ))
    }

    @Test
    fun `idle offload is generation safe and uses the canonical permit`() {
        val runtime = mainSource("com/newoether/agora/api/LocalModelRuntime.kt")
        val queue = runtime.substringAfter("internal class LocalModelTaskQueue(")
            .substringBefore("internal object LocalModelRuntime")

        assertTrue(queue.contains("submittedTasks++"))
        assertTrue(queue.contains("submittedTasks--"))
        assertTrue(queue.contains("if (submittedTasks == 0) onQueueIdle()"))
        assertTrue(queue.contains("suspend fun runIfIdle"))
        assertTrue(queue.contains("permit.withPermit"))
        assertTrue(runtime.contains("onTaskArrived = ::cancelIdleDeadline"))
        assertTrue(runtime.contains("onQueueIdle = ::startIdleDeadline"))
        assertTrue(runtime.contains("tasks.signalIdleIfEmpty()"))
        assertTrue(runtime.contains("if (delayMillis > 0) delay(delayMillis)"))
        assertTrue(runtime.contains("tasks.runIfIdle"))
        assertTrue(runtime.contains("if (epoch != idleEpoch) return@runIfIdle"))
        val epochCheck = runtime.indexOf("if (epoch != idleEpoch)")
        assertTrue(runtime.indexOf("unloadResident()", epochCheck) > epochCheck)
    }

    @Test
    fun `idle retention is bound once and remains device local`() {
        val appContainer = mainSource("com/newoether/agora/di/AppContainer.kt")
        val settingsPage = mainSource(
            "com/newoether/agora/ui/settings/SettingsProviderDetailPage.kt",
        )
        val portable = mainSource("com/newoether/agora/data/PortableSettingsArchive.kt")
        val settingsManager = mainSource("com/newoether/agora/data/SettingsManager.kt")
        val portableReset = settingsManager
            .substringAfter("suspend fun resetPortableSettingsForImport()")
            .substringBefore("suspend fun invalidatePortableModelCaches")
        val localModelsGroup = settingsPage.indexOf("R.string.local_models_title")
        val advancedGroup = settingsPage.indexOf("R.string.advanced_title", localModelsGroup)

        assertEquals(
            1,
            Regex.escape("LocalModelRuntime.bindIdleRetention(").toRegex()
                .findAll(appContainer).count(),
        )
        assertTrue(appContainer.contains("it.localModelIdleRetentionMinutes, appScope"))
        assertTrue(localModelsGroup >= 0 && advancedGroup > localModelsGroup)
        assertTrue(settingsPage.contains("LOCAL_MODEL_IDLE_RETENTION_PRESETS"))
        assertTrue(settingsPage.contains("PersistedSliderFeedbackGate"))
        assertFalse(portable.contains("localModelIdleRetentionMinutes"))
        assertFalse(portable.contains("local_model_idle_retention_minutes"))
        assertFalse(portableReset.contains("LOCAL_MODEL_IDLE_RETENTION_MINUTES"))
    }

    @Test
    fun `projector is image gated and reused by path`() {
        val provider = mainSource("com/newoether/agora/api/local/LocalProvider.kt")
        val engine = mainSource("com/newoether/agora/api/LlamaChatEngine.kt")
        val projectorLoad = functionSection(engine, "loadMmproj")

        assertTrue(provider.contains("if (hasImages)"))
        assertTrue(provider.contains("engine.loadMmproj(modelConfig.mmprojPath)"))
        assertTrue(projectorLoad.contains("loadedMmprojPath == mmprojPath"))
        assertTrue(projectorLoad.indexOf("loadedMmprojPath == mmprojPath") <
            projectorLoad.indexOf("nativeChatLoadMmproj"))
    }

    @Test
    fun `local context and settings cannot promise an impossible output`() {
        val provider = mainSource("com/newoether/agora/api/local/LocalProvider.kt")
        val settings = mainSource("com/newoether/agora/ui/settings/SettingsProviderDetailPage.kt")
        val onboarding = mainSource("com/newoether/agora/ui/onboarding/WelcomeScreen.kt")
        val native = mainCppSource("llama_chat_jni.cpp")
        val legacyDefaults = LocalChatModelConfig(modelId = "model", alias = "Model")

        assertEquals(2048, legacyDefaults.nCtx)
        assertEquals(4096, legacyDefaults.maxTokens)
        assertTrue(settings.contains("var nCtx by remember { mutableStateOf(\"4096\") }"))
        assertTrue(settings.contains("mutableStateOf(\"1024\")"))
        assertTrue(onboarding.contains("nCtx = 4096"))
        assertTrue(onboarding.contains("maxTokens = 1024"))
        assertEquals(2, "Max tokens must not exceed context size".toRegex()
            .findAll(settings).count())
        assertTrue(provider.contains(
            "minOf(config.maxContextWindow, modelConfig.nCtx).coerceAtLeast(1)"
        ))
        assertFalse(provider.contains("?: buildPrompt(templateMessages)"))
        listOf("nativeChatGenerate", "nativeChatGenerateWithImages").forEach { functionName ->
            val function = nativeFunctionSection(native, functionName)
            assertTrue(function.contains("generation_limit = std::min(max_tokens, remaining_context)"))
            assertTrue(function.contains("context_limited ? \"context_full\" : \"max_tokens\""))
        }
    }

    private fun functionSection(source: String, functionName: String): String = source
        .substringAfter("fun $functionName(")
        .substringBefore("\n    fun ")

    private fun nativeFunctionSection(source: String, functionName: String): String = source
        .substringAfter("Java_com_newoether_agora_api_LlamaChatEngine_$functionName(")
        .substringBefore("\nJNIEXPORT")

    private fun mainSource(relativePath: String): String = locateSourceRoot("java")
        .resolve(relativePath)
        .readText()

    private fun mainCppSource(fileName: String): String = locateSourceRoot("cpp")
        .resolve(fileName)
        .readText()

    private fun locateSourceRoot(kind: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/src/main/$kind"),
                File(directory, "src/main/$kind"),
            ).firstOrNull(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate the main $kind source directory")
    }
}
