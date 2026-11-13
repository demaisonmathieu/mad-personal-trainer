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

class DomyosLegacyTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun resistanceFrameChecksum() {
        val frames = com.mathieu.madtrainer.ble.DomyosLegacy.resistanceFrames(5)
        assertEquals(2, frames.size)
        assertEquals(20, frames[0].bytes.size)
        assertEquals(3, frames[1].bytes.size)
        val all = frames[0].bytes + frames[1].bytes
        assertEquals(5, all[10].toInt())
        assertEquals(0xFF, all[17].toInt() and 0xFF) // sinon rejet silencieux par la console
        // Checksum attendu pour le niveau 5 (somme des octets 0..21)
        val init = com.mathieu.madtrainer.ble.DomyosLegacy.resistanceFrames(5)
        assertEquals(0x91, init[1].bytes[2].toInt() and 0xFF)
        var sum = 0
        for (i in 0 until 22) sum += all[i].toInt() and 0xFF
        assertEquals(sum and 0xFF, all[22].toInt() and 0xFF)
        // bornage 1..15
        assertEquals(15, com.mathieu.madtrainer.ble.DomyosLegacy.resistanceFrames(40)[0].bytes[10].toInt())
    }

    @Test
    fun resistanceChunksAreSentBackToBack() {
        val f = com.mathieu.madtrainer.ble.DomyosLegacy.resistanceFrames(3)
        assertTrue(!f[0].waitResponse)   // pas de pause entre les 2 morceaux
        assertTrue(f[1].waitResponse)
        // Doit être identique à la trame d'init acceptée, au niveau près
        val hex = (f[0].bytes + f[1].bytes).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        assertEquals("F0 AD FF FF FF FF FF FF FF FF 03 FF FF FF FF FF FF FF 01 FF FF FF 8F", hex)
    }

    @Test
    fun displayFramesChecksum() {
        val fr = com.mathieu.madtrainer.ble.DomyosLegacy.displayFrames(125, 6.2, 72, 0, 3, 0.5)
        assertEquals(4, fr.size)
        for (k in listOf(0, 2)) {
            val w = fr[k].bytes + fr[k + 1].bytes
            assertEquals(27, w.size)
            var sum = 0
            for (i in 0 until 26) sum += w[i].toInt() and 0xFF
            assertEquals(sum and 0xFF, w[26].toInt() and 0xFF)
        }
        val d = fr[2].bytes + fr[3].bytes
        assertEquals(2, d[3].toInt()); assertEquals(5, d[4].toInt()) // 2 min 05 s
        assertEquals(72, d[16].toInt())
    }

    @Test
    fun commandAckFromRealLog() {
        val d = com.mathieu.madtrainer.ble.DomyosLegacy
        // Écho réel reçu de la console EL500 (journal du 13/11)
        val ack = bytes(0xF0, 0xBD, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x07, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01, 0xFF, 0xFF, 0xFF, 0x00)
        assertEquals(7, d.parseCommandAck(ack))
        assertNull(d.parseCommandAck(bytes(0xF0, 0xBC, 0xFF)))
    }

    @Test
    fun realStatusFrame() {
        val d = com.mathieu.madtrainer.ble.DomyosLegacy
        // Trame réelle : 6,2 km/h, cadence 72, résistance 1
        val p = "F0 BC FF FF 00 00 00 3E 00 48 00 00 00 00 01 00 01 00 00 00 3E FF 00 00 01 32"
            .split(" ").map { it.toInt(16).toByte() }.toByteArray()
        val st = d.parseStatus(p)!!
        assertEquals(6.2, st.speedKmh, 0.001)
        assertEquals(72, st.cadence)
        assertEquals(1, st.resistance)
    }

    @Test
    fun initSequence() {
        val d = com.mathieu.madtrainer.ble.DomyosLegacy
        assertEquals(14, d.initSequence(false).size)
        assertEquals(15, d.initSequence(true).size)
        assertArrayEquals(bytes(0xf0, 0xc8, 0x01, 0xb9), d.initSequence(false)[0].bytes)
    }

    @Test
    fun statusParsingAndAssembly() {
        val d = com.mathieu.madtrainer.ble.DomyosLegacy
        val p = ByteArray(26)
        p[0] = 0xF0.toByte(); p[1] = 0xBC.toByte()
        p[6] = 0x00; p[7] = 0x7D        // 12.5 km/h
        p[9] = 55                       // cadence
        p[10] = 0x01; p[11] = 0x2C      // 300 kcal
        p[14] = 7                       // résistance
        p[18] = 0                       // pas de cardio
        val a = com.mathieu.madtrainer.ble.DomyosLegacy.Assembler()
        assertNull(a.push(p.copyOfRange(0, 20)))
        val full = a.push(p.copyOfRange(20, 26))!!
        val st = d.parseStatus(full)!!
        assertEquals(12.5, st.speedKmh, 0.001)
        assertEquals(55, st.cadence)
        assertEquals(300, st.kcal)
        assertEquals(7, st.resistance)
        assertNull(st.heartRate)
        // trame entière d'un coup
        assertEquals(26, a.push(p)!!.size)
    }

    @Test
    fun ergSimulationIsConsistent() {
        val d = com.mathieu.madtrainer.ble.DomyosLegacy
        val r = d.resistanceForPower(150, 60)
        val w = d.estimatePower(r, 60, 10.0)
        assertTrue("r=$r w=$w", kotlin.math.abs(w - 150) < 20)
        assertEquals(0, d.estimatePower(5, 0, 0.0))
    }
}
