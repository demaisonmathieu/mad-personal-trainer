package com.mathieu.madtrainer.data

import kotlinx.serialization.Serializable
import java.util.UUID

/** Mode global de l'app. */
@Serializable
enum class Sport(val label: String) { ELLIPTICAL("Elliptique"), RUNNING("Running") }

@Serializable
enum class StepMode {
    FREE,

    /** Elliptique : niveau de résistance. */
    RESISTANCE,

    /** Elliptique : watts. */
    POWER,

    /** Running : allure cible en secondes par km. */
    PACE,

    /** Running : zone cardio 1 à 5. */
    HR_ZONE,
}

@Serializable
data class WorkoutStep(
    val name: String = "",
    /** Durée de l'étape (ou estimation si l'étape est définie par une distance). */
    val durationSec: Int = 60,
    val mode: StepMode = StepMode.RESISTANCE,
    /** Résistance (RESISTANCE), watts (POWER), s/km (PACE), zone 1-5 (HR_ZONE). Ignoré en FREE. */
    val target: Double = 5.0,
    /** Running : si renseigné, l'étape se termine à cette distance (m) et non à la durée. */
    val distanceM: Int? = null,
)

@Serializable
data class Workout(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Nouvelle séance",
    val steps: List<WorkoutStep> = emptyList(),
    /** Renseignés pour les séances proposées par le coach. */
    val planWeek: Int? = null,
    val planDay: String? = null,
    val notes: String = "",
    val fromCoach: Boolean = false,
    val sport: Sport = Sport.ELLIPTICAL,
) {
    val totalSec: Int get() = steps.sumOf { it.durationSec }
    val totalDistanceM: Int get() = steps.sumOf { it.distanceM ?: 0 }
}

@Serializable
data class Sample(
    val t: Int,
    val power: Int? = null,
    val cadence: Double? = null,
    val speed: Double? = null,
    val hr: Int? = null,
    val resistance: Double? = null,
    val distanceM: Int? = null,
    val kcal: Int? = null,
    /** Running */
    val lat: Double? = null,
    val lon: Double? = null,
    val paceSecPerKm: Int? = null,
    /** Index de l'étape du programme en cours */
    val step: Int? = null,
)

@Serializable
data class SessionRecord(
    val id: String = UUID.randomUUID().toString(),
    val startedAt: Long,
    val durationSec: Int,
    val workoutName: String? = null,
    val distanceM: Int = 0,
    val kcal: Int = 0,
    val avgPower: Int = 0,
    val maxPower: Int = 0,
    val avgCadence: Double = 0.0,
    val avgHr: Int? = null,
    val samples: List<Sample> = emptyList(),
    val sport: Sport = Sport.ELLIPTICAL,
    val avgPaceSecPerKm: Int? = null,
    val maxHr: Int? = null,
    /** Étapes prévues (pour mesurer le respect des consignes) */
    val steps: List<WorkoutStep> = emptyList(),
    /** Analyse de la séance rédigée par l'IA */
    val aiReport: String? = null,
)
