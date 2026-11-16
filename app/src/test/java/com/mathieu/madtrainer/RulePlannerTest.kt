package com.mathieu.madtrainer

import com.mathieu.madtrainer.coach.CoachParser
import com.mathieu.madtrainer.coach.Goal
import com.mathieu.madtrainer.coach.Level
import com.mathieu.madtrainer.coach.NutritionPlanner
import com.mathieu.madtrainer.coach.PlannerInput
import com.mathieu.madtrainer.coach.RulePlanner
import com.mathieu.madtrainer.coach.Sex
import com.mathieu.madtrainer.coach.StrengthProfile
import com.mathieu.madtrainer.coach.UserProfile
import com.mathieu.madtrainer.data.BodyCache
import com.mathieu.madtrainer.data.BodyMeasure
import com.mathieu.madtrainer.data.SessionRecord
import com.mathieu.madtrainer.data.Sport
import com.mathieu.madtrainer.data.StepMode
import com.mathieu.madtrainer.data.WeekPlanner
import com.mathieu.madtrainer.strava.StravaActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RulePlannerTest {
    private val day = 86_400_000L
    private val now = Instant.parse("2026-11-16T10:00:00Z").toEpochMilli()
    private val profile = UserProfile(
        sex = Sex.MALE, age = 42, weightKg = 82.0, heightCm = 178,
        goal = Goal.FITNESS, level = Level.INTERMEDIATE,
        sessionsPerWeek = 4, sessionMinutes = 45, horizonWeeks = 8,
    )

    private fun runs(n: Int, paceSecKm: Int, km: Double) = (1..n).map { i ->
        SessionRecord(
            startedAt = now - i * 3 * day, durationSec = (paceSecKm * km).toInt(),
            distanceM = (km * 1000).toInt(), sport = Sport.RUNNING,
        )
    }

    @Test
    fun planHasRightNumberOfSessionsAndValidDays() {
        val plan = RulePlanner.build(PlannerInput(profile, now = now))
        assertEquals(8 * 4, plan.workouts.size)
        (1..8).forEach { w ->
            val week = plan.workouts.filter { it.week == w }
            assertEquals(4, week.size)
            assertEquals(4, week.map { it.day }.distinct().size)
            assertTrue(week.all { WeekPlanner.dayIndex(it.day) >= 0 })
        }
        // Importable comme un plan d'IA, sans séance vide
        val workouts = CoachParser.toGlobalWorkouts(plan)
        assertEquals(32, workouts.size)
        assertTrue(workouts.all { it.steps.isNotEmpty() && it.totalSec >= 15 * 60 })
        assertTrue(workouts.any { it.sport == Sport.RUNNING } && workouts.any { it.sport == Sport.ELLIPTICAL })
    }

    @Test
    fun deloadWeekIsShorter() {
        val workouts = CoachParser.toGlobalWorkouts(RulePlanner.build(PlannerInput(profile, now = now)))
        fun weekMin(w: Int) = workouts.filter { it.planWeek == w }.sumOf { it.totalSec } / 60
        assertTrue("S3 ${weekMin(3)} / S4 ${weekMin(4)}", weekMin(4) < weekMin(3))
        assertTrue(weekMin(3) >= weekMin(1))
    }

    @Test
    fun strongLowerBodyPainMeansNoRunning() {
        val plan = RulePlanner.build(
            PlannerInput(profile, strength = StrengthProfile(painAreas = listOf("Genou droit"), painLevel = 7), now = now),
        )
        assertTrue(plan.workouts.all { it.sport == Sport.ELLIPTICAL.name })
        assertTrue(plan.summary.contains("elliptique"))
    }

    @Test
    fun highBmiLimitsRunningToOneEasySession() {
        val heavy = profile.copy(weightKg = 105.0)
        val plan = RulePlanner.build(PlannerInput(heavy, now = now))
        (1..8).forEach { w ->
            val runs = plan.workouts.filter { it.week == w && it.sport == Sport.RUNNING.name }
            assertEquals(1, runs.size)
            assertTrue(runs.single().steps.all { it.mode == "HR_ZONE" && it.target <= 2.0 })
        }
    }

    @Test
    fun pacesComeFromRecentRuns() {
        val perf = RulePlanner.snapshot(runs(5, 330, 8.0), emptyList(), now)
        assertEquals(330, perf.runPaceSecKm)
        assertEquals(44, perf.longestRunMin)
        val plan = RulePlanner.build(PlannerInput(profile, sessions = runs(5, 330, 8.0), now = now))
        val pace = CoachParser.toGlobalWorkouts(plan).flatMap { it.steps }.filter { it.mode == StepMode.PACE }
        assertTrue(pace.isNotEmpty())
        // Tempo 25 s/km et fractionné 50 s/km plus vite que l'allure habituelle
        assertTrue(pace.all { it.target == 305.0 || it.target == 280.0 })
    }

    @Test
    fun stravaDuplicatesAreIgnored() {
        val app = runs(1, 330, 8.0)
        val dup = StravaActivity(
            start_date = Instant.ofEpochMilli(app[0].startedAt + 60_000).toString(),
            sport_type = "Run", moving_time = 2640, distance = 8000.0,
        )
        val ride = StravaActivity(start_date = Instant.ofEpochMilli(now - day).toString(), sport_type = "Ride", moving_time = 3600, distance = 25000.0)
        val perf = RulePlanner.snapshot(app, listOf(dup, ride), now)
        assertEquals((2640 + 3600) / 60 / 4, perf.weeklyCardioMin)
        assertEquals(1, perf.runsLast4Weeks)
    }

    @Test
    fun beginnerStartsWithoutQualityAndWithRunWalk() {
        val plan = RulePlanner.build(PlannerInput(profile.copy(level = Level.BEGINNER), now = now))
        val week1 = plan.workouts.filter { it.week == 1 }
        assertTrue(week1.none { it.name.startsWith("Tempo") || it.name.startsWith("Fractionné") })
        assertTrue(week1.filter { it.sport == Sport.RUNNING.name }.any { w -> w.steps.any { it.name == "Marche" } })
        assertTrue(plan.workouts.filter { it.week == 3 }.any { it.name.startsWith("Tempo") || it.name.startsWith("Fractionné") })
    }

    // ------------------------------------------------------------------ nutrition

    @Test
    fun nutritionUsesScaleBmrAndDeficitForWeightLoss() {
        val p = profile.copy(goal = Goal.WEIGHT_LOSS, targetWeightKg = 76.0)
        val body = BodyCache(measures = listOf(BodyMeasure(time = now, weightKg = 82.0, bodyFatPct = 22.0, bmrKcal = 1750)))
        val n = NutritionPlanner.build(p, body)
        assertEquals(1750, n.bmr)
        assertEquals("balance connectée", n.bmrSource)
        assertTrue(n.adjustment < 0 && n.adjustment >= -500)
        assertTrue(n.trainingDayKcal > n.restDayKcal)
        assertTrue(n.restDayKcal >= 1750 * 1.1 - 50)
        // Protéines sur la masse maigre (82 × 0,78 × 2,4 ≈ 154 g)
        assertEquals(154.0, n.proteinG.toDouble(), 2.0)
        val total = n.proteinG * 4 + n.fatG * 9 + n.carbsRestG * 4
        assertEquals(n.restDayKcal.toDouble(), total.toDouble(), 10.0)
        assertNotNull(n.weeksToTarget)
        assertEquals(4, n.trainingMeals.size)
    }

    @Test
    fun nutritionMaintenanceWithMifflinFormula() {
        val n = NutritionPlanner.build(profile.copy(goal = Goal.PERFORMANCE), null)
        // Mifflin homme : 10 × 82 + 6,25 × 178 − 5 × 42 + 5 = 1727,5
        assertEquals(1728.0, n.bmr.toDouble(), 1.0)
        assertEquals(0, n.adjustment)
        assertNull(n.weeksToTarget)
        assertTrue(n.summaryLines().first().contains("kcal"))
    }
}
