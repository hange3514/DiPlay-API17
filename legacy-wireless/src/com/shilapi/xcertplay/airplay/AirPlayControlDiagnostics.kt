package com.shilapi.xcertplay.airplay

/** Only fixed protocol routes/methods and byte counts survive into the diagnostic report. */
internal object AirPlayControlDiagnostics {
    private val safeMethod = setOf("GET", "POST", "SETUP", "RECORD", "TEARDOWN", "OPTIONS", "GET_PARAMETER", "SET_PARAMETER")
    private val safeRoute = setOf("info", "pair-setup", "pair-verify", "auth-setup", "fp-setup", "stream", "command", "feedback", "server-info")
    private val safeToken = Regex("[A-Za-z0-9_]{1,48}")

    fun request(method: String, path: String, bytes: Int): String {
        val verb = method.takeIf { it in safeMethod } ?: "OTHER"
        val route = route(path)
        return "airplay control request method=$verb route=$route contentBytes=$bytes"
    }

    /** Protocol failure evidence excludes exception text, addresses, identifiers and request payloads. */
    fun failure(method: String, path: String, error: Throwable): String {
        val verb = method.takeIf { it in safeMethod } ?: "OTHER"
        val type = error.javaClass.simpleName.takeIf { safeToken.matches(it) } ?: "Throwable"
        val frame = error.stackTrace.firstOrNull { !it.className.contains("AirPlaySession") }
        val frameClass = frame?.className?.substringAfterLast('.')?.replace(Regex("[^A-Za-z0-9_]"), "")
            ?.takeIf { safeToken.matches(it) } ?: "unknown"
        val frameMethod = frame?.methodName?.replace(Regex("[^A-Za-z0-9_]"), "")
            ?.takeIf { safeToken.matches(it) } ?: "unknown"
        return "airplay protocol failure method=$verb route=${route(path)} errorType=$type frameClass=$frameClass frameMethod=$frameMethod"
    }

    private fun route(path: String): String = path.substringBefore('?').substringBefore('#').substringAfterLast('/').lowercase()
        .takeIf { it in safeRoute } ?: "other"
}
