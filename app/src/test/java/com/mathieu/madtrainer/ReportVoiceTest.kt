package com.mathieu.madtrainer

import com.mathieu.madtrainer.data.HrZones
import com.mathieu.madtrainer.data.Sample
import com.mathieu.madtrainer.data.SessionRecord
import com.mathieu.madtrainer.data.SessionReport
import com.mathieu.madtrainer.data.Sport
import com.mathieu.madtrainer.data.StepMode
import com.mathieu.madtrainer.data.VoiceCoach
import com.mathieu.madtrainer.data.WorkoutStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportVoiceTest {
    private val zones = HrZones.fromMax(180) // Z1 90, Z2 108, Z3 126, Z4 144, Z5 162

    private val steps = listOf(
        WorkoutStep("Échauffement", 300, StepMode.RESISTANCE, 3.0),
        WorkoutStep("Effort", 600, StepMode.RESISTANCE, 10.0),
        WorkoutStep("Zone 2", 600, StepMode.HR_ZONE, 2.0),
    )

    @Test
    fun preWarningAndTexts() {
        assertTrue(VoiceCoach.shouldPreWarn(steps[1], 570))
        assertTrue(!VoiceCoach.shouldPreWarn(steps[1], 569))
        assertTrue(!VoiceCoach.shouldPreWarn(WorkoutStep("court", 40), 10))
        assertEquals("Dans 30 secondes : Zone 2, zone 2, entre 108 et 126 pulsations.", VoiceCoach.preStepText(steps[2], zones))
        assertEquals("Dans 30 secondes, fin de la séance.", VoiceCoach.preStepText(null, zones))
        assertTrue(VoiceCoach.stepText(steps[2], 2, 3, zones).startsWith("Dernière étape. Zone 2."))
        val dist = WorkoutStep("1 km", 300, StepMode.PACE, 300.0, distanceM = 1000)
        assertTrue(VoiceCoach.shouldPreWarnDistance(dist, 905.0, false))
        assertTrue(!VoiceCoach.shouldPreWarnDistance(dist, 905.0, true))
        assertTrue(!VoiceCoach.shouldPreWarnDistance(dist, 800.0, false))
    }

    @Test
    fun hrCeilings() {
        assertEquals(126 + 8, VoiceCoach.hrCeiling(steps[2], steps, zones))
        // Échauffement : jusqu'à la fin de la zone 3
        assertEquals(144, VoiceCoach.hrCeiling(steps[0], steps, zones))
        // Étape la plus dure : plafond = FC max
        assertEquals(180, VoiceCoach.hrCeiling(steps[1], steps, zones))
        assertEquals(180, VoiceCoach.hrCeiling(null, emptyList(), zones))
    }

    @Test
    fun hrAlarmNeedsSustainAndCooldown() {
        val a = VoiceCoach.HrAlarm(sustainSec = 10, cooldownSec = 60)
        var alerts = 0
        for (t in 1..9) if (a.update(t, 150, 140)) alerts++
        assertEquals(0, alerts)
        if (a.update(10, 150, 140)) alerts++
        assertEquals(1, alerts)
        for (t in 11..69) if (a.update(t, 150, 140)) alerts++
        assertEquals(1, alerts)
        if (a.update(70, 150, 140)) alerts++
        assertEquals(2, alerts)
        assertTrue(!a.update(71, 120, 140))
        assertTrue(!a.update(72, null, 140))
    }

    private fun record(id: String, start: Long, power: Int, hr: Int, name: String = "Test") = SessionRecord(
        id = id,
        startedAt = start,
        durationSec = 1500,
        workoutName = name,
        avgPower = power,
        avgHr = hr,
        sport = Sport.ELLIPTICAL,
        steps = steps,
        samples = (1..1500).map { t ->
            val st = when { t <= 300 -> 0; t <= 900 -> 1; else -> 2 }
            Sample(t = t, power = power, hr = if (st == 2) 115 else hr, resistance = if (st == 0) 3.0 else 10.0, step = st)
        },
    )

    @Test
    fun reportComparesAndMeasuresAdherence() {
        val day = 24L * 3600 * 1000
        val base = 1_760_000_000_000L
        val history = listOf(
            record("a", base, 140, 140),
            record("b", base + 2 * day, 145, 140),
            record("c", base + 4 * day, 150, 140),
        )
        val rec = record("d", base + 6 * day, 165, 138)
        val r = SessionReport.build(rec, history + rec, zones)
        assertTrue(r.highlights.joinToString(), r.highlights.any { "+15 W" in it })
        assertTrue(r.highlights.any { "Efficacité cardio en hausse" in it })
        assertTrue(r.highlights.any { "Record : meilleure puissance" in it })
        assertEquals(100, r.steps[2].inTargetPct)
        assertEquals(100, r.steps[0].inTargetPct)
        assertEquals(25, r.zoneMinutes.sum())
        val prompt = SessionReport.prompt(rec, r, history + rec, zones, "- Homme, 40 ans")
        assertTrue("Zone 2 : zone 2 (108–126 bpm) → 115 bpm" in prompt)
        assertTrue("200 mots" in prompt)
    }
}
