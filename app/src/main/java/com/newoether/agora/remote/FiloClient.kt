package com.newoether.agora.remote

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
internal data class RemoteSession(val id: String, val title: String, val cwd: String, val updatedAt: Long)
@Serializable
internal data class RemoteMessage(
    val id: String, val turnId: String, val clientId: String?, val role: String,
    val text: String, val timestamp: Long,
)
@Serializable
internal data class RemoteQueuedMessage(val id: String, val clientId: String, val text: String)
@Serializable
internal data class RemoteSessionPage(val sessions: List<RemoteSession>, val nextCursor: String?)
@Serializable
internal data class RemoteConversationPage(
    val messages: List<RemoteMessage>, val nextCursor: String?, val queued: List<RemoteQueuedMessage>,
)
@Serializable
private data class FiloInfo(
    val protocolVersion: Int, val agent: String, val sessionMode: String,
    val messageDelivery: String, val outputMode: String, val device: String,
)
@Serializable
private data class SendInput(val text: String, val clientId: String)
@Serializable
private data class SendResult(val queueId: String)

internal class FiloHttpException(val status: Int) : IOException("Filo HTTP $status")

/** A dedicated transport: credentials, redirects and retries never enter the provider client. */
internal class FiloClient(
    address: String,
    private val token: String,
    private val calls: Call.Factory = OkHttpClient.Builder()
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS).build(),
) {
    private val endpoint = address.trim().toHttpUrl().also {
        require(it.username.isEmpty() && it.password.isEmpty() && it.query == null &&
            it.fragment == null && it.encodedPath == "/")
        require(token.matches(Regex("[a-fA-F0-9]{64}")))
    }
    val address: String get() = endpoint.toString()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun connect(): String {
        val info = json.decodeFromString<FiloInfo>(request("v1/info"))
        require(info.protocolVersion == 1 && info.agent == "codex" &&
            info.sessionMode == "existing" && info.messageDelivery == "native-queue" &&
            info.outputMode == "persisted-messages") { "Incompatible Filo service" }
        return info.device
    }

    suspend fun sessions(cursor: String? = null): RemoteSessionPage =
        json.decodeFromString(request("v1/sessions", cursor))

    suspend fun conversation(id: String, cursor: String? = null): RemoteConversationPage =
        json.decodeFromString(request("v1/sessions/${sessionId(id)}", cursor))

    suspend fun send(id: String, text: String, clientId: String): String {
        val body = json.encodeToString(SendInput(text, clientId))
        require(text.isNotBlank() && body.toByteArray(Charsets.UTF_8).size <= 65536)
        return json.decodeFromString<SendResult>(
            request("v1/sessions/${sessionId(id)}/messages", body = body),
        ).queueId
    }

    private fun sessionId(id: String): String {
        require(id.matches(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")))
        return id
    }

    private suspend fun request(path: String, cursor: String? = null, body: String? = null): String =
        suspendCancellableCoroutine { continuation ->
            val url = endpoint.newBuilder().addPathSegments(path).apply {
                cursor?.let { addQueryParameter("cursor", it) }
            }.build()
            val request = Request.Builder().url(url).header("Authorization", "Bearer $token")
                .apply { body?.let { post(it.toRequestBody("application/json".toMediaType())) } }.build()
            val call = calls.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (!it.isSuccessful) throw FiloHttpException(it.code)
                            val text = it.body.string()
                            if (!continuation.isCancelled) continuation.resume(text)
                        } catch (error: Exception) {
                            if (!continuation.isCancelled) continuation.resumeWithException(error)
                        }
                    }
                }
            })
        }
}

internal fun projectRemoteMessages(messages: List<RemoteMessage>): List<ChatMessage> =
    messages.mapIndexed { index, message ->
        require(message.role == "user" || message.role == "assistant")
        ChatMessage(
            id = message.id, parentId = messages.getOrNull(index - 1)?.id,
            text = message.text, participant = if (message.role == "user") Participant.USER else Participant.MODEL,
            timestamp = message.timestamp, modelName = "Codex", runId = message.turnId,
        )
    }

/** The newest native page replaces the cached tail, including native edits/removals. */
internal fun mergeRemoteHistory(old: List<RemoteMessage>, fresh: List<RemoteMessage>): List<RemoteMessage> {
    val boundary = fresh.firstOrNull()?.id ?: return emptyList()
    val index = old.indexOfFirst { it.id == boundary }
    return (old.take(index.coerceAtLeast(0)) + fresh).distinctBy { it.id }
}
