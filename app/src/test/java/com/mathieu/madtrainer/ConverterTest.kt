package com.mathieu.madtrainer

import com.mathieu.madtrainer.data.Sport
import com.mathieu.madtrainer.data.StepMode
import com.mathieu.madtrainer.data.Workout
import com.mathieu.madtrainer.data.WorkoutConverter
import com.mathieu.madtrainer.data.WorkoutStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConverterTest {
    @Test
    fun ellipticalToRunningUsesZones() {
        val w = Workout(
            name = "Intervalles",
            steps = listOf(
                WorkoutStep("Échauffement", 300, StepMode.RESISTANCE, 3.0),
                WorkoutStep("Effort", 60, StepMode.RESISTANCE, 12.0),
                WorkoutStep("Récup", 60, StepMode.RESISTANCE, 4.0),
                WorkoutStep("Libre", 120, StepMode.FREE, 0.0),
            ),
        )
        val r = WorkoutConverter.convert(w, Sport.RUNNING)
        assertEquals(Sport.RUNNING, r.sport)
        assertTrue(r.name.endsWith("(course)"))
        assertTrue(r.steps.take(3).all { it.mode == StepMode.HR_ZONE })
        assertEquals(1.0, r.steps[0].target, 0.0) // échauffement doux → Z1
        assertEquals(5.0, r.steps[1].target, 0.0) // le plus intense, court → Z5
        assertEquals(StepMode.FREE, r.steps[3].mode)
    }

    @Test
    fun runningToEllipticalDropsDistance() {
        val w = Workout(
            name = "Fractionné", sport = Sport.RUNNING,
            steps = listOf(
                WorkoutStep("Footing", 600, StepMode.HR_ZONE, 2.0),
                WorkoutStep("400 m", 100, StepMode.PACE, 250.0, distanceM = 400),
                WorkoutStep("Tempo", 600, StepMode.PACE, 330.0),
            ),
        )
        val e = WorkoutConverter.convert(w, Sport.ELLIPTICAL)
        assertEquals(Sport.ELLIPTICAL, e.sport)
        assertNull(e.steps[1].distanceM)
        assertEquals(100, e.steps[1].durationSec)
        assertEquals(5.0, e.steps[1].target, 0.0)
        assertEquals(2.0, e.steps[2].target, 0.0)
        // retour : le nom ne cumule pas les suffixes
        assertEquals("Fractionné (course)", WorkoutConverter.convert(e, Sport.RUNNING).name)
    }

    @Test
    fun hrControlledResistance() {
        // zone 2 sur FC max 180 = 108..126
        assertEquals(7, WorkoutConverter.resistanceForZone(8, 135, 2, 180))
        assertEquals(9, WorkoutConverter.resistanceForZone(8, 100, 2, 180))
        assertEquals(8, WorkoutConverter.resistanceForZone(8, 118, 2, 180))
        assertEquals(15, WorkoutConverter.resistanceForZone(15, 90, 2, 180))
    }
}
