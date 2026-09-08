package com.newoether.agora.remote

import com.newoether.agora.model.MessageStatus
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RemoteTopologyTest {
    private val revision = "a".repeat(64)
    private fun node(id: String, role: String = "assistant", turn: String = "turn") =
        RemoteMessageNode(id, turn, null, role, 1, revision, 100, groupId = if (role == "assistant") "group-$turn" else null)

    @Test fun largeTopologyHasStableBubbleKeysAndNoBodiesBeforeOrAfterPayloadRevisionChanges() {
        val nodes = (0..1000).flatMap { index ->
            listOf(node("u$index", "user", "t$index"), node("a$index", turn = "t$index"))
        }
        val before = projectRemoteTopology(nodes, RemoteRuntime("idle"))
        val changed = nodes.dropLast(1) + nodes.last().copy(revision = "b".repeat(64), textLength = 10000)
        val after = projectRemoteTopology(changed, RemoteRuntime("idle"))
        assertEquals(before.map { it.stub }, after.map { it.stub })
        assertEquals(2002, after.size)
        assertTrue(after.all { it.stub.text.isEmpty() && it.stub.segments == null })
        assertNotEquals(before.last().revision, after.last().revision)
    }

    @Test fun transportPartsDoNotBecomeExtraBubblesOrAlterNativeToolCount() {
        val user = node("u", "user")
        val first = node("answer").copy(nativeId = "answer", textContinues = true)
        val part = first.copy(id = "filo-part:answer:16384", textOffset = 16384, textContinues = false)
        val tool = node("tool").copy(textLength = 0, activity = RemoteNodeActivity("tool", "succeeded"))
        val groups = projectRemoteTopology(listOf(user, first, part, tool), RemoteRuntime("idle"))
        assertEquals(listOf("u", "group-turn"), groups.map { it.stub.id })
        assertEquals(1, groups.last().nodes.count { it.activity?.type == "tool" })
        assertEquals(3, groups.last().requests.size)
    }

    @Test fun onlyTheNativeAcknowledgedActiveTailReceivesGenerationStatus() {
        val active = RemoteRuntime("active", "turn")
        assertTrue(projectRemoteTopology(emptyList(), active).isEmpty())
        val nodes = listOf(node("old", turn = "old"), node("u", "user"), node("thought").copy(activity = RemoteNodeActivity("thought")))
        val result = projectRemoteTopology(nodes, active)
        assertEquals(MessageStatus.SUCCESS, result.first().stub.status)
        assertEquals(MessageStatus.THINKING, result.last().stub.status)
        val answer = node("answer")
        assertEquals(MessageStatus.SENDING, projectRemoteTopology(nodes + answer, active).last().stub.status)
    }

    @Test fun networkTopologyDoesNotRequestPayloadUntilExplicitHydrationAndSseUsesMetadataView() = runBlocking {
        val session = "00000000-0000-0000-0000-000000000001"
        val topology = RemoteTopologyPage(listOf(node("a")), null, emptyList(), RemoteRuntime("idle"))
        val message = RemoteMessage("a", "turn", null, "assistant", "body", 1)
        val paths = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            assertEquals("Bearer ${"a".repeat(64)}", exchange.requestHeaders.getFirst("Authorization"))
            paths += exchange.requestURI.toString()
            val text = when {
                exchange.requestURI.path.endsWith("/topology") -> Json.encodeToString(topology)
                exchange.requestURI.path.endsWith("/payloads") -> Json.encodeToString(RemotePayloadResponse(listOf(message)))
                else -> "data: ${Json.encodeToString(topology)}\n\n"
            }.toByteArray()
            exchange.sendResponseHeaders(200, text.size.toLong())
            exchange.responseBody.use { it.write(text) }
        }
        server.start()
        try {
            val client = FiloClient("http://127.0.0.1:${server.address.port}/", "a".repeat(64))
            assertEquals(topology, client.topology(session))
            assertEquals(1, paths.size)
            assertFalse(paths.any { "payloads" in it })
            assertEquals(listOf(message), client.payloads(session, listOf(RemotePayloadRequest("a", revision))))
            assertEquals(topology, client.topologyEvents(session).first())
            assertTrue(paths.last().endsWith("events?view=topology"))
        } finally { server.stop(0) }
    }
}
