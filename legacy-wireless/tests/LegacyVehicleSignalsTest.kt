package local.headunit.diplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyVehicleSignalsTest {
    @Test fun matchesOnlyTheObservedApi17Mx9a0BuildProfile() {
        assertTrue(LegacyVehicleSignals.matchesProfile(17, "freescale", "MX9A0", "sabresd_6dq", "sabresd_6dq", "SABRESD", "freescale"))
        assertFalse(LegacyVehicleSignals.matchesProfile(19, "freescale", "MX9A0", "sabresd_6dq", "sabresd_6dq", "SABRESD", "freescale"))
        assertFalse(LegacyVehicleSignals.matchesProfile(17, "freescale", "MX9", "sabresd_6dq", "sabresd_6dq", "SABRESD", "freescale"))
        assertFalse(LegacyVehicleSignals.matchesProfile(17, "freescale", "MX9A0", "sabresd_6dq", "other", "SABRESD", "freescale"))
    }

    @Test fun recognizesOnlyKnownOemVehicleCodesForDrivingOnASingleSource() {
        assertEquals(0x121, LegacyVehicleSignals.preferredId(" sahg "))
        assertEquals(0x080, LegacyVehicleSignals.preferredId("e6H"))
        assertEquals(0, LegacyVehicleSignals.preferredId("Reserved"))
        assertEquals(0, LegacyVehicleSignals.preferredId(null))
        assertEquals(0, LegacyVehicleSignals.preferredId(""))
    }

    @Test fun decodesBothOemLayoutsOnlyFromTheirSelectedSources() {
        val slower = LegacyVehicleSignals.SignalState(0x121)
        val faster = LegacyVehicleSignals.SignalState(0x080)
        val frame121 = intArrayOf(0x121, 0xe8, 0x73, 0xfe, 0xff, 0xff, 0xff, 0xf8, 0x9a)
        val frame080 = intArrayOf(0x080, 0xf7, 0xff, 0xff, 0xd0.toByte().toInt(), 0xf7, 0xff, 0xff, 0xff)
        assertFalse(slower.observeCan(frame080, 100))
        assertTrue(slower.snapshot(100).speedKph.isNaN())
        assertTrue(slower.observeCan(frame121, 101))
        assertEquals(68.75, slower.snapshot(101).speedKph, 0.0001)
        assertEquals("can_121", slower.snapshot(101).speedSource)
        assertEquals(101L, slower.snapshot(101).speedAtMs)
        assertTrue(faster.observeCan(frame080, 102))
        assertEquals(137.5, faster.snapshot(102).speedKph, 0.0001)
        assertEquals("can_080", faster.snapshot(102).speedSource)
    }

    @Test fun requiresAlignedAgreementWhenOemVehicleCodeIsUnknown() {
        val state = LegacyVehicleSignals.SignalState(0)
        val frame121 = intArrayOf(0x121, 0x60, 0x71, 0xfe, 0xff, 0xff, 0xff, 0xf8, 0x9a)
        val frame080 = intArrayOf(0x080, 0xf7, 0xff, 0xff, 0x60, 0xf1, 0xff, 0xff, 0xff)
        state.observeCan(frame121, 0)
        assertTrue(state.snapshot(0).speedKph.isNaN())
        state.observeCan(frame080, 601)
        assertTrue(state.snapshot(601).speedKph.isNaN())
        state.observeCan(frame121, 610)
        assertEquals(24.2, state.snapshot(610).speedKph, 0.0001)
        assertEquals("can_agreement", state.snapshot(610).speedSource)
        assertEquals("dual_agreement", state.snapshot(610).speedSelector)
        assertEquals(601L, state.snapshot(610).speedAtMs)
        frame080[4] = 0x61
        state.observeCan(frame080, 611)
        assertTrue(state.snapshot(611).speedKph.isNaN())
        assertEquals(-1L, state.snapshot(611).speedAtMs)
    }

    @Test fun infersOnlyObservedSahModelWhenOemPropertyIsUnresolved() {
        val state = LegacyVehicleSignals.SignalState(0)
        val reportedModel = intArrayOf(0x40d, 0x0f, 0x20, 0x0c, 0xfc, 4, 0xff, 0xff, 1)
        val speed121 = intArrayOf(0x121, 0x60, 0x71, 0xfe, 0xff, 0xff, 0xff, 0xf8, 0x9a)
        val speed080 = intArrayOf(0x080, 0xf7, 0xff, 0xff, 0x60, 0xf1, 0xff, 0xff, 0xff)
        assertFalse(state.observeCan(reportedModel.copyOf(3), 90))
        assertFalse(state.observeCan(reportedModel.clone().apply { this[2] = 0x21 }, 95))
        assertTrue(state.observeCan(reportedModel, 100))
        assertEquals("can_model_sah", state.snapshot(100).speedSelector)
        assertTrue(state.snapshot(100).speedKph.isNaN())
        assertFalse(state.observeCan(speed080, 110))
        assertTrue(state.observeCan(speed121, 120))
        assertEquals("can_121", state.snapshot(120).speedSource)
        assertTrue(state.selectOemSpeedId(0x121))
        assertEquals("oem_property", state.snapshot(120).speedSelector)
        assertTrue(state.selectOemSpeedId(0x080))
        assertFalse(state.observeCan(reportedModel, 125))
        assertTrue(state.snapshot(125).speedKph.isNaN())
        assertTrue(state.observeCan(speed080, 130))
        assertEquals("can_080", state.snapshot(130).speedSource)
    }

    @Test fun invalidSentinelClearsSpeedAndMalformedFramesDoNotRefreshIt() {
        val state = LegacyVehicleSignals.SignalState(0x121)
        assertTrue(state.observeCan(intArrayOf(0x121, 0, 0x70, 0xfe, 0xff, 0xff, 0xff, 0xf8, 0x9a), 100))
        assertEquals(0.0, state.snapshot(100).speedKph, 0.0)
        assertFalse(state.observeCan(intArrayOf(0x121, 0xff, 0x0f), 110))
        assertFalse(state.observeCan(intArrayOf(0x121, 0x100, 0, 0, 0, 0, 0, 0, 0), 120))
        assertEquals(100L, state.snapshot(120).speedAtMs)
        assertTrue(state.snapshot(1600).speedKph.isNaN())
        assertTrue(state.observeCan(intArrayOf(0x121, 0xff, 0xff, 0, 0, 0, 0, 0, 0), 130))
        assertEquals("none", state.snapshot(130).speedSource)
    }

    @Test fun observedLampCorrelationBytesShareTheSameParkingBrakeBit() {
        val state = LegacyVehicleSignals.SignalState(0x121)
        val brakeFrame = intArrayOf(0x055, 0, 0, 0x10, 0, 0, 0x03, 0x24, 4)
        state.observeCan(brakeFrame, 100)
        assertEquals(true, state.snapshot(100).parkingBrake)
        assertNull(state.snapshot(100).night)
        brakeFrame[6] = 0x6b
        state.observeCan(brakeFrame, 110)
        assertEquals(true, state.snapshot(110).parkingBrake)
        assertTrue(state.snapshot(110).speedKph.isNaN())
        assertNull(state.snapshot(110).night)
        brakeFrame[6] = 0x6a
        state.observeCan(brakeFrame, 120)
        assertEquals(false, state.snapshot(120).parkingBrake)
        assertNull(state.snapshot(8120).parkingBrake)
    }

    @Test fun imageModeLatchesFromKnownOemEventsAndStartsUnknownInNewSession() {
        val state = LegacyVehicleSignals.SignalState(0)
        assertFalse(state.observeLight("MODE_AUTO", 1))
        assertNull(state.snapshot(1).night)
        assertTrue(state.observeLight("MODE_NIGHT", 10))
        assertEquals(true, state.snapshot(60 * 60_000L).night)
        assertEquals(10L, state.snapshot(60 * 60_000L).nightAtMs)
        assertTrue(state.observeLight("MODE_DAY", 60 * 60_000L))
        assertEquals(false, state.snapshot(60 * 60_000L).night)
        assertNull(LegacyVehicleSignals.SignalState(0).snapshot(60 * 60_000L).night)
    }

    @Test fun decodesCapturedSteeringPositionWithoutAssumingItsScaleOrZero() {
        val state = LegacyVehicleSignals.SignalState(0x121)
        assertNull(state.snapshot(0).steeringRaw)
        assertTrue(state.observeCan(intArrayOf(0x11f, 0x2f, -4, 0, 7, 0xe7, 0, 0, 0), 8))
        assertEquals(-977, state.snapshot(8).steeringRaw)
        assertEquals(8L, state.snapshot(8).steeringAtMs)
        assertEquals(-1L, state.snapshot(8).steeringSwingAtMs)
        assertTrue(state.observeCan(steeringFrame(7200), 900))
        assertEquals(7200, state.snapshot(1899).steeringRaw)
        assertNull(state.snapshot(1900).steeringRaw)
        assertEquals(-1L, state.snapshot(1900).steeringAtMs)
        assertTrue(state.observeCan(steeringFrame(-7200), 2000))
        assertEquals(-7200, state.snapshot(2000).steeringRaw)
    }

    @Test fun malformedAndOutOfRangeFramesDoNotPropagateSteering() {
        val state = LegacyVehicleSignals.SignalState(0)
        assertTrue(state.observeCan(steeringFrame(-1500), 50))
        assertFalse(state.observeCan(steeringFrame(600).copyOf(8), 60))
        assertFalse(state.observeCan(steeringFrame(600).apply { this[1] = -129 }, 70))
        assertFalse(state.observeCan(steeringFrame(600).apply { this[2] = 256 }, 80))
        assertEquals(50L, state.snapshot(90).steeringAtMs)
        assertTrue(state.observeCan(steeringFrame(7201), 95))
        assertNull(state.snapshot(95).steeringRaw)
        assertEquals(-1L, state.snapshot(95).steeringSwingAtMs)
        assertTrue(state.observeCan(steeringFrame(100), 96))
        assertEquals(100, state.snapshot(96).steeringRaw)
        assertEquals(-1L, state.snapshot(96).steeringSwingAtMs)
        assertTrue(state.observeCan(steeringFrame(-7201), 97))
        assertNull(state.snapshot(97).steeringRaw)
    }

    @Test fun recentSwingPreservesBetweenPollMotionWithoutCrossingStaleGaps() {
        val state = LegacyVehicleSignals.SignalState(0)
        state.observeCan(steeringFrame(-120), 470)
        state.observeCan(steeringFrame(280), 505)
        state.observeCan(steeringFrame(-110), 520)
        state.observeCan(steeringFrame(280), 700)
        state.observeCan(steeringFrame(-110), 710)
        val afterRoundTrip = state.snapshot(900)
        assertEquals(-110, afterRoundTrip.steeringRaw)
        assertEquals(400, afterRoundTrip.steeringSwingRaw)
        assertEquals(505L, afterRoundTrip.steeringSwingAtMs)
        assertEquals(390, state.snapshot(1470).steeringSwingRaw)
        assertEquals(520L, state.snapshot(1470).steeringSwingAtMs)
        assertEquals(0, state.snapshot(1505).steeringSwingRaw)
        assertEquals(-1L, state.snapshot(1505).steeringSwingAtMs)
        assertEquals(-110, state.snapshot(1505).steeringRaw)
        state.observeCan(steeringFrame(320), 1800)
        assertEquals(320, state.snapshot(1800).steeringRaw)
        assertEquals(-1L, state.snapshot(1800).steeringSwingAtMs)
    }

    private fun steeringFrame(raw: Int) = intArrayOf(0x11f, raw and 255, raw ushr 8 and 255, 0, 7, 0, 0, 0, 0)
}
