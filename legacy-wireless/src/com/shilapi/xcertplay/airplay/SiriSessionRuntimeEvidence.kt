package com.shilapi.xcertplay.airplay

/** Correlates the current button interaction with its event-channel replies and speech state. */
internal class SiriSessionRuntimeEvidence(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private class ButtonAttempt(val startMs: Long, val pressCseq: Int) {
        var written = false
        var writeComplete = false
        var pressStatus = 0
        var releaseStatus = 0
        var listeningMs = -1L
    }

    private var lastButton: ButtonAttempt? = null
    private var buttonAttempts = 0
    private var speechMode: Int? = null
    private var speechTransitions = 0

    @Synchronized fun beginButton(pressCseq: Int) {
        lastButton = ButtonAttempt(nowMs(), pressCseq)
        if (buttonAttempts < 255) buttonAttempts++
    }

    @Synchronized fun finishButtonWrite(written: Boolean) {
        val button = lastButton ?: return
        button.written = written
        button.writeComplete = true
        // If no write was possible, its expected sequence numbers may be used by a later command.
        if (!written) {
            button.pressStatus = 0
            button.releaseStatus = 0
        }
    }

    @Synchronized fun observeReply(cseq: Int, status: Int) {
        val button = lastButton ?: return
        if (button.writeComplete && !button.written) return
        if (status !in 200..599 || nowMs() - button.startMs !in 0L..MATCH_WINDOW_MS) return
        when (cseq) {
            button.pressCseq -> if (button.pressStatus == 0) button.pressStatus = status
            button.pressCseq + 1 -> if (button.releaseStatus == 0) button.releaseStatus = status
        }
    }

    @Synchronized fun observeSpeech(params: Map<String, Any?>) {
        val states = params["appStates"] as? List<*> ?: return
        val state = states.asSequence().take(16).filterIsInstance<Map<*, *>>()
            .firstOrNull { (it["appStateID"] as? Number)?.toLong() == 1L } ?: return
        val mode = (state["speechMode"] as? Number)?.toLong() ?: return
        if ((mode != -1L && mode != 1L && mode != 2L) || mode.toInt() == speechMode) return
        speechMode = mode.toInt()
        if (speechTransitions < 255) speechTransitions++
        val button = lastButton ?: return
        val elapsed = nowMs() - button.startMs
        if (mode == 2L && button.listeningMs < 0L && elapsed in 0L..MATCH_WINDOW_MS) {
            button.listeningMs = elapsed
        }
    }

    @Synchronized fun summary(): String {
        val button = lastButton
        val listeningMs = if (button?.written == true) button.listeningMs else -1L
        return "siriButtonAttempts=$buttonAttempts siriButtonWritten=${button?.written == true} " +
            "siriPressStatus=${button?.pressStatus ?: 0} siriReleaseStatus=${button?.releaseStatus ?: 0} " +
            "siriMode=${speechMode ?: "unknown"} siriModeChanges=$speechTransitions siriButtonToListeningMs=$listeningMs"
    }

    private companion object { const val MATCH_WINDOW_MS = 15_000L }
}
