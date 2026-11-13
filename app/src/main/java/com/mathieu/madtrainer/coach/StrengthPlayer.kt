package com.mathieu.madtrainer.coach

/** Une étape du lecteur guidé : travail (répétitions ou chrono) ou repos. */
data class PlayerStep(
    val rest: Boolean,
    val exerciseIndex: Int,
    val exercise: StrengthExercise,
    val set: Int,
    val totalSets: Int,
    /** "gauche" / "droite" pour les exercices unilatéraux */
    val side: String? = null,
    /** Chrono en secondes ; null = répétitions, l'utilisateur valide quand c'est fait */
    val seconds: Int?,
) {
    val label: String
        get() = if (rest) {
            "Repos"
        } else {
            exercise.name + (side?.let { " ($it)" } ?: "")
        }

    /** Ce que dit la voix au début de l'étape. */
    fun spoken(next: PlayerStep?): String = if (rest) {
        "Repos ${seconds ?: 0} secondes." + (next?.let { " Ensuite : ${it.label}." } ?: "")
    } else {
        val what = exercise.reps?.let { "$it répétitions" } ?: "${seconds ?: 0} secondes"
        "${exercise.name}" + (side?.let { ", côté $it" } ?: "") + ". Série $set sur $totalSets, $what."
    }
}

object StrengthPlayer {
    /** Déroule une séance en étapes successives (séries, côtés, repos). */
    fun expand(session: StrengthSession): List<PlayerStep> {
        val out = mutableListOf<PlayerStep>()
        session.exercises.forEachIndexed { i, e ->
            val sides = if (e.per_side) listOf("gauche", "droite") else listOf<String?>(null)
            for (set in 1..e.sets.coerceAtLeast(1)) {
                sides.forEachIndexed { si, side ->
                    out += PlayerStep(false, i, e, set, e.sets, side, e.duration_sec)
                    // Petit temps de bascule entre les deux côtés, repos complet après la série
                    val isLastOfSet = si == sides.lastIndex
                    val restSec = if (isLastOfSet) e.rest_sec else 10
                    val isVeryLast = i == session.exercises.lastIndex && set == e.sets && isLastOfSet
                    if (restSec > 0 && !isVeryLast) out += PlayerStep(true, i, e, set, e.sets, null, restSec)
                }
            }
        }
        return out
    }

    /** Durée estimée (s), en comptant 3 s par répétition. */
    fun estimatedSeconds(session: StrengthSession): Int =
        expand(session).sumOf { it.seconds ?: ((it.exercise.reps ?: 10) * 3) }
}
