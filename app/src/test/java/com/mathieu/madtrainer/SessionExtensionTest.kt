package com.mathieu.madtrainer

import com.mathieu.madtrainer.data.SessionExtension
import com.mathieu.madtrainer.data.StepMode
import com.mathieu.madtrainer.data.Workout
import com.mathieu.madtrainer.data.WorkoutEngine
import com.mathieu.madtrainer.data.WorkoutStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionExtensionTest {
    private val w = Workout(
        name = "Endurance",
        steps = listOf(
            WorkoutStep("Échauffement", 300, StepMode.HR_ZONE, 1.0),
            WorkoutStep("Endurance", 1200, StepMode.HR_ZONE, 2.0),
            WorkoutStep("Retour au calme", 300, StepMode.HR_ZONE, 1.0),
        ),
    )

    @Test
    fun extendsCurrentStep() {
        val ext = SessionExtension.extend(w, 1, 600)
        assertFalse(ext.newStep)
        assertEquals(1, ext.index)
        assertEquals(1500, ext.workout.steps[1].durationSec)
        assertEquals(1800 + 300, ext.workout.totalSec)
        // L'elliptique se repère au temps total : toujours dans l'étape 2, 5 min de plus à faire
        val pos = WorkoutEngine.position(ext.workout, 300 + 600)
        assertEquals(1, pos.index)
        assertEquals(900, pos.stepRemaining)
    }

    @Test
    fun extendsDistanceStepByOneKm() {
        val run = Workout(steps = listOf(WorkoutStep("Tempo", 1200, StepMode.PACE, 300.0, distanceM = 4000)))
        val ext = SessionExtension.extend(run, 0, 300, 1000)
        assertEquals(5000, ext.workout.steps[0].distanceM)
    }

    @Test
    fun duringCoolDownInsertsEnduranceThenFullCoolDown() {
        // 2 min déjà faites dans le retour au calme
        val ext = SessionExtension.extend(w, 2, 120)
        assertTrue(ext.newStep)
        assertEquals(3, ext.index)
        val names = ext.workout.steps.map { it.name }
        assertEquals(listOf("Échauffement", "Endurance", "Retour au calme", SessionExtension.EXTRA_NAME, "Retour au calme"), names)
        assertEquals(120, ext.workout.steps[2].durationSec)
        assertEquals(2.0, ext.workout.steps[3].target, 0.0)
        // Au même temps écoulé, on est au début de la prolongation
        val pos = WorkoutEngine.position(ext.workout, 300 + 1200 + 120)
        assertEquals(3, pos.index)
        assertEquals(0, pos.stepElapsed)
        // Retour au calme pas encore commencé : on insère avant lui, sans morceau vide
        val atStart = SessionExtension.extend(w, 2, 0)
        assertEquals(2, atStart.index)
        assertEquals(4, atStart.workout.steps.size)
    }

    @Test
    fun continueFreeAfterEndAndTrimAtStop() {
        val free = SessionExtension.continueFree(w)
        assertEquals(4, free.steps.size)
        assertTrue(SessionExtension.isFreeExtension(free.steps.last()))
        assertSame(free, SessionExtension.continueFree(free))
        assertFalse(WorkoutEngine.position(free, 1800 + 600).finished)
        // Pas de « +5 min » pendant la prolongation libre
        assertSame(free, SessionExtension.extend(free, 3, 60).workout)

        val trimmed = SessionExtension.trimFree(free.steps, 420)
        assertEquals(420, trimmed.last().durationSec)
        assertEquals(3, SessionExtension.trimFree(free.steps, 2).size)
        assertEquals(w.steps, SessionExtension.trimFree(w.steps, 100))
    }
}
