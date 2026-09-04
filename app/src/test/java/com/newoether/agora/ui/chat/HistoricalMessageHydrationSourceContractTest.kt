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
}
