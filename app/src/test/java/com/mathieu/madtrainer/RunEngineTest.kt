package com.mathieu.madtrainer

import com.mathieu.madtrainer.data.RunEngine
import com.mathieu.madtrainer.data.StepMode
import com.mathieu.madtrainer.data.WorkoutStep
import com.mathieu.madtrainer.data.formatPace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunEngineTest {
    @Test
    fun zones() {
        assertEquals(90..108, RunEngine.zoneRange(1, 180))
        assertEquals(162..180, RunEngine.zoneRange(5, 180))
        assertEquals(3, RunEngine.zoneOf(130, 180))
        assertEquals(5, RunEngine.zoneOf(170, 180))
    }

    @Test
    fun paceAndFormat() {
        assertEquals(360, RunEngine.pace(1000.0, 360.0))
        assertNull(RunEngine.pace(5.0, 10.0))
        assertEquals("5'45\"", formatPace(345))
        assertEquals("--'--\"", formatPace(null))
    }

    @Test
    fun stepCompletionByDistanceOrTime() {
        val byDist = WorkoutStep("400", 120, StepMode.PACE, 300.0, distanceM = 400)
        assertTrue(!RunEngine.stepDone(byDist, 500, 399.0))
        assertTrue(RunEngine.stepDone(byDist, 10, 400.0))
        val byTime = WorkoutStep("Footing", 600, StepMode.HR_ZONE, 2.0)
        assertTrue(RunEngine.stepDone(byTime, 600, 0.0))
        assertTrue(!RunEngine.stepDone(byTime, 599, 5000.0))
    }

    @Test
    fun guidance() {
        val pace = WorkoutStep("Tempo", 600, StepMode.PACE, 330.0)
        assertEquals(RunEngine.Guidance.FASTER, RunEngine.guidance(pace, 360, null, 180))
        assertEquals(RunEngine.Guidance.SLOWER, RunEngine.guidance(pace, 300, null, 180))
        assertEquals(RunEngine.Guidance.NONE, RunEngine.guidance(pace, 335, null, 180))
        val z2 = WorkoutStep("Z2", 600, StepMode.HR_ZONE, 2.0) // 108-126
        assertEquals(RunEngine.Guidance.SLOWER, RunEngine.guidance(z2, null, 135, 180))
        assertEquals(RunEngine.Guidance.FASTER, RunEngine.guidance(z2, null, 100, 180))
        assertEquals(RunEngine.Guidance.NONE, RunEngine.guidance(z2, null, 120, 180))
    }
}
