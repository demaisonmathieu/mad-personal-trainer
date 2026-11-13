package com.mathieu.madtrainer.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class StepMode { FREE, RESISTANCE, POWER }

@Serializable
data class WorkoutStep(
    val name: String = "",
    val durationSec: Int = 60,
    val mode: StepMode = StepMode.RESISTANCE,
    /** Niveau de résistance (RESISTANCE) ou watts (POWER). Ignoré en FREE. */
    val target: Double = 5.0,
)

@Serializable
data class Workout(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Nouvelle séance",
    val steps: List<WorkoutStep> = emptyList(),
) {
    val totalSec: Int get() = steps.sumOf { it.durationSec }
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
)
