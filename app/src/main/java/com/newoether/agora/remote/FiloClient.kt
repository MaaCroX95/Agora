package com.newoether.agora.remote

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
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
private data class RemoteSessionStatuses(val statuses: List<RemoteSessionStatus>)
@Serializable
private data class RemoteModels(val models: List<RemoteModel>)
@Serializable
private data class FiloInfo(
    val protocolVersion: Int, val agent: String, val sessionMode: String,
    val messageDelivery: String, val outputMode: String, val device: String,
)
@Serializable
private data class SendInput(val text: String, val clientId: String)
@Serializable
private data class SendResult(val turnId: String, val clientId: String)

@Serializable
private data class FiloError(val code: String? = null)

internal class FiloHttpException(val status: Int, val code: String? = null) : IOException("Filo HTTP $status")
internal class FiloInputException : IllegalArgumentException("Invalid Filo message")
internal class FiloConfigurationException : IllegalArgumentException("Invalid Filo connection")
internal enum class RemoteFailure { NETWORK, AUTHENTICATION, CONFIGURATION, PROTOCOL, SERVICE, STORAGE, SESSION_BUSY, CONTENT_TOO_LARGE, UNKNOWN }

internal fun classifyRemoteFailure(error: Exception): RemoteFailure = when (error) {
    is RemoteContentLimitException -> RemoteFailure.CONTENT_TOO_LARGE
    is RemoteStorageException -> RemoteFailure.STORAGE
    is FiloConfigurationException, is FiloInputException -> RemoteFailure.CONFIGURATION
    is FiloHttpException -> when {
        error.status == 409 && error.code == "session_busy" -> RemoteFailure.SESSION_BUSY
        error.status == 401 || error.status == 403 -> RemoteFailure.AUTHENTICATION
        else -> RemoteFailure.SERVICE
    }
    is IllegalArgumentException -> RemoteFailure.PROTOCOL
    is IOException -> RemoteFailure.NETWORK
    else -> RemoteFailure.UNKNOWN
}

