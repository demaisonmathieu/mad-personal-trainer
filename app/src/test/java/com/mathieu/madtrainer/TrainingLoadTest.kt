package com.mathieu.madtrainer

import com.mathieu.madtrainer.coach.AdjustAction
import com.mathieu.madtrainer.coach.PlanAdjuster
import com.mathieu.madtrainer.data.BodyCache
import com.mathieu.madtrainer.data.FormStatus
import com.mathieu.madtrainer.data.HrZones
import com.mathieu.madtrainer.data.RestingHr
import com.mathieu.madtrainer.data.Sample
import com.mathieu.madtrainer.data.SessionRecord
import com.mathieu.madtrainer.data.Sport
import com.mathieu.madtrainer.data.StepMode
import com.mathieu.madtrainer.data.TrainingLoad
import com.mathieu.madtrainer.data.Workout
import com.mathieu.madtrainer.data.WorkoutStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TrainingLoadTest {
    private val day = 86_400_000L
    private val now = Instant.parse("2026-11-16T18:00:00Z").toEpochMilli()
    private val zones = HrZones.fromMax(180) // Z1 90, Z2 108, Z3 126, Z4 144, Z5 162

    private fun session(daysAgo: Int, min: Int, hr: Int?, sport: Sport = Sport.RUNNING, pace: Int? = 360, steps: List<WorkoutStep> = emptyList()) =
        SessionRecord(
            startedAt = now - daysAgo * day,
            durationSec = min * 60,
            avgHr = hr,
            sport = sport,
            avgPaceSecPerKm = if (sport == Sport.RUNNING) pace else null,
            distanceM = if (sport == Sport.RUNNING && pace != null) (min * 60 * 1000 / pace) else 0,
            avgPower = if (sport == Sport.ELLIPTICAL) 120 else 0,
            steps = steps,
            samples = if (hr != null) List(min * 60) { Sample(t = it, hr = hr) } else emptyList(),
        )

    @Test
    fun loadIsMinutesTimesZone() {
        assertEquals(60.0, TrainingLoad.sessionLoad(session(0, 30, 115), zones), 0.01) // zone 2
        assertEquals(60.0, TrainingLoad.sessionLoad(session(0, 30, null), zones), 0.01) // sans cardio : 2 par défaut
        assertEquals(120.0, TrainingLoad.sessionLoad(session(0, 30, 150), zones), 0.01) // zone 4
    }

    @Test
    fun steadyTrainingIsOptimalAndSpikeIsOverload() {
        // 3 séances de 45 min en zone 2 par semaine depuis 8 semaines
        val steady = (0 until 56).filter { it % 7 in listOf(1, 3, 5) }.map { session(it, 45, 115) }
        val t = TrainingLoad.report(steady, zones, null, now)
        assertEquals(FormStatus.OPTIMAL, t.status)
        assertEquals(8, t.weeks.size)

        // Même historique + une dernière semaine énorme
        val spike = steady + (0..5).map { session(it, 90, 150) }
        val t2 = TrainingLoad.report(spike, zones, null, now)
        assertEquals(FormStatus.OVERLOAD, t2.status)
        assertTrue(t2.tsb < 0)

        assertEquals(FormStatus.NOT_ENOUGH_DATA, TrainingLoad.report(listOf(session(1, 30, 115)), zones, null, now).status)
    }

    @Test
    fun efficiencyAndRestingHrTrends() {
        // Même cardio, allure 6'00 il y a 5-7 semaines puis 5'30 ces dernières semaines
        val before = (30..50 step 5).map { session(it, 40, 140, pace = 360) }
        val recent = (2..20 step 5).map { session(it, 40, 140, pace = 330) }
        val eff = TrainingLoad.efficiencyChange(before + recent, Sport.RUNNING, now)!!
        assertEquals(360.0 / 330.0 * 100 - 100, eff, 0.1)
        assertNull(TrainingLoad.efficiencyChange(recent, Sport.RUNNING, now))

        val rhr = (8..30).map { RestingHr(now - it * day, 55) } + (0..6).map { RestingHr(now - it * day, 63) }
        assertEquals(8, TrainingLoad.restingHrDelta(rhr, now))
        val t = TrainingLoad.report(before + recent, zones, BodyCache(restingHr = rhr), now)
        assertTrue(t.highlights.any { "FC de repos +8" in it })
    }

    // ------------------------------------------------------------------ ajustement du plan

    private val intervals = listOf(
        WorkoutStep("Échauffement", 600, StepMode.HR_ZONE, 1.0),
        WorkoutStep("Rapide", 1200, StepMode.HR_ZONE, 4.0),
        WorkoutStep("Retour au calme", 300, StepMode.HR_ZONE, 1.0),
    )

    /** Séance de fractionné avec le cardio [hr] pendant l'effort. */
    private fun intervalSession(daysAgo: Int, hr: Int): SessionRecord {
        val samples = List(2100) { t ->
            Sample(t = t, hr = if (t in 600 until 1800) hr else 100, step = if (t < 600) 0 else if (t < 1800) 1 else 2)
        }
        return SessionRecord(startedAt = now - daysAgo * day, durationSec = 2100, avgHr = 130, steps = intervals, samples = samples, sport = Sport.RUNNING, avgPaceSecPerKm = 330)
    }

    @Test
    fun tooHardSessionsTriggerReduce() {
        val history = (14..56).filter { it % 2 == 0 }.map { session(it, 40, 115) }
        val recent = listOf(intervalSession(1, 170), intervalSession(3, 172), intervalSession(5, 168))
        val sessions = history + recent
        val s = PlanAdjuster.signals(sessions, zones, now)
        assertEquals(3, s.tooHard)
        val adj = PlanAdjuster.decide(TrainingLoad.report(sessions, zones, null, now), s, plannedLastWeek = 3, doneLastWeek = 3)
        assertEquals(AdjustAction.REDUCE, adj.action)
    }

    @Test
    fun wellExecutedSessionsTriggerIncrease() {
        val history = (8..56).filter { it % 2 == 0 }.map { session(it, 35, 115) }
        val recent = listOf(intervalSession(1, 150), intervalSession(3, 152), intervalSession(5, 149))
        val sessions = history + recent
        val s = PlanAdjuster.signals(sessions, zones, now)
        assertEquals(0, s.tooHard)
        assertTrue((s.workAdherence ?: 0) >= 80)
        val trend = TrainingLoad.report(sessions, zones, null, now)
        val adj = PlanAdjuster.decide(trend, s, plannedLastWeek = 3, doneLastWeek = 3)
        assertEquals(trend.status.label, AdjustAction.INCREASE, adj.action)
        // Séances manquées : on consolide
        assertEquals(AdjustAction.KEEP, PlanAdjuster.decide(trend, s, plannedLastWeek = 4, doneLastWeek = 1).action)
    }

    @Test
    fun applyScalesBodyOfSessionOnly() {
        val w = Workout(name = "Fractionné", steps = intervals, notes = "Zone 4")
        val reduced = PlanAdjuster.apply(w, AdjustAction.REDUCE)
        assertEquals(600, reduced.steps[0].durationSec)
        assertEquals(960, reduced.steps[1].durationSec)
        assertEquals(3.0, reduced.steps[1].target, 0.0)
        assertEquals(300, reduced.steps[2].durationSec)
        assertTrue(reduced.notes.startsWith("Zone 4 Allégée"))
        val increased = PlanAdjuster.apply(w, AdjustAction.INCREASE)
        assertEquals(1320, increased.steps[1].durationSec)
        assertEquals(4.0, increased.steps[1].target, 0.0)
        // Un second ajustement remplace la mention au lieu de l'empiler
        assertEquals(1, PlanAdjuster.apply(reduced, AdjustAction.REDUCE).notes.split("Allégée").size - 1)
        assertEquals(w, PlanAdjuster.apply(w, AdjustAction.KEEP))
    }
}
