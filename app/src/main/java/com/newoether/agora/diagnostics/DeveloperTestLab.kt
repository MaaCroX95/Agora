package com.newoether.agora.diagnostics

data class DeveloperTestResult(
    val id: String,
    val passed: Boolean,
    val detail: String,
)

/** Deterministic, offline fixtures. They never read credentials, network state, or user data. */
object DeveloperTestLab {
    fun runAll(): List<DeveloperTestResult> = listOf(
        run("credential_redaction") {
            val headers = DiagnosticRedactor.captureHeaders(
                mapOf("Authorization" to "Bearer fixture-secret-token"),
            )
            val url = DiagnosticRedactor.captureUrl(
                "https://example.invalid/chat?key=fixture-query-secret",
            )
            headers.values.none { it.contains("fixture-secret-token") } &&
                !url.value.contains("fixture-query-secret")
        },
        run("raw_json_sanitization") {
            val captured = DiagnosticRedactor.captureJson(
                """{"model":"fixture","content":"private fixture","api_key":"fixture-secret"}""",
            )
            captured.value.contains("model") &&
                captured.value.contains("private fixture") &&
                !captured.value.contains("fixture-secret")
        },
        run("sse_fixture") {
            val captured = DiagnosticRedactor.captureWireLine(
                """data: {"text":"private fixture","token":"fixture-secret"}""",
            )
            captured.value.startsWith("data:") &&
                captured.value.contains("private fixture") &&
                !captured.value.contains("fixture-secret")
        },
        run("invalid_payload_sanitization") {
            val captured = DiagnosticRedactor.captureJson(
                "not-json token=fixture-secret private fixture",
            )
            captured.value.contains("private fixture") &&
                !captured.value.contains("fixture-secret")
        },
        run("capture_limit") {
            val captured = DiagnosticRedactor.captureContent(
                "x".repeat(DiagnosticCaptureStore.DEFAULT_MAX_PAYLOAD_BYTES + 1),
            )
            captured.truncated &&
                captured.value.length == DiagnosticCaptureStore.DEFAULT_MAX_PAYLOAD_BYTES
        },
        run("stable_private_identity") {
            val first = com.newoether.agora.model.ConversationRuntimeTrace
                .hashConversationId("fixture-id")
            val second = com.newoether.agora.model.ConversationRuntimeTrace
                .hashConversationId("fixture-id")
            first == second && first.length == 24 && !first.contains("fixture-id")
        },
    )

    private inline fun run(
        id: String,
        block: () -> Boolean,
    ): DeveloperTestResult = try {
        val passed = block()
        DeveloperTestResult(
            id = id,
            passed = passed,
            detail = if (passed) "PASS" else "FAILED_ASSERTION",
        )
    } catch (error: Throwable) {
        DeveloperTestResult(
            id = id,
            passed = false,
            detail = "FAILED_" + error.javaClass.simpleName,
        )
    }
}
