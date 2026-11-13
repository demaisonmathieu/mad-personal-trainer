package com.mathieu.madtrainer.data

import kotlin.math.roundToInt

/**
 * Textes et règles du coach vocal (elliptique et course) — logique pure, testable en JVM.
 * - annonce de chaque étape ;
 * - préavis 30 s avant l'étape suivante ;
 * - alerte quand le cardio reste trop haut pour l'étape en cours.
 */
object VoiceCoach {
    const val PRE_WARN_SEC = 30

    private fun isEasy(step: WorkoutStep): Boolean =
        step.name.lowercase().let { n -> listOf("chauff", "calme", "récup", "recup", "marche", "repos").any { it in n } }

    /** Plafond cardio (bpm) au-delà duquel l'étape est jugée trop intense. */
    fun hrCeiling(step: WorkoutStep?, steps: List<WorkoutStep>, zones: HrZones): Int {
        if (step == null) return zones.max
        val ceiling = when (step.mode) {
            // Étape en zone : tolérance de 8 bpm (le guidage « ralentis » prend le relais en dessous)
            StepMode.HR_ZONE -> zones.range(step.target.roundToInt()).last + 8
            StepMode.FREE -> zones.range(if (isEasy(step)) 3 else 4).last
            // Résistance, watts, allure : une zone de marge au-dessus de la zone équivalente
            else -> {
                val z = if (isEasy(step)) 2 else WorkoutConverter.zoneFor(step, steps)
                zones.range((z + 1).coerceAtMost(5)).last
            }
        }
        return ceiling.coerceAtMost(zones.max)
    }

    fun stepText(step: WorkoutStep, index: Int, count: Int, zones: HrZones): String =
        (if (index == count - 1 && count > 1) "Dernière étape. " else "Étape ${index + 1} sur $count. ") +
            "${step.name}. ${RunEngine.stepLengthText(step, spoken = true)}, ${RunEngine.targetText(step, zones, spoken = true)}."

    /** Préavis avant l'étape suivante (ou la fin de séance si [next] est null). */
    fun preStepText(next: WorkoutStep?, zones: HrZones, distance: Boolean = false): String {
        val prefix = if (distance) "Dans 100 mètres" else "Dans 30 secondes"
        return if (next == null) "$prefix, fin de la séance." else "$prefix : ${next.name}, ${RunEngine.targetText(next, zones, spoken = true)}."
    }

    /** Faut-il prévenir maintenant ? (étape en durée d'au moins 1 min, pile à 30 s de la fin) */
    fun shouldPreWarn(step: WorkoutStep, stepElapsedSec: Int): Boolean =
        step.distanceM == null && step.durationSec >= 60 && step.durationSec - stepElapsedSec == PRE_WARN_SEC

    /** Étape en distance : prévenir à 100 m de la fin (étapes de 400 m et plus). */
    fun shouldPreWarnDistance(step: WorkoutStep, stepDistanceM: Double, alreadyWarned: Boolean): Boolean {
        val d = step.distanceM ?: return false
        return !alreadyWarned && d >= 400 && d - stepDistanceM <= 100
    }

    fun hrAlertText(hr: Int, running: Boolean): String =
        "Attention, cardio $hr, trop élevé pour cet exercice. " +
            if (running) "Ralentis, marche quelques instants si besoin." else "Baisse l'intensité ou la cadence."

    /**
     * Détecte un cardio trop haut maintenu : il faut [sustainSec] secondes consécutives au-dessus du plafond,
     * puis au moins [cooldownSec] secondes entre deux alertes.
     */
    class HrAlarm(private val sustainSec: Int = 10, private val cooldownSec: Int = 60) {
        private var above = 0
        private var lastAlertT = Int.MIN_VALUE / 2

        fun update(t: Int, hr: Int?, ceiling: Int): Boolean {
            if (hr == null || hr <= 0 || hr <= ceiling) {
                above = 0
                return false
            }
            above++
            if (above >= sustainSec && t - lastAlertT >= cooldownSec) {
                lastAlertT = t
                return true
            }
            return false
        }

        fun reset() {
            above = 0
            lastAlertT = Int.MIN_VALUE / 2
        }
    }
}
