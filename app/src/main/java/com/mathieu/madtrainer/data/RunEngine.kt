package com.mathieu.madtrainer.data

import kotlin.math.roundToInt

/** Logique pure de la course à pied (testable en JVM). */
object RunEngine {

    /** Bornes [min, max] en bpm de la zone 1..5 (50-60 %, …, 90-100 % de la FC max). */
    fun zoneRange(zone: Int, maxHr: Int): IntRange {
        val z = zone.coerceIn(1, 5)
        val lo = (maxHr * (0.4 + 0.1 * z)).roundToInt()
        val hi = (maxHr * (0.5 + 0.1 * z)).roundToInt()
        return lo..hi
    }

    fun zoneOf(hr: Int, maxHr: Int): Int = when {
        hr < maxHr * 0.6 -> 1
        hr < maxHr * 0.7 -> 2
        hr < maxHr * 0.8 -> 3
        hr < maxHr * 0.9 -> 4
        else -> 5
    }

    /** Allure moyenne (s/km) sur une distance (m) et un temps (s) ; null si trop peu de distance. */
    fun pace(distanceM: Double, seconds: Double): Int? =
        if (distanceM < 20 || seconds <= 0) null else (seconds / (distanceM / 1000.0)).roundToInt().takeIf { it in 120..1800 }

    /** Kcal estimées de course : ≈ 1 kcal / kg / km. */
    fun runKcal(distanceM: Double, weightKg: Double): Int = (distanceM / 1000.0 * weightKg).roundToInt()

    /** Une étape est-elle terminée ? (distance si définie, sinon durée) */
    fun stepDone(step: WorkoutStep, stepElapsedSec: Int, stepDistanceM: Double): Boolean =
        step.distanceM?.let { stepDistanceM >= it } ?: (stepElapsedSec >= step.durationSec)

    /** Ce qui reste de l'étape, en texte court. */
    fun stepRemainingText(step: WorkoutStep, stepElapsedSec: Int, stepDistanceM: Double): String =
        step.distanceM?.let { d -> formatDistance((d - stepDistanceM).coerceAtLeast(0.0)) }
            ?: formatDuration(step.durationSec - stepElapsedSec)

    /** Consigne lisible, utilisée à l'écran et par la voix. */
    fun targetText(step: WorkoutStep, maxHr: Int, spoken: Boolean = false): String = when (step.mode) {
        StepMode.PACE -> if (spoken) "allure ${spokenPace(step.target.roundToInt())} au kilomètre" else "allure ${formatPace(step.target.roundToInt())}"
        StepMode.HR_ZONE -> {
            val r = zoneRange(step.target.roundToInt(), maxHr)
            if (spoken) "zone ${step.target.roundToInt()}, entre ${r.first} et ${r.last} pulsations"
            else "zone ${step.target.roundToInt()} (${r.first}–${r.last} bpm)"
        }
        StepMode.FREE -> if (spoken) "à ton rythme" else "libre"
        StepMode.RESISTANCE -> "résistance ${step.target.roundToInt()}"
        StepMode.POWER -> "${step.target.roundToInt()} watts"
    }

    fun stepLengthText(step: WorkoutStep, spoken: Boolean = false): String =
        step.distanceM?.let { formatDistance(it.toDouble(), spoken) } ?: if (spoken) spokenDuration(step.durationSec) else formatDuration(step.durationSec)

    enum class Guidance { NONE, FASTER, SLOWER }

    /**
     * Faut-il accélérer / ralentir ? Tolérance ±15 s/km pour l'allure, ±3 bpm autour de la zone.
     */
    fun guidance(step: WorkoutStep, currentPace: Int?, hr: Int?, maxHr: Int): Guidance = when (step.mode) {
        StepMode.PACE -> when {
            currentPace == null -> Guidance.NONE
            currentPace > step.target + 15 -> Guidance.FASTER
            currentPace < step.target - 15 -> Guidance.SLOWER
            else -> Guidance.NONE
        }
        StepMode.HR_ZONE -> {
            if (hr == null) {
                Guidance.NONE
            } else {
                val r = zoneRange(step.target.roundToInt(), maxHr)
                when {
                    hr > r.last + 3 -> Guidance.SLOWER
                    hr < r.first - 3 -> Guidance.FASTER
                    else -> Guidance.NONE
                }
            }
        }
        else -> Guidance.NONE
    }

    fun defaultRunWorkouts(): List<Workout> = listOf(
        Workout(
            name = "Footing 30 min",
            sport = Sport.RUNNING,
            steps = listOf(
                WorkoutStep("Échauffement marche/trot", 300, StepMode.HR_ZONE, 1.0),
                WorkoutStep("Footing", 1200, StepMode.HR_ZONE, 2.0),
                WorkoutStep("Retour au calme", 300, StepMode.FREE, 0.0),
            ),
        ),
        Workout(
            name = "Fractionné 8 × 400 m",
            sport = Sport.RUNNING,
            steps = buildList {
                add(WorkoutStep("Échauffement", 600, StepMode.HR_ZONE, 2.0))
                repeat(8) {
                    add(WorkoutStep("400 m rapide ${it + 1}", 120, StepMode.HR_ZONE, 4.0, distanceM = 400))
                    add(WorkoutStep("Récup ${it + 1}", 90, StepMode.FREE, 0.0))
                }
                add(WorkoutStep("Retour au calme", 300, StepMode.HR_ZONE, 1.0))
            },
        ),
        Workout(
            name = "Sortie 5 km à allure régulière",
            sport = Sport.RUNNING,
            steps = listOf(
                WorkoutStep("Échauffement", 480, StepMode.HR_ZONE, 2.0, distanceM = 1000),
                WorkoutStep("Allure 6'00", 1800, StepMode.PACE, 360.0, distanceM = 5000),
                WorkoutStep("Retour au calme", 300, StepMode.FREE, 0.0),
            ),
        ),
    )
}

/** 345 → « 5'45" » */
fun formatPace(secPerKm: Int?): String =
    if (secPerKm == null || secPerKm <= 0) "--'--\"" else "%d'%02d\"".format(secPerKm / 60, secPerKm % 60)

fun spokenPace(secPerKm: Int): String {
    val m = secPerKm / 60
    val s = secPerKm % 60
    return if (s == 0) "$m minutes" else "$m minutes $s"
}

fun formatDistance(m: Double, spoken: Boolean = false): String = when {
    spoken && m >= 1000 -> "%.1f kilomètres".format(m / 1000).replace('.', ',').replace(",0 ", " ")
    spoken -> "${m.roundToInt()} mètres"
    m >= 1000 -> "%.2f km".format(m / 1000)
    else -> "${m.roundToInt()} m"
}

fun spokenDuration(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return when {
        m == 0 -> "$s secondes"
        s == 0 -> if (m == 1) "1 minute" else "$m minutes"
        else -> "$m minutes $s"
    }
}
