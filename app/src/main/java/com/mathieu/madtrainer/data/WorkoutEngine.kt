package com.mathieu.madtrainer.data

import kotlin.math.roundToInt

data class StepPosition(
    val index: Int,
    val stepElapsed: Int,
    val stepRemaining: Int,
    val finished: Boolean,
)

object WorkoutEngine {

    /** Position dans la séance après [elapsed] secondes. */
    fun position(w: Workout, elapsed: Int): StepPosition {
        if (w.steps.isEmpty()) return StepPosition(0, 0, 0, true)
        var acc = 0
        w.steps.forEachIndexed { i, s ->
            if (elapsed < acc + s.durationSec) {
                return StepPosition(i, elapsed - acc, acc + s.durationSec - elapsed, false)
            }
            acc += s.durationSec
        }
        val last = w.steps.last()
        return StepPosition(w.steps.lastIndex, last.durationSec, 0, true)
    }

    /** Construit l'enregistrement final d'une séance à partir des échantillons (1/s). */
    fun summarize(
        startedAt: Long,
        durationSec: Int,
        workoutName: String?,
        samples: List<Sample>,
    ): SessionRecord {
        val powers = samples.mapNotNull { it.power }
        val cad = samples.mapNotNull { it.cadence }.filter { it > 0 }
        val hrs = samples.mapNotNull { it.hr }.filter { it > 0 }
        fun delta(values: List<Int>) =
            if (values.size >= 2) (values.last() - values.first()).coerceAtLeast(0) else values.lastOrNull() ?: 0
        return SessionRecord(
            startedAt = startedAt,
            durationSec = durationSec,
            workoutName = workoutName,
            distanceM = delta(samples.mapNotNull { it.distanceM }),
            kcal = delta(samples.mapNotNull { it.kcal }),
            avgPower = if (powers.isEmpty()) 0 else powers.average().roundToInt(),
            maxPower = powers.maxOrNull() ?: 0,
            avgCadence = if (cad.isEmpty()) 0.0 else cad.average(),
            avgHr = if (hrs.isEmpty()) null else hrs.average().roundToInt(),
            samples = samples,
        )
    }

    fun defaultWorkouts(): List<Workout> = listOf(
        Workout(
            name = "Découverte 20 min",
            steps = listOf(
                WorkoutStep("Échauffement", 300, StepMode.RESISTANCE, 3.0),
                WorkoutStep("Tempo", 600, StepMode.RESISTANCE, 6.0),
                WorkoutStep("Retour au calme", 300, StepMode.RESISTANCE, 2.0),
            ),
        ),
        Workout(
            name = "Intervalles 30/30",
            steps = buildList {
                add(WorkoutStep("Échauffement", 300, StepMode.RESISTANCE, 4.0))
                repeat(8) {
                    add(WorkoutStep("Effort ${it + 1}", 30, StepMode.RESISTANCE, 12.0))
                    add(WorkoutStep("Récup ${it + 1}", 30, StepMode.RESISTANCE, 3.0))
                }
                add(WorkoutStep("Retour au calme", 300, StepMode.RESISTANCE, 2.0))
            },
        ),
        Workout(
            name = "Pyramide puissance (ERG)",
            steps = listOf(
                WorkoutStep("Échauffement", 300, StepMode.POWER, 80.0),
                WorkoutStep("Palier 1", 180, StepMode.POWER, 110.0),
                WorkoutStep("Palier 2", 180, StepMode.POWER, 140.0),
                WorkoutStep("Palier 3", 180, StepMode.POWER, 170.0),
                WorkoutStep("Palier 2", 180, StepMode.POWER, 140.0),
                WorkoutStep("Palier 1", 180, StepMode.POWER, 110.0),
                WorkoutStep("Retour au calme", 300, StepMode.POWER, 70.0),
            ),
        ),
    )
}

fun formatDuration(sec: Int): String {
    val s = sec.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val ss = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%02d:%02d".format(m, ss)
}
