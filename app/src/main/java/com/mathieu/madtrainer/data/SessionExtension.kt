package com.mathieu.madtrainer.data

/**
 * Prolonger une séance en cours (« je me sens bien, je continue ») — calcul pur, commun à l'elliptique et à la course.
 * La séance en cours travaille sur une copie du programme : le programme enregistré n'est pas modifié.
 */
object SessionExtension {
    const val EXTRA_SEC = 5 * 60
    const val EXTRA_M = 1000
    const val EXTRA_NAME = "Prolongation endurance"
    const val FREE_NAME = "Prolongation libre"

    /** Étape « illimitée » ajoutée à la fin du programme ; raccourcie à la durée réelle à l'arrêt. */
    private const val FREE_SEC = 3 * 3600

    data class Extended(
        val workout: Workout,
        /** Index de l'étape en cours après modification. */
        val index: Int,
        /** Une nouvelle étape commence maintenant (à annoncer, chrono d'étape remis à zéro). */
        val newStep: Boolean,
    )

    fun isCoolDown(s: WorkoutStep): Boolean = s.name.lowercase().let { "calme" in it || "cool" in it }

    fun isFreeExtension(s: WorkoutStep?): Boolean = s?.name == FREE_NAME

    /**
     * +5 min (ou +1 km) : l'étape en cours est allongée. Pendant le retour au calme, on insère 5 min d'endurance
     * (zone 2) puis on refait un retour au calme complet.
     * @param stepElapsed secondes déjà passées dans l'étape ; [stepDistanceM] mètres déjà faits (course)
     */
    fun extend(w: Workout, index: Int, stepElapsed: Int, stepDistanceM: Int = 0): Extended {
        val cur = w.steps.getOrNull(index) ?: return Extended(w, index, false)
        if (isFreeExtension(cur)) return Extended(w, index, false)
        val steps = w.steps.toMutableList()
        if (!isCoolDown(cur)) {
            steps[index] = if (cur.distanceM != null) {
                cur.copy(distanceM = cur.distanceM + EXTRA_M, durationSec = cur.durationSec + EXTRA_SEC)
            } else {
                cur.copy(durationSec = cur.durationSec + EXTRA_SEC)
            }
            return Extended(w.copy(steps = steps), index, false)
        }
        // Retour au calme : on garde la partie déjà faite, on intercale l'endurance, puis un retour au calme complet
        val done = if (stepElapsed > 0) {
            listOf(cur.copy(durationSec = stepElapsed, distanceM = cur.distanceM?.let { stepDistanceM.coerceAtLeast(1) }))
        } else emptyList()
        val extra = WorkoutStep(EXTRA_NAME, EXTRA_SEC, StepMode.HR_ZONE, 2.0)
        val out = w.steps.take(index) + done + extra + cur + w.steps.drop(index + 1)
        return Extended(w.copy(steps = out), index + done.size, true)
    }

    /** Fin du programme atteinte : on continue en libre au lieu de s'arrêter. */
    fun continueFree(w: Workout): Workout =
        if (isFreeExtension(w.steps.lastOrNull())) w else w.copy(steps = w.steps + WorkoutStep(FREE_NAME, FREE_SEC, StepMode.FREE, 0.0))

    /** À l'arrêt : la prolongation libre prend sa durée réelle ([lastStepSec]) ; supprimée si quasi nulle. */
    fun trimFree(steps: List<WorkoutStep>, lastStepSec: Int): List<WorkoutStep> {
        val last = steps.lastOrNull()
        if (!isFreeExtension(last)) return steps
        return if (lastStepSec < 5) steps.dropLast(1) else steps.dropLast(1) + last!!.copy(durationSec = lastStepSec)
    }

    fun announceFree(): String =
        "Programme terminé, bravo ! Si tu te sens bien, continue : la séance continue en libre. Appuie sur Terminer quand tu veux arrêter."

    fun announceExtra(newStep: Boolean): String =
        if (newStep) "Cinq minutes d'endurance en plus, zone 2, puis retour au calme." else "Cinq minutes de plus sur cette étape."
}
