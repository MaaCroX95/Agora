package com.newoether.agora.remote

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class FiloStreamFailureTest {
    @Test fun serverErrorEventIsAServiceFailureWhilePrematureEofIsNetworkFailure() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var body = "event: error\ndata: {}\n\n"
        server.createContext("/") { exchange ->
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = FiloClient("http://127.0.0.1:${server.address.port}/", "a".repeat(64))
            for (expected in listOf(RemoteFailure.SERVICE, RemoteFailure.NETWORK)) {
                try {
                    client.events("00000000-0000-0000-0000-000000000001").first()
                    fail("An unsuccessful stream cannot publish a page")
                } catch (error: Exception) { assertEquals(expected, classifyRemoteFailure(error)) }
                body = ": heartbeat\n\n"
            }
        } finally { server.stop(0) }
    }
}
