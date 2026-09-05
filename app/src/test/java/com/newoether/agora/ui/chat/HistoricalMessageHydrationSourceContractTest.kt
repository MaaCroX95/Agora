package com.newoether.agora.ui.chat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalMessageHydrationSourceContractTest {
    @Test
    fun historicalRowsUseOneBoundedBackgroundProjectionAndVisibleStateOwner() {
        val start = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        val root = generateSequence(start) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        fun source(path: String) = File(root, path).readText().replace("\r\n", "\n")
        val hydration = source("app/src/main/java/com/newoether/agora/viewmodel/ConversationMessagePayloadHydration.kt")
        val gate = source("app/src/main/java/com/newoether/agora/ui/chat/HistoricalMessageHydration.kt")
        val list = source("app/src/main/java/com/newoether/agora/ui/chat/MessageList.kt")
        val projection = source("app/src/main/java/com/newoether/agora/viewmodel/UiMessageProjection.kt")

        assertTrue(hydration.contains("MessagePayloadProjector(projectionDispatcher)") &&
            hydration.contains("requestedIds.mapNotNull(entitiesById::get)"))
        assertTrue(gate.contains("produceState(") && gate.contains("Crossfade(") &&
            gate.contains("heightIn(min = 72.dp)") && gate.contains("onRetry"))
        assertTrue(list.contains("HistoricalMessageHydrationCrossfade(") &&
            !list.contains("collectAsState(initial = cachedMessage)"))
        assertTrue(projection.contains("fallbackSegments.withDurableModelAnswer"))
    }

    @Test
    fun historicalRowsExposePayloadBeforeMarkdownSettlementAndKeepViewportAnchored() {
        val start = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        val root = generateSequence(start) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        fun source(path: String) = File(root, path).readText().replace("\r\n", "\n")
        val list = source("app/src/main/java/com/newoether/agora/ui/chat/MessageList.kt")
        val settlement = source(
            "app/src/main/java/com/newoether/agora/ui/chat/HistoricalMessageViewportSettlement.kt",
        )
        val item = source("app/src/main/java/com/newoether/agora/ui/chat/message/MessageItem.kt")
        val assistant = source(
            "app/src/main/java/com/newoether/agora/ui/chat/message/AssistantMessageContent.kt",
        )
        val streaming = source(
            "app/src/main/java/com/newoether/agora/ui/chat/message/StreamingMarkdownMessage.kt",
        )
        val incremental = source(
            "app/src/main/java/com/newoether/agora/ui/chat/message/IncrementalStreamingMarkdown.kt",
        )
        val payloadReadyIndex =
            list.indexOf("onMessageHydrated(conversationId, messageStub.id)")
        val renderSettlementIndex =
            list.indexOf("rememberHistoricalMessageViewportSettlement(")
        assertTrue(list.contains("historicalMessagePayloadReady("))
        assertTrue(payloadReadyIndex >= 0 && renderSettlementIndex > payloadReadyIndex)
        assertTrue(!settlement.contains("onMessageHydrated"))
        assertTrue(settlement.contains("delay(HISTORICAL_MESSAGE_CROSSFADE_MS.toLong())"))
        assertTrue(settlement.contains("withFrameNanos { }"))
        assertTrue(list.contains("onRenderReady = {") &&
            list.contains("finishHydrationAfterRenderedContent()"))
        assertTrue(item.contains("onContentReady = onRenderReady"))
        assertTrue(assistant.contains("AssistantRenderReadiness(asynchronousAnswerKeys)"))
        assertTrue(assistant.contains("onAnswerReady = markAnswerReady"))
        assertTrue(streaming.contains("onReady = onReady"))
        assertTrue(incremental.contains("MarkdownTextContent(") &&
            incremental.contains("onReady = onReady"))
        assertTrue(incremental.contains("snapshot.inputContent == content"))
    }
}
