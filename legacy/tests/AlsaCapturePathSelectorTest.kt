package local.headunit.diplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlsaCapturePathSelectorTest {
    @Test fun usesCaptureEvidenceBeforeGuessedCardNumbersOnOtherModels() {
        val conventional = node(1, "Codec")
        val microphone = node(4, "Digital Microphone")
        val missing = node(5, "Internal Microphone", exists = false)
        val loopback = node(2, "ALSA Loopback")
        val candidates = listOf(conventional, microphone, missing, loopback)

        val ordered = AlsaCapturePathSelector.orderCandidates(candidates, loopback.path, knownMx9a0 = false)

        assertEquals(listOf(microphone.path, conventional.path, missing.path, loopback.path), ordered.map { it.path })
        assertFalse(ordered.any { it.source == "last_signal" })
        assertTrue(ordered.last().isLoopback())
    }

    @Test fun keepsKnownMx9a0AndExistingLearnedInputsAheadOfGenericCandidates() {
        val known = node(1, "pcm1754")
        val microphone = node(3, "DMIC")
        val candidates = listOf(microphone, known)

        assertEquals(known.path, AlsaCapturePathSelector.orderCandidates(candidates, null, knownMx9a0 = true).first().path)
        val learned = AlsaCapturePathSelector.orderCandidates(candidates, known.path, knownMx9a0 = false).first()
        assertEquals(known.path, learned.path)
        assertEquals("last_signal", learned.source)
    }

    @Test fun genericMx9a0BoardDoesNotProveSignalWithoutMatchingCodec() {
        val generic = node(1, "Codec")
        val known = node(1, "pcm1754-audio")
        val inventory = AlsaCapturePathSelector.Inventory(
            "profile", "", "", emptyList(), emptyList(), 0, false, null, 0, 0, true
        )

        assertFalse(inventory.hasProvenSignal(generic))
        assertTrue(inventory.hasProvenSignal(known))
        assertTrue(inventory.hasProvenSignal(generic.copy(source = "last_signal")))
    }

    @Test fun confirmedSahPcm1754PrecedesTas3208OnCleanInstall() {
        val tas = node(0, "tas3208_HiFi")
        val pcm = node(1, "pcm1754_HiFi")
        val candidates = listOf(tas, pcm)

        val generic = AlsaCapturePathSelector.orderCandidates(candidates, null, knownMx9a0 = false)
        assertEquals(tas.path, generic.first().path)
        val sah = AlsaCapturePathSelector.orderCandidates(candidates, null, knownMx9a0 = false, preferSahPcm1754 = true)
        assertEquals(pcm.path, sah.first().path)
        val mismatched = pcm.copy(description = "other capture")
        val unknownCodec = AlsaCapturePathSelector.orderCandidates(listOf(tas, mismatched), null,
            knownMx9a0 = false, preferSahPcm1754 = true)
        assertEquals(tas.path, unknownCodec.first().path)

        val inventory = AlsaCapturePathSelector.Inventory(
            "profile", "", "", emptyList(), sah, 2, false, null, 0, 0, false, preferSahPcm1754 = true
        )
        assertTrue(inventory.hasProvenSignal(pcm))
        assertFalse(inventory.hasProvenSignal(tas))
        assertFalse(inventory.hasProvenSignal(mismatched))
        assertEquals(48000 to 2, inventory.captureFormats(pcm, 32000).first())
    }

    @Test fun sahStillPrefersPreviouslyLearnedReadableCapture() {
        val tas = node(0, "tas3208_HiFi")
        val pcm = node(1, "pcm1754_HiFi")
        val remembered = AlsaCapturePathSelector.orderCandidates(listOf(tas, pcm), tas.path,
            knownMx9a0 = false, preferSahPcm1754 = true).first()
        assertEquals(tas.path, remembered.path)
        assertEquals("last_signal", remembered.source)

        val unreadable = AlsaCapturePathSelector.orderCandidates(listOf(tas.copy(readable = false), pcm), tas.path,
            knownMx9a0 = false, preferSahPcm1754 = true)
        assertEquals(pcm.path, unreadable.first().path)
        assertFalse(unreadable.any { it.source == "last_signal" })
    }

    @Test fun includesDriverSpecificMultichannelFormatsWithinCaptureLimits() {
        val formats = AlsaCapturePathSelector.extraFormats(32000, 192000, 4, 8)

        assertTrue(32000 to 4 in formats)
        assertTrue(44100 to 8 in formats)
        assertTrue(formats.all { (rate, channels) -> rate in 32000..96000 && channels in 4..8 })
        assertTrue(AlsaCapturePathSelector.extraFormats(48000, 8000, 1, 2).isEmpty())
    }

    private fun node(card: Int, description: String, exists: Boolean = true) =
        AlsaCapturePathSelector.Candidate("/dev/snd/pcmC${card}D0c", "proc_pcm", card, 0,
            description, exists, readable = exists, writable = false)
}
