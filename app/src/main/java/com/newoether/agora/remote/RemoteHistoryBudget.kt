package com.newoether.agora.remote

import java.io.IOException

internal class RemoteContentLimitException : IOException("Remote history exceeds the device display limit")

/** Bound retained history as well as individual frames; check before publishing projection copies. */
internal fun checkedRemoteHistory(messages: List<RemoteMessage>): List<RemoteMessage> {
    if (messages.size > 8192) throw RemoteContentLimitException()
    var bytes = 0L
    for (message in messages) {
        bytes += 2L * (message.text.length.toLong() + (message.activity?.arguments?.length ?: 0) +
            (message.activity?.result?.length ?: 0)) + 256L
        if (bytes > 16L * 1024 * 1024) throw RemoteContentLimitException()
    }
    return messages
}

internal fun RemoteRuntime?.hasVisibleGeneration(messages: List<RemoteMessage>): Boolean =
    this?.isRunning == true && activeTurnId != null && (activeTurnHasUserMessage ||
        messages.any { it.role == "user" && it.turnId == activeTurnId })
