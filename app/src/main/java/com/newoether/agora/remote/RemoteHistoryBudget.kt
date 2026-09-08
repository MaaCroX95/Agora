package com.newoether.agora.remote

import java.io.IOException

internal class RemoteContentLimitException : IOException("Filo response exceeds the transport limit")

internal fun RemoteRuntime?.hasVisibleGeneration(messages: List<RemoteMessage>): Boolean =
    this?.isRunning == true && activeTurnId != null && (activeTurnHasUserMessage ||
        messages.any { it.role == "user" && it.turnId == activeTurnId })
