package com.mathieu.madtrainer

import com.mathieu.madtrainer.data.BodyCache
import com.mathieu.madtrainer.data.BodyKind
import com.mathieu.madtrainer.data.BodyMeasure
import com.mathieu.madtrainer.data.BodyPoint
import com.mathieu.madtrainer.data.BodyStats
import com.mathieu.madtrainer.data.RestingHr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyMetricsTest {
    private val day = 86_400_000L
    private val now = 400 * day + 12 * 3_600_000L
    private val starfit = "com.starfit.app"

    @Test
    fun groupsValuesOfSameWeighing() {
        val t = now - day
        val points = listOf(
            BodyPoint(t, BodyKind.WEIGHT, 82.4, starfit),
            BodyPoint(t + 1_000, BodyKind.BODY_FAT_PCT, 21.5, starfit),
            BodyPoint(t + 2_000, BodyKind.WATER_MASS, 45.0, starfit),
            BodyPoint(t + 3_000, BodyKind.BMR, 1789.6, starfit),
            BodyPoint(t + 4_000, BodyKind.HEIGHT, 1.78, starfit),
            // Pesée suivante, le lendemain
            BodyPoint(now, BodyKind.WEIGHT, 82.0, starfit),
        )
        val m = BodyStats.group(points)
        assertEquals(2, m.size)
        assertEquals(82.4, m[0].weightKg!!, 0.001)
        assertEquals(21.5, m[0].bodyFatPct!!, 0.001)
        assertEquals(1790, m[0].bmrKcal)
        assertEquals(45.0 / 82.4 * 100, m[0].waterPct!!, 0.001)
        assertNull(m[1].bodyFatPct)
        assertEquals(178, BodyStats.latestHeightCm(points))
        assertEquals(82.0, BodyStats.latest(m)!!.weightKg!!, 0.001)
    }

    @Test
    fun twoWeighingsCloseInTimeStaySeparate() {
        // Deux pesées à 30 s d'écart : le second poids ouvre une nouvelle pesée
        val points = listOf(
            BodyPoint(now, BodyKind.WEIGHT, 82.4, starfit),
            BodyPoint(now + 30_000, BodyKind.WEIGHT, 82.6, starfit),
        )
        assertEquals(2, BodyStats.group(points).size)
    }

    @Test
    fun differentSourcesAreNotMerged() {
        val points = listOf(
            BodyPoint(now, BodyKind.WEIGHT, 82.4, starfit),
            BodyPoint(now + 1_000, BodyKind.BODY_FAT_PCT, 20.0, "com.huami.watch.hmwatchmanager"),
        )
        val m = BodyStats.group(points)
        assertEquals(2, m.size)
        assertNull(BodyStats.latest(m.filter { it.weightKg == null }))
    }

    @Test
    fun trendComparesStartAndEndAverages() {
        // 30 jours de pesées, −0,1 kg par jour
        val measures = (0 until 30).map { i -> BodyMeasure(time = now - (29 - i) * day, weightKg = 85.0 - i * 0.1) }
        val t = BodyStats.trend(measures, 30, now) { it.weightKg }
        assertNotNull(t)
        assertTrue(t!!.delta < -2.0 && t.delta > -3.0)
        assertTrue(t.perWeek < 0)
        // Une seule pesée : pas de tendance
        assertNull(BodyStats.trend(measures.takeLast(1), 30, now) { it.weightKg })
    }

    @Test
    fun dailySeriesHasOneValuePerDay() {
        val measures = listOf(
            BodyMeasure(time = now - 2 * day, weightKg = 83.0),
            BodyMeasure(time = now, weightKg = 82.5),
        )
        val s = BodyStats.dailySeries(measures, 7, now) { it.weightKg }
        assertEquals(7, s.size)
        assertEquals(82.5f, s.last()!!, 0.001f)
        assertEquals(83.0f, s[4]!!, 0.001f)
        assertNull(s[5])
    }

    @Test
    fun describeForCoachPrompt() {
        val cache = BodyCache(
            measures = (0 until 30).map { i ->
                BodyMeasure(time = now - (29 - i) * day, source = starfit, weightKg = 85.0 - i * 0.1, bodyFatPct = 24.0 - i * 0.05)
            },
            restingHr = listOf(RestingHr(now - day, 56), RestingHr(now, 54)),
        )
        val txt = BodyStats.describe(cache, now)!!
        assertTrue(txt, txt.contains("poids 82,1 kg"))
        assertTrue(txt, txt.contains("masse grasse"))
        assertTrue(txt, txt.contains("Sur 30 jours : poids −"))
        assertTrue(txt, txt.contains("FC de repos (moyenne 7 jours, montre) : 55 bpm"))
        assertNull(BodyStats.describe(BodyCache(), now))
    }
}
