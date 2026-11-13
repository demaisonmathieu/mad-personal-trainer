package com.mathieu.madtrainer

import com.mathieu.madtrainer.ble.FtmsControl
import com.mathieu.madtrainer.ble.FtmsParser
import com.mathieu.madtrainer.data.StepMode
import com.mathieu.madtrainer.data.Workout
import com.mathieu.madtrainer.data.WorkoutEngine
import com.mathieu.madtrainer.data.WorkoutStep
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FtmsParserTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun crossTrainerData() {
        // flags : vitesse (bit0=0) + distance(2) + pas/min(3) + résistance(7) + puissance(8) + énergie(10) + temps(13)
        val flags = (1 shl 2) or (1 shl 3) or (1 shl 7) or (1 shl 8) or (1 shl 10) or (1 shl 13)
        val data = bytes(
            flags and 0xFF, (flags shr 8) and 0xFF, (flags shr 16) and 0xFF,
            0xC4, 0x09,             // 25.00 km/h
            0x10, 0x27, 0x00,       // 10000 m
            0x41, 0x00, 0x3C, 0x00, // 65 spm, moy 60
            0x46, 0x00,             // résistance brute 70
            0x96, 0x00,             // 150 W
            0x7B, 0x00, 0xFF, 0xFF, 0xFF, // 123 kcal, n/a, n/a
            0x2C, 0x01,             // 300 s
        )
        val m = FtmsParser.parseCrossTrainerData(data)
        assertEquals(25.0, m.speedKmh!!, 0.001)
        assertEquals(10000, m.distanceM)
        assertEquals(65.0, m.cadence!!, 0.001)
        assertEquals(70, m.resistanceRaw)
        assertEquals(150, m.powerW)
        assertEquals(123, m.totalKcal)
        assertNull(m.kcalPerHour)
        assertEquals(300, m.elapsedSec)
    }

    @Test
    fun truncatedFrameKeepsPartialData() {
        val m = FtmsParser.parseCrossTrainerData(bytes(0x00, 0x00, 0x00, 0xE8))
        assertNull(m.speedKmh)
        val m2 = FtmsParser.parseCrossTrainerData(bytes(0x00, 0x01, 0x00, 0xE8, 0x03, 0x2C))
        assertEquals(10.0, m2.speedKmh!!, 0.001)
        assertNull(m2.powerW)
    }

    @Test
    fun negativePowerIsSigned() {
        val flags = 1 or (1 shl 8) // more data (pas de vitesse) + puissance
        val m = FtmsParser.parseCrossTrainerData(bytes(flags and 0xFF, flags shr 8, 0, 0xFE, 0xFF))
        assertEquals(-2, m.powerW)
        assertNull(m.speedKmh)
    }

    @Test
    fun indoorBikeData() {
        val flags = (1 shl 2) or (1 shl 6)
        val m = FtmsParser.parseIndoorBikeData(bytes(flags, 0, 0x10, 0x0E, 0xA0, 0x00, 0xC8, 0x00))
        assertEquals(36.0, m.speedKmh!!, 0.001)
        assertEquals(80.0, m.cadence!!, 0.001)
        assertEquals(200, m.powerW)
    }

    @Test
    fun heartRate() {
        assertEquals(72, FtmsParser.parseHeartRate(bytes(0x00, 72)))
        assertEquals(300, FtmsParser.parseHeartRate(bytes(0x01, 0x2C, 0x01)))
    }

    @Test
    fun featuresAndRanges() {
        val f = FtmsParser.parseFeatures(bytes(0, 0, 0, 0, 0x0C, 0, 0, 0))!!
        assertTrue(f.supportsResistanceTarget)
        assertTrue(f.supportsPowerTarget)
        val r = FtmsParser.parseRange(bytes(0x0A, 0x00, 0x96, 0x00, 0x0A, 0x00))!!
        assertEquals(FtmsParser.Range(10, 150, 10), r)
    }

    @Test
    fun controlCommands() {
        assertArrayEquals(bytes(0x05, 0x2C, 0x01), FtmsControl.setPower(300))
        assertArrayEquals(bytes(0x04, 80), FtmsControl.setResistance(80, wide = false))
        assertArrayEquals(bytes(0x04, 80, 0), FtmsControl.setResistance(80, wide = true))
        val resp = FtmsControl.parseResponse(bytes(0x80, 0x05, 0x01))!!
        assertTrue(resp.success)
        assertEquals(FtmsControl.OP_SET_POWER, resp.requestOp)
    }

    @Test
    fun workoutPosition() {
        val w = Workout(
            name = "t",
            steps = listOf(
                WorkoutStep("a", 10, StepMode.RESISTANCE, 3.0),
                WorkoutStep("b", 20, StepMode.POWER, 100.0),
            ),
        )
        assertEquals(0, WorkoutEngine.position(w, 0).index)
        assertEquals(0, WorkoutEngine.position(w, 9).index)
        val p = WorkoutEngine.position(w, 10)
        assertEquals(1, p.index)
        assertEquals(20, p.stepRemaining)
        assertTrue(WorkoutEngine.position(w, 30).finished)
    }
}
