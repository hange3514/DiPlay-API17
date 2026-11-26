package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PascdUnknownGearTest {
    @Test fun encodesUnknownGearWithLiveSpeedSamples() {
        val reading = VehicleSpeedReading(VehicleGear.UNKNOWN, listOf(
            VehicleSpeedSample(12_345, 0.0),
            VehicleSpeedSample(12_545, 5.0),
        ))
        assertEquals("\$PASCD,12.345,C,U,0,2,0.00,0.000,0.20,5.000*65\r\n", PascdEncoder.encode(reading))
    }

    @Test fun doesNotEncodeAnEmptySpeedReport() {
        assertNull(PascdEncoder.encode(VehicleSpeedReading(VehicleGear.UNKNOWN, emptyList())))
    }
}