/** A dedicated transport: credentials, redirects and retries never enter the provider client. */
internal class FiloClient(
    address: String,
    private val token: String,
    private val calls: Call.Factory = OkHttpClient.Builder()
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS).build(),
) {
    private val endpoint = try { address.trim().toHttpUrl().also {
        require(it.username.isEmpty() && it.password.isEmpty() && it.query == null &&
            it.fragment == null && it.encodedPath == "/")
        require(token.matches(Regex("[a-fA-F0-9]{64}")))
    } } catch (_: IllegalArgumentException) { throw FiloConfigurationException() }
    val address: String get() = endpoint.toString()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun connect(): String {
        val info = json.decodeFromString<FiloInfo>(request("v1/info"))
        require(info.protocolVersion == 2 && info.agent == "codex" &&
            info.sessionMode in setOf("existing", "standalone") && info.messageDelivery == "native-steer" &&
            info.outputMode == "live-messages") { "Incompatible Filo service" }
        return info.device
    }

    suspend fun sessions(cursor: String? = null): RemoteSessionPage = withContext(Dispatchers.Default) {
        json.decodeFromString(request("v1/sessions", cursor))
    }

    suspend fun sessionStatuses(ids: List<String>): List<RemoteSessionStatus> = withContext(Dispatchers.Default) {
        require(ids.isNotEmpty() && ids.size <= 12 && ids.distinct().size == ids.size)
        val result = json.decodeFromString<RemoteSessionStatuses>(
            request("v1/sessions/status", sessionIds = ids.map(::sessionId)),
        ).statuses
        require(result.size == ids.size && result.map { it.id }.toSet() == ids.toSet())
        result
    }

    suspend fun conversation(id: String, cursor: String? = null): RemoteConversationPage = withContext(Dispatchers.Default) {
        decodePage(
            request("v1/sessions/${sessionId(id)}", cursor, includeActivity = true),
        )
    }

    private fun decodePage(text: String): RemoteConversationPage =
        json.decodeFromString<RemoteConversationPage>(text).also { page ->
            require(page.messages.all { it.role == "user" || it.role == "assistant" })
            require(page.messages.map { it.id }.toSet().size == page.messages.size)
            page.messages.forEach { message -> message.activity?.let { activity ->
                require(message.role == "assistant" && activity.type in setOf("thought", "tool"))
                require(activity.type != "tool" || !activity.toolName.isNullOrBlank())
                require(activity.durationMs == null || activity.durationMs >= 0)
                require(activity.state == null || activity.state in setOf("running", "succeeded", "failed", "stopped"))
            } }
        }

    fun events(id: String): Flow<RemoteConversationPage> = callbackFlow {
        val request = Request.Builder().url(endpoint.newBuilder()
            .addPathSegments("v1/sessions/${sessionId(id)}/events").build())
            .header("Authorization", "Bearer $token").build()
        val call = calls.newCall(request)
        call.timeout().clearTimeout()
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { close(e) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!it.isSuccessful) throw FiloHttpException(it.code)
                        val source = it.body.source()
                        while (!call.isCanceled()) {
                            val line = source.readRemoteEventLine() ?: break
                            if (line == "event: error") throw IOException("Filo stream failed")
                            if (line.startsWith("data: ")) trySend(decodePage(line.removePrefix("data: ")))
                        }
                        close(IOException("Filo stream closed"))
                    } catch (error: Exception) { close(error) }
                }
            }
        })
        awaitClose { call.cancel() }
    }.buffer(Channel.CONFLATED)

    suspend fun resume(id: String) { request("v1/sessions/${sessionId(id)}/resume", body = "{}") }
    suspend fun create(): RemoteSession = json.decodeFromString(request("v1/sessions", body = "{}"))
    suspend fun models(): List<RemoteModel> = json.decodeFromString<RemoteModels>(request("v1/models")).models
    suspend fun setModel(id: String, model: String) {
        request("v1/sessions/${sessionId(id)}/model", body = json.encodeToString(mapOf("model" to model)))
    }
    suspend fun updateSettings(id: String, settings: RemoteSettings) {
        request("v1/sessions/${sessionId(id)}/settings", body = settings.body())
    }
    suspend fun stop(id: String, turnId: String) {
        request("v1/sessions/${sessionId(id)}/stop", body = json.encodeToString(mapOf("turnId" to turnId)))
    }

    suspend fun send(id: String, text: String, clientId: String): String {
        val body = json.encodeToString(SendInput(text, clientId))
        if (text.isBlank() || body.toByteArray(Charsets.UTF_8).size > 65536) throw FiloInputException()
        return json.decodeFromString<SendResult>(
            request("v1/sessions/${sessionId(id)}/messages", body = body),
        ).also { require(it.clientId == clientId && it.turnId.isNotBlank()) }.turnId
    }

    private fun sessionId(id: String): String {
        require(id.matches(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")))
        return id
    }

    private suspend fun request(
        path: String, cursor: String? = null, body: String? = null, includeActivity: Boolean = false,
        sessionIds: List<String>? = null,
    ): String =
        suspendCancellableCoroutine { continuation ->
            val url = endpoint.newBuilder().addPathSegments(path).apply {
                cursor?.let { addQueryParameter("cursor", it) }
                sessionIds?.let { addQueryParameter("ids", it.joinToString(",")) }
                if (includeActivity) addQueryParameter("includeActivity", "true")
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
                            val text = it.body.source().readRemoteResponse()
                            if (!it.isSuccessful) throw FiloHttpException(it.code,
                                runCatching { json.decodeFromString<FiloError>(text).code }.getOrNull())
                            if (!continuation.isCancelled) continuation.resume(text)
                        } catch (error: Exception) {
                            if (!continuation.isCancelled) continuation.resumeWithException(error)
                        }
                    }
                }
            })
        }
}
