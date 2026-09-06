package com.newoether.agora.remote

import com.newoether.agora.model.Participant
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.io.IOException
import kotlinx.serialization.SerializationException

class FiloClientTest {
    private val token = "a".repeat(64)
    private val id = "00000000-0000-0000-0000-000000000001"

    @Test fun nativeProjectionKeepsIdentityAndReplacesEditedTail() {
        val user = RemoteMessage("u", "turn", "client", "user", "hello", 10)
        val answer = RemoteMessage("a", "turn", null, "assistant", "old", 10)
        val edited = answer.copy(text = "new")
        val messages = projectRemoteMessages(mergeRemoteHistory(listOf(user, answer), listOf(edited)))
        assertEquals(listOf("u", "a"), messages.map { it.id })
        assertEquals("u", messages.last().parentId)
        assertEquals("turn", messages.last().runId)
        assertEquals(Participant.MODEL, messages.last().participant)
        assertEquals("new", messages.last().text)
    }

    @Test fun malformedEndpointOrTokenIsRejectedBeforeNetwork() {
        listOf("http://user:secret@localhost/", "http://localhost/?token=x", "http://localhost/path").forEach {
            assertThrows(IllegalArgumentException::class.java) { FiloClient(it, token) }
        }
        assertThrows(IllegalArgumentException::class.java) { FiloClient("http://localhost/", "short") }
    }

    @Test fun failuresDistinguishTransportAuthenticationAndProtocol() {
        assertEquals(RemoteFailure.NETWORK, classifyRemoteFailure(IOException("unreachable")))
        assertEquals(RemoteFailure.AUTHENTICATION, classifyRemoteFailure(FiloHttpException(401)))
        assertEquals(RemoteFailure.AUTHENTICATION, classifyRemoteFailure(FiloHttpException(403)))
        assertEquals(RemoteFailure.SERVICE, classifyRemoteFailure(FiloHttpException(502)))
        assertEquals(RemoteFailure.PROTOCOL, classifyRemoteFailure(SerializationException("payload")))
        assertEquals(RemoteFailure.PROTOCOL, classifyRemoteFailure(IllegalArgumentException("incompatible")))
        assertEquals(RemoteFailure.STORAGE, classifyRemoteFailure(RemoteStorageException()))
        val invalid = assertThrows(FiloConfigurationException::class.java) { FiloClient("broken", token) }
        assertEquals(RemoteFailure.CONFIGURATION, classifyRemoteFailure(invalid))
    }

    @Test fun authenticatedQueueSendUsesExactSessionAndDoesNotFollowRedirects() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = AtomicInteger()
        var auth = ""
        var path = ""
        var body = ""
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            auth = exchange.requestHeaders.getFirst("Authorization")
            path = exchange.requestURI.path
            body = exchange.requestBody.reader().readText()
            exchange.responseHeaders.add("Location", "/must-not-retry")
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
        }
        server.start()
        try {
            val client = FiloClient("http://127.0.0.1:${server.address.port}/", token)
            try { client.send(id, "hello", id); fail("Redirect must fail") }
            catch (error: FiloHttpException) { assertEquals(307, error.status) }
            assertEquals(1, requests.get())
            assertEquals("Bearer $token", auth)
            assertEquals("/v1/sessions/$id/messages", path)
            assertTrue(body.contains("\"text\":\"hello\""))
            assertTrue(body.contains("\"clientId\":\"$id\""))
        } finally { server.stop(0) }
    }
}
