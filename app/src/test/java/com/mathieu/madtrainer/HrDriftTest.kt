package com.mathieu.madtrainer

import com.mathieu.madtrainer.data.HrDrift
import com.mathieu.madtrainer.data.Sample
import com.mathieu.madtrainer.data.SessionRecord
import com.mathieu.madtrainer.data.Sport
import com.mathieu.madtrainer.data.StepMode
import com.mathieu.madtrainer.data.WorkoutStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class HrDriftTest {
    private fun elliptical(duration: Int, steps: List<WorkoutStep> = emptyList(), gen: (Int) -> Pair<Int, Int>) = SessionRecord(
        startedAt = 0L,
        durationSec = duration,
        sport = Sport.ELLIPTICAL,
        steps = steps,
        samples = (1..duration).map { t ->
            val (p, hr) = gen(t)
            Sample(t = t, power = p, hr = hr)
        },
    )

    @Test
    fun steadyEffortWithRisingHeartRateDrifts() {
        val rec = elliptical(2400) { t -> 150 to (125 + 25.0 * t / 2400).roundToInt() }
        val r = HrDrift.compute(rec)
        assertNotNull(r)
        assertTrue(r!!.steady)
        assertTrue("${r.pct}", r.pct in 5.0..10.0)
        assertTrue(r.minutes >= 25)
    }

    @Test
    fun warmUpAndCoolDownAreIgnored() {
        val steps = listOf(
            WorkoutStep("Échauffement", 300, StepMode.RESISTANCE, 3.0),
            WorkoutStep("Endurance", 1800, StepMode.RESISTANCE, 8.0),
            WorkoutStep("Retour au calme", 300, StepMode.RESISTANCE, 2.0),
        )
        val rec = elliptical(2400, steps) { t ->
            when {
                t <= 300 -> 80 to 100
                t <= 2100 -> 150 to 140
                else -> 60 to 125
            }
        }
        val r = HrDrift.compute(rec)
        assertNotNull(r)
        assertEquals(0.0, r!!.pct, 0.5)
    }

    @Test
    fun intervalsAreComparedAtEqualEffort() {
        // 5 min à 200 W / 5 min à 140 W : le cardio constant à chaque palier ne doit pas créer de dérive
        val flat = elliptical(3000) { t -> if ((t / 300) % 2 == 0) 200 to 150 else 140 to 125 }
        val r0 = HrDrift.compute(flat)
        assertNotNull(r0)
        assertFalse(r0!!.steady)
        assertEquals(0.0, r0.pct, 0.5)

        // Même séance avec un cardio qui monte au fil du temps à chaque palier
        val rising = elliptical(3000) { t ->
            val k = 1 + 0.10 * t / 3000
            if ((t / 300) % 2 == 0) 200 to (150 * k).roundToInt() else 140 to (125 * k).roundToInt()
        }
        val r1 = HrDrift.compute(rising)
        assertNotNull(r1)
        assertFalse(r1!!.steady)
        assertTrue("${r1.pct}", r1.pct in 1.5..6.0)
    }

    @Test
    fun runningStopsAreIgnored() {
        val rec = SessionRecord(
            startedAt = 0L,
            durationSec = 2400,
            sport = Sport.RUNNING,
            samples = (1..2400).map { t ->
                val stopped = t % 600 in 0..59 && t > 600
                Sample(
                    t = t,
                    hr = if (stopped) 110 else 150,
                    paceSecPerKm = if (stopped) null else 360,
                    distanceM = t * 1000 / 360,
                )
            },
        )
        val r = HrDrift.compute(rec)
        assertNotNull(r)
        assertEquals(0.0, r!!.pct, 0.5)
    }

    @Test
    fun shortSessionHasNoDrift() {
        assertNull(HrDrift.compute(elliptical(1000) { 150 to 140 }))
    }
}
