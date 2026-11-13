package com.mathieu.madtrainer

import com.mathieu.madtrainer.coach.CoachParser
import com.mathieu.madtrainer.coach.CoachPrompt
import com.mathieu.madtrainer.coach.Equipment
import com.mathieu.madtrainer.coach.Goal
import com.mathieu.madtrainer.coach.UserProfile
import com.mathieu.madtrainer.data.StepMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachTest {

    @Test
    fun promptContainsProfileAndSchema() {
        val p = UserProfile(age = 40, weightKg = 82.0, heightCm = 180, goal = Goal.WEIGHT_LOSS, preferences = "intervalles")
        val prompt = CoachPrompt.build(p, emptyList(), Equipment())
        assertTrue(prompt.contains("82 kg"))
        assertTrue(prompt.contains("Perte de poids"))
        assertTrue(prompt.contains("intervalles"))
        assertTrue(prompt.contains("\"workouts\""))
        assertTrue(prompt.contains("1 à 15"))
        assertTrue(prompt.contains("180 bpm")) // FC max Tanaka : 208 - 0,7 × 40
    }

    @Test
    fun parsesFencedJsonAndClampsTargets() {
        val response = """
            Voici ton programme ! Quelques conseils…

            ```json
            {
              "plan_name": "Objectif -5 kg",
              "summary": "Résumé",
              "advice": ["Bois de l'eau"],
              "workouts": [
                { "name": "S1 - Lundi", "week": 1, "day": "Lundi", "purpose": "Endurance",
                  "steps": [
                    { "name": "Échauffement", "duration_sec": 300, "mode": "RESISTANCE", "target": 3 },
                    { "name": "Effort", "duration_sec": 60, "mode": "resistance", "target": 40 },
                    { "name": "Tempo", "duration_sec": 600, "mode": "POWER", "target": 120 },
                    { "name": "Calme", "duration_sec": 180, "mode": "FREE", "target": 0 }
                  ] }
              ]
            }
            ```
            Bon courage !
        """.trimIndent()
        val plan = CoachParser.parse(response).getOrThrow()
        assertEquals("Objectif -5 kg", plan.plan_name)
        val w = CoachParser.toWorkouts(plan, 1, 15)
        assertEquals(1, w.size)
        assertEquals(1, w[0].planWeek)
        assertEquals(15.0, w[0].steps[1].target, 0.0)  // borné au max de la machine
        assertEquals(StepMode.POWER, w[0].steps[2].mode)
        assertEquals(StepMode.FREE, w[0].steps[3].mode)
        assertEquals(1140, w[0].totalSec)
    }

    @Test
    fun rejectsResponseWithoutJson() {
        assertTrue(CoachParser.parse("Désolé, je ne peux pas.").isFailure)
    }
}
