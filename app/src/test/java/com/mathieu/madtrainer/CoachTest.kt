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
    fun parsesRunningPlan() {
        val response = """
            ```json
            { "plan_name": "10 km", "workouts": [
              { "name": "Fractionné", "week": 2, "day": "Mardi", "steps": [
                { "name": "Échauffement", "duration_sec": 600, "distance_m": null, "mode": "HR_ZONE", "target": 2 },
                { "name": "1000 m", "duration_sec": 300, "distance_m": 1000, "mode": "PACE", "target": 300 },
                { "name": "Récup", "duration_sec": 120, "mode": "FREE", "target": 0 }
              ] } ] }
            ```
        """.trimIndent()
        val plan = CoachParser.parse(response).getOrThrow()
        val w = CoachParser.toWorkouts(plan, sport = com.mathieu.madtrainer.data.Sport.RUNNING)
        assertEquals(com.mathieu.madtrainer.data.Sport.RUNNING, w[0].sport)
        assertEquals(StepMode.HR_ZONE, w[0].steps[0].mode)
        assertEquals(1000, w[0].steps[1].distanceM)
        assertEquals(300, w[0].steps[1].durationSec) // 1 km à 5'00
        assertEquals(StepMode.FREE, w[0].steps[2].mode)
    }

    @Test
    fun rejectsResponseWithoutJson() {
        assertTrue(CoachParser.parse("Désolé, je ne peux pas.").isFailure)
    }
}

class StravaSummaryTest {
    @Test
    fun summaryGroupsBySportAndFeedsPrompt() {
        val acts = listOf(
            com.mathieu.madtrainer.strava.StravaActivity(id = 1, sport_type = "Run", start_date = "2026-11-10T07:00:00Z", start_date_local = "2026-11-10T08:00:00", moving_time = 1800, distance = 5200.0, average_heartrate = 152.0),
            com.mathieu.madtrainer.strava.StravaActivity(id = 2, sport_type = "Ride", start_date = "2026-11-08T07:00:00Z", start_date_local = "2026-11-08T08:00:00", moving_time = 5400, distance = 42000.0, total_elevation_gain = 380.0),
        )
        val txt = com.mathieu.madtrainer.strava.StravaSummary.describe(com.mathieu.madtrainer.strava.StravaCache(activities = acts))!!
        assertTrue(txt.contains("Course à pied : 1 séances"))
        assertTrue(txt.contains("Vélo : 1 séances, 1,5 h, 42,0 km"))
        assertTrue(txt.contains("D+ 380 m"))
        val prompt = CoachPrompt.build(UserProfile(), emptyList(), Equipment(), otherActivities = txt)
        assertTrue(prompt.contains("## Autres activités (Strava)"))
        org.junit.Assert.assertNull(com.mathieu.madtrainer.strava.StravaSummary.describe(com.mathieu.madtrainer.strava.StravaCache()))
    }
}
