package com.mathieu.madtrainer.coach

import kotlinx.serialization.Serializable

/** Zones / problèmes proposés dans le formulaire (texte libre possible en plus). */
val PAIN_AREAS = listOf(
    "Sciatique", "Lombaires", "Dorsales", "Cervicales / nuque", "Épaules", "Hanches", "Genoux",
    "Chevilles", "Tendon d'Achille", "Périoste / tibias", "Voûte plantaire", "Poignets",
)

val STRENGTH_EQUIPMENT = listOf(
    "Aucun (poids du corps)", "Tapis", "Élastiques", "Haltères", "Kettlebell", "Swiss ball", "Barre de traction", "Rouleau de massage",
)

/** Ce que l'utilisateur déclare pour le renfo. */
@Serializable
data class StrengthProfile(
    val painAreas: List<String> = emptyList(),
    /** 0 = aucune douleur, 10 = maximale */
    val painLevel: Int = 0,
    /** Détails : depuis quand, côté, ce qui aggrave / soulage, avis médical… */
    val painDetails: String = "",
    val equipment: List<String> = listOf("Tapis"),
    val sessionsPerWeek: Int = 2,
    val sessionMinutes: Int = 20,
    /** Objectifs du renfo : gainage, prévention course, mobilité… */
    val goals: String = "",
)

/** Suivi de la douleur après une séance de renfo. */
@Serializable
data class PainLog(
    val date: Long,
    val sessionName: String,
    val painBefore: Int,
    val painAfter: Int,
    val note: String = "",
    /** Récap de la séance (0 / vide pour les anciens enregistrements) */
    val durationSec: Int = 0,
    val setsDone: Int = 0,
    val setsTotal: Int = 0,
    val exercises: List<StrengthLogExercise> = emptyList(),
)

/** Ce qui a été fait sur un exercice pendant la séance. */
@Serializable
data class StrengthLogExercise(
    val name: String,
    val setsDone: Int,
    val sets: Int,
    val reps: Int? = null,
    val durationSec: Int? = null,
    val perSide: Boolean = false,
) {
    /** ex. « 3/3 × 12 », « 2/3 × 30 s / côté » */
    val detail: String
        get() = "$setsDone/$sets × " + (reps?.toString() ?: "${durationSec ?: 0} s") + (if (perSide) " / côté" else "")
}

// ---------------------------------------------------------------- format JSON demandé à l'IA

@Serializable
data class StrengthExercise(
    val name: String = "",
    val sets: Int = 2,
    /** Répétitions (null si exercice en durée) */
    val reps: Int? = null,
    /** Durée de travail par série en secondes (gainage, étirement) */
    val duration_sec: Int? = null,
    val rest_sec: Int = 30,
    /** « chaque côté » pour les exercices unilatéraux */
    val per_side: Boolean = false,
    val instructions: String = "",
    val cues: String = "",
    val easier: String = "",
    val harder: String = "",
    val avoid_if: String = "",
    /** Identifiant de l'illustration dans la banque d'exercices (photos début / fin de mouvement), null si aucune */
    val image_id: String? = null,
)

@Serializable
data class StrengthSession(
    val name: String = "",
    val focus: String = "",
    val week: Int = 1,
    val day: String = "",
    val duration_min: Int = 20,
    val exercises: List<StrengthExercise> = emptyList(),
)

@Serializable
data class StrengthPlan(
    val plan_name: String = "Renforcement",
    val summary: String = "",
    val safety: List<String> = emptyList(),
    val red_flags: List<String> = emptyList(),
    val daily_habits: List<String> = emptyList(),
    val progression: String = "",
    val sessions: List<StrengthSession> = emptyList(),
)

@Serializable
data class SavedStrengthPlan(
    val createdAt: Long,
    val plan: StrengthPlan,
    val rawResponse: String = "",
)
