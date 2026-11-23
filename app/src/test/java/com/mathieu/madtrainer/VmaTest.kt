package com.mathieu.madtrainer

import com.mathieu.madtrainer.data.Sample
import com.mathieu.madtrainer.data.SessionRecord
import com.mathieu.madtrainer.data.Sport
import com.mathieu.madtrainer.data.Vma
import com.mathieu.madtrainer.data.kmSplits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class VmaTest {
    /** Course à vitesse constante [kmh] pendant [min] minutes, cardio [hr]. */
    private fun run(kmh: Double, min: Int, hr: Int?): SessionRecord {
        val samples = (0..min * 60).map { t -> Sample(t = t, hr = hr, distanceM = (t * kmh / 3.6).roundToInt()) }
        return SessionRecord(startedAt = 0, durationSec = min * 60, distanceM = samples.last().distanceM!!, sport = Sport.RUNNING, samples = samples)
    }

    @Test
    fun vmaFromHeartRateReserve() {
        // FC max 190, repos 50 : 155 bpm = 75 % de la FC de réserve → 12 km/h / 0,75 = 16 km/h
        val est = Vma.estimate(run(12.0, 30, 155), maxHr = 190, restingHr = 50)!!
        assertEquals(16.0, est.vmaKmh, 0.15)
        assertEquals(56.0, est.vo2max, 0.6)
        assertTrue(est.reliable)
    }

    @Test
    fun withoutHeartRateBestSixMinutesIsLowerBound() {
        val est = Vma.estimate(run(12.0, 20, null), maxHr = 190, restingHr = 50)!!
        assertEquals(12.0, est.vmaKmh, 0.1)
        assertTrue(!est.reliable)
        assertNull(Vma.estimate(run(12.0, 8, 150), 190, 50))
    }

    @Test
    fun walkingAndSpeedChangesAreIgnored() {
        // 4 min de course à 10 km/h (150 bpm) / 4 min de marche à 5,5 km/h (140 bpm) pendant 48 min
        // Course : 150 bpm = 100/140 = 71,4 % de FCR → 10 / 0,714 = 14 km/h ; la marche ne doit pas tirer la médiane
        var d = 0.0
        val samples = (0..48 * 60).map { t ->
            val running = (t / 240) % 2 == 0
            if (t > 0) d += (if (running) 10.0 else 5.5) / 3.6
            Sample(t = t, hr = if (running) 150 else 140, distanceM = d.roundToInt())
        }
        val rec = SessionRecord(startedAt = 0, durationSec = 48 * 60, distanceM = d.roundToInt(), sport = Sport.RUNNING, samples = samples)
        val est = Vma.estimate(rec, maxHr = 190, restingHr = 50)!!
        assertEquals(14.0, est.vmaKmh, 0.2)
    }

    /** Footing de [min] min qui alterne 7,6 et 8,4 km/h chaque minute, altitude GPS qui ondule de ±8 m. */
    private fun jog(min: Int, hr: Int?, start: Long = 0): SessionRecord {
        var d = 0.0
        val samples = (0..min * 60).map { t ->
            if (t > 0) d += (if ((t / 60) % 2 == 0) 7.6 else 8.4) / 3.6
            Sample(t = t, hr = hr, distanceM = d.roundToInt(), alt = 100 + 8 * kotlin.math.sin(t / 60.0))
        }
        return SessionRecord(startedAt = start, durationSec = min * 60, distanceM = d.roundToInt(), sport = Sport.RUNNING, samples = samples)
    }

    @Test
    fun easyJogWithNoisyAltitudeIsEstimatedFromHeartRate() {
        // 150 bpm = 71,4 % de FCR → environ 8 / 0,714 ≈ 11,2 km/h, et non la meilleure vitesse 6 min (≈ 8 km/h)
        val est = Vma.estimate(jog(40, 150), maxHr = 190, restingHr = 50)!!
        assertTrue(est.method, est.fromHeartRate)
        assertEquals(11.2, est.vmaKmh, 0.7)

        // Des sorties sans cardio (borne basse ≈ 8 km/h) ne tirent pas la VMA retenue vers le bas
        val day = 24L * 3600 * 1000
        val runs = listOf(jog(40, 150, 0), jog(40, null, day), jog(40, 150, 2 * day), jog(40, null, 3 * day), jog(40, 150, 4 * day))
        val retained = Vma.recent(runs, 190, 50)!!
        assertEquals(3, retained.runs)
        assertEquals(11.2, retained.vmaKmh, 0.7)
    }

    @Test
    fun gpsJumpDoesNotInflateBestSixMinutes() {
        val samples = (0..20 * 60).map { t ->
            val base = (t * 10.0 / 3.6).roundToInt()
            Sample(t = t, distanceM = if (t >= 600) base + 400 else base)
        }
        val rec = SessionRecord(startedAt = 0, durationSec = 1200, distanceM = samples.last().distanceM!!, sport = Sport.RUNNING, samples = samples)
        assertEquals(10.0, Vma.bestSpeed(rec.samples, 360)!!, 0.1)
        assertEquals(10.0, Vma.estimate(rec, 190, 50)!!.vmaKmh, 0.1)
    }

    @Test
    fun retainedVmaIsMedianOfRecentRuns() {
        val day = 24L * 3600 * 1000
        // 170 bpm → 14 km/h ; une sortie isolée à 143 bpm → 18 km/h ne doit pas faire monter la VMA retenue
        val runs = listOf(170, 170, 143, 170, 170).mapIndexed { i, hr -> run(12.0, 30, hr).copy(startedAt = i * day) }
        val est = Vma.recent(runs, maxHr = 190, restingHr = 50)!!
        assertEquals(5, est.runs)
        assertEquals(14.0, est.vmaKmh, 0.15)
        // Seules les courses jusqu'à la date demandée comptent
        assertEquals(2, Vma.recent(runs, 190, 50, until = day)!!.runs)
    }

    @Test
    fun paceTableUsesPercentOfVma() {
        val t = Vma.paceTable(15.0)
        val vma = t.last()
        // 100 % de 15 km/h = 4'00/km ; 105 % plus rapide, 95 % plus lent
        assertEquals(240, Vma.paceAt(15.0, 1.0))
        assertTrue(vma.fastSecKm < 240 && vma.slowSecKm > 240)
        assertTrue(t.zipWithNext().all { (a, b) -> b.fastSecKm < a.fastSecKm })
    }

    @Test
    fun kmSplitsAreInterpolatedWithCumulativeTime() {
        // 5'30/km pendant 2,5 km
        val samples = (0..825).map { t -> Sample(t = t, distanceM = (t * 1000.0 / 330).roundToInt()) }
        val s = kmSplits(samples)
        assertEquals(3, s.size)
        assertEquals(330, s[0].sec)
        assertEquals(330, s[1].sec)
        assertEquals(660, s[1].cumulativeSec)
        assertEquals(500, s[2].distanceM)
        assertEquals(165, s[2].sec)
        assertEquals(330, s[2].paceSecKm)
        assertNotNull(s.last())
    }
}
