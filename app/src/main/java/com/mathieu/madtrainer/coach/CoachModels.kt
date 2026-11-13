package com.mathieu.madtrainer.coach

import com.mathieu.madtrainer.data.HrZones
import kotlinx.serialization.Serializable

@Serializable
enum class Goal(val label: String) {
    WEIGHT_LOSS("Perte de poids"),
    FITNESS("Remise en forme"),
    ENDURANCE("Endurance"),
    CARDIO_HEALTH("Santé cardio"),
    PERFORMANCE("Performance / puissance"),
}

@Serializable
enum class Level(val label: String) {
    BEGINNER("Débutant"),
    INTERMEDIATE("Intermédiaire"),
    ADVANCED("Avancé"),
}

@Serializable
enum class Sex(val label: String) { MALE("Homme"), FEMALE("Femme"), OTHER("Non précisé") }

@Serializable
data class UserProfile(
    val firstName: String = "",
    val sex: Sex = Sex.OTHER,
    val age: Int = 35,
    val weightKg: Double = 75.0,
    val heightCm: Int = 175,
    val targetWeightKg: Double? = null,
    val restingHr: Int? = null,
    val maxHr: Int? = null,
    val goal: Goal = Goal.FITNESS,
    val level: Level = Level.BEGINNER,
    val sessionsPerWeek: Int = 3,
    val sessionMinutes: Int = 30,
    val horizonWeeks: Int = 4,
    /** Goûts : intervalles, séances longues, musique… (texte libre) */
    val preferences: String = "",
    /** Contraintes : douleurs, genoux, horaires… (texte libre) */
    val constraints: String = "",
    /** Repères running : chronos récents, km par semaine… (texte libre) */
    val runningInfo: String = "",
    /** Zones cardio personnalisées (bornes basses des zones 1 à 5, bpm), ex. importées de Strava */
    val hrZoneLows: List<Int>? = null,
    /** Origine des zones personnalisées (« Strava »…) */
    val hrZonesSource: String? = null,
) {
    val bmi: Double get() = weightKg / ((heightCm / 100.0) * (heightCm / 100.0))

    /** FC max mesurée sinon estimée (formule de Tanaka : 208 − 0,7 × âge). */
    val estimatedMaxHr: Int get() = maxHr ?: (208 - 0.7 * age).toInt()

    /** Zones cardio utilisées partout dans l'app (personnalisées si importées, sinon % de la FC max). */
    val hrZones: HrZones get() = HrZones.of(estimatedMaxHr, hrZoneLows)
}

/** Séance proposée par le coach (format JSON demandé à l'IA). */
@Serializable
data class CoachStep(
    val name: String = "",
    val duration_sec: Int = 60,
    val mode: String = "RESISTANCE",
    val target: Double = 5.0,
    /** Running : distance de l'étape en mètres (prioritaire sur la durée). */
    val distance_m: Int? = null,
)

@Serializable
data class CoachWorkout(
    val name: String = "",
    /** Plan commun : "ELLIPTICAL" ou "RUNNING" */
    val sport: String = "",
    val week: Int = 1,
    val day: String = "",
    val purpose: String = "",
    val steps: List<CoachStep> = emptyList(),
)

@Serializable
data class CoachPlan(
    val plan_name: String = "Mon plan",
    val summary: String = "",
    val weekly_structure: String = "",
    val advice: List<String> = emptyList(),
    val nutrition: List<String> = emptyList(),
    val recovery: List<String> = emptyList(),
    val progression: String = "",
    val warnings: List<String> = emptyList(),
    val workouts: List<CoachWorkout> = emptyList(),
)

/** Plan importé et conservé dans l'app (avec la réponse complète pour relecture). */
@Serializable
data class SavedPlan(
    val createdAt: Long,
    val plan: CoachPlan,
    val rawResponse: String = "",
    val workoutIds: List<String> = emptyList(),
)
