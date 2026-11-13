package com.mathieu.madtrainer.data

import kotlin.math.roundToInt

/**
 * Passage d'une séance d'un sport à l'autre (« Faire sur l'autre machine »).
 * Principe : la zone cardio est la langue commune. Les consignes propres à un sport
 * (résistance / watts pour l'elliptique, allure pour la course) deviennent des zones,
 * selon leur intensité relative dans la séance.
 */
object WorkoutConverter {

    fun convert(w: Workout, target: Sport): Workout {
        if (w.sport == target) return w
        val specific = w.steps.filter { it.mode == StepMode.RESISTANCE || it.mode == StepMode.POWER || it.mode == StepMode.PACE }
        val steps = w.steps.map { s ->
            when (s.mode) {
                StepMode.RESISTANCE, StepMode.POWER, StepMode.PACE -> s.copy(
                    mode = StepMode.HR_ZONE,
                    target = zoneFor(s, specific).toDouble(),
                    // L'elliptique ne mesure pas la distance de façon fiable : étape en durée
                    distanceM = if (target == Sport.ELLIPTICAL) null else s.distanceM,
                )
                StepMode.HR_ZONE, StepMode.FREE -> if (target == Sport.ELLIPTICAL) s.copy(distanceM = null) else s
            }
        }
        val suffix = if (target == Sport.RUNNING) " (course)" else " (elliptique)"
        return w.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = w.name.removeSuffix(" (course)").removeSuffix(" (elliptique)") + suffix,
            steps = steps,
            sport = target,
            fromCoach = false,
        )
    }

    /** Intensité relative 0..1 de l'étape parmi les étapes « spécifiques » de la séance → zone 2 à 4 (5 si très court et intense). */
    private fun zoneFor(s: WorkoutStep, all: List<WorkoutStep>): Int {
        fun intensity(x: WorkoutStep): Double = when (x.mode) {
            StepMode.PACE -> 1000.0 / x.target.coerceAtLeast(1.0) // plus l'allure est petite, plus c'est intense
            else -> x.target
        }
        val same = all.filter { it.mode == s.mode }
        val lo = same.minOf { intensity(it) }
        val hi = same.maxOf { intensity(it) }
        val r = if (hi - lo < 1e-6) 0.5 else (intensity(s) - lo) / (hi - lo)
        val z = when {
            r < 0.2 -> 2
            r < 0.6 -> 3
            r < 0.9 -> 4
            else -> if (s.durationSec <= 120) 5 else 4
        }
        // Une étape très douce (échauffement / retour au calme) reste en zone 1-2
        return if (r < 0.05 && s.name.lowercase().let { "chauff" in it || "calme" in it || "récup" in it }) 1 else z
    }

    /**
     * Pilotage de l'elliptique par le cardio : nouveau niveau de résistance pour rester dans la zone.
     * Ajustement d'un niveau à la fois, avec une marge de 3 bpm pour éviter de « pomper ».
     */
    fun resistanceForZone(currentLevel: Int, hr: Int, zone: Int, maxHr: Int, min: Int = 1, max: Int = 15): Int {
        val r = RunEngine.zoneRange(zone, maxHr)
        return when {
            hr > r.last + 3 -> (currentLevel - 1).coerceIn(min, max)
            hr < r.first - 3 -> (currentLevel + 1).coerceIn(min, max)
            else -> currentLevel
        }
    }

    /** Niveau de départ suggéré pour une zone (avant que le cardio ne réagisse). */
    fun startLevelForZone(zone: Int, max: Int = 15): Int = (max * (0.15 + 0.12 * zone)).roundToInt().coerceIn(1, max)
}
