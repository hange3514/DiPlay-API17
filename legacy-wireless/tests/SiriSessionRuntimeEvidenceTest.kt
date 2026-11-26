package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

class SiriSessionRuntimeEvidenceTest {
    @Test fun reportsMatchingRepliesAndRecognizingLatency() {
        var now = 10_000L
        val evidence = SiriSessionRuntimeEvidence { now }
        evidence.beginButton(6)
        evidence.observeReply(6, 100)
        evidence.observeReply(5, 403)
        evidence.observeReply(6, 200) // The reply may arrive before the writes are marked complete.
        evidence.finishButtonWrite(true)
        evidence.observeReply(7, 400)
        evidence.observeReply(6, 500)
        now += 280
        evidence.observeSpeech(speech(2))
        now += 350
        evidence.observeSpeech(speech(2))
        evidence.observeSpeech(speech(99))
        evidence.observeSpeech(speech(1))
        assertEquals(
            "siriButtonAttempts=1 siriButtonWritten=true siriPressStatus=200 siriReleaseStatus=400 " +
                "siriMode=1 siriModeChanges=2 siriButtonToListeningMs=280",
            evidence.summary(),
        )
    }

    @Test fun ignoresResponsesToUnsentOrExpiredAttempt() {
        var now = 10_000L
        val evidence = SiriSessionRuntimeEvidence { now }
        evidence.beginButton(1)
        evidence.observeReply(1, 200)
        evidence.finishButtonWrite(false)
        evidence.observeReply(2, 200)
        evidence.observeSpeech(speech(2))
        assertEquals(
            "siriButtonAttempts=1 siriButtonWritten=false siriPressStatus=0 siriReleaseStatus=0 " +
                "siriMode=2 siriModeChanges=1 siriButtonToListeningMs=-1",
            evidence.summary(),
        )

        evidence.beginButton(10)
        evidence.finishButtonWrite(true)
        now += 20_000
        evidence.observeReply(11, 200)
        evidence.observeSpeech(speech(1))
        evidence.observeSpeech(speech(2))
        assertEquals(
            "siriButtonAttempts=2 siriButtonWritten=true siriPressStatus=0 siriReleaseStatus=0 " +
                "siriMode=2 siriModeChanges=3 siriButtonToListeningMs=-1",
            evidence.summary(),
        )
    }

    private fun speech(mode: Int): Map<String, Any> =
        mapOf("appStates" to listOf(mapOf("appStateID" to 1L, "speechMode" to mode)))
}
