package com.mathieu.madtrainer

import com.mathieu.madtrainer.data.HrZones
import com.mathieu.madtrainer.data.RunEngine
import com.mathieu.madtrainer.data.StepMode
import com.mathieu.madtrainer.data.WorkoutConverter
import com.mathieu.madtrainer.data.WorkoutStep
import com.mathieu.madtrainer.strava.StravaZones
import org.junit.Assert.assertEquals
import org.junit.Test

class HrZonesTest {
    private val strava = """{"heart_rate":{"custom_zones":true,"zones":[{"min":0,"max":125},{"min":125,"max":145},{"min":145,"max":158},{"min":158,"max":172},{"min":172,"max":-1}]},"power":{"zones":[]}}"""

    @Test
    fun defaultZonesMatchPercentages() {
        val z = HrZones.fromMax(180)
        assertEquals(90..108, z.range(1))
        assertEquals(162..180, z.range(5))
        assertEquals(3, z.zoneOf(130))
        assertEquals(1, z.zoneOf(60))
    }

    @Test
    fun parsesStravaZones() {
        val lows = StravaZones.parseHrLows(strava)
        assertEquals(listOf(105, 125, 145, 158, 172), lows)
        val z = HrZones.of(185, lows)
        assertEquals(true, z.custom)
        assertEquals(125..145, z.range(2))
        assertEquals(172..185, z.range(5))
        assertEquals(2, z.zoneOf(140))
        assertEquals(4, z.zoneOf(160))
        assertEquals(null, StravaZones.parseHrLows("""{"heart_rate":{"zones":[]}}"""))
        assertEquals(null, StravaZones.parseHrLows("pas du json"))
    }

    @Test
    fun customZonesDriveGuidanceAndResistance() {
        val z = HrZones.of(185, StravaZones.parseHrLows(strava))
        val step = WorkoutStep("Z2", 600, StepMode.HR_ZONE, 2.0)
        // 135 bpm : en zone 2 selon Strava, mais au-dessus de la zone 2 calculée sur 185 (111–130)
        assertEquals(RunEngine.Guidance.NONE, RunEngine.guidance(step, null, 135, z))
        assertEquals(RunEngine.Guidance.SLOWER, RunEngine.guidance(step, null, 135, 185))
        assertEquals(5, WorkoutConverter.resistanceForZone(6, 150, 2, z))
        assertEquals("zone 2 (125–145 bpm)", RunEngine.targetText(step, z))
        // Le minimum garanti : FC max au moins au-dessus du début de la zone 5
        assertEquals(177, HrZones.of(150, listOf(105, 125, 145, 158, 172)).max)
    }
}
