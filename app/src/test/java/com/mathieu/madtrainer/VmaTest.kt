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
