package com.mathieu.madtrainer.coach

import com.mathieu.madtrainer.data.BodyCache
import com.mathieu.madtrainer.data.BodyStats
import com.mathieu.madtrainer.data.StepMode
import com.mathieu.madtrainer.data.Workout
import com.mathieu.madtrainer.data.Sport
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Un repas type, avec ses apports et un exemple de contenu. */
data class Meal(val name: String, val kcal: Int, val proteinG: Int, val carbsG: Int, val fatG: Int, val example: String)

/** Plan nutritionnel calculé par règles (sans IA). */
data class NutritionPlan(
    val bmr: Int,
    /** D'où vient le métabolisme de base : balance, masse maigre ou formule. */
    val bmrSource: String,
    /** Dépense hors sport (métabolisme × activité quotidienne). */
    val dailyBase: Int,
    /** Dépense moyenne d'une séance. */
    val sessionKcal: Int,
    val sessionsPerWeek: Int,
    /** Écart visé par rapport à la dépense (négatif = déficit). */
    val adjustment: Int,
    val trainingDayKcal: Int,
    val restDayKcal: Int,
    val proteinG: Int,
    val fatG: Int,
    val carbsTrainingG: Int,
    val carbsRestG: Int,
    val waterL: Double,
    val trainingMeals: List<Meal>,
    val restMeals: List<Meal>,
    val tips: List<String>,
    /** Semaines estimées pour atteindre le poids visé (perte). */
    val weeksToTarget: Int? = null,
) {
    /** Résumé court pour le plan d'entraînement. */
    fun summaryLines(): List<String> = buildList {
        add("Jours de séance : environ $trainingDayKcal kcal ; jours de repos : environ $restDayKcal kcal.")
        add("Protéines $proteinG g, lipides $fatG g, glucides $carbsTrainingG g (séance) / $carbsRestG g (repos) par jour.")
        add("Eau : environ ${BodyStats.fmt(waterL)} L par jour, plus 0,5 L par heure de séance.")
        addAll(tips.take(2))
        add("Détail des repas dans l'écran Corps (icône balance).")
    }
}

object NutritionPlanner {

    /** Facteur d'activité hors sport (travail plutôt assis, déplacements courants). */
    const val DAILY_ACTIVITY = 1.35

    /** MET approximatif par zone cardio (course / elliptique). */
    private fun met(zone: Int, run: Boolean): Double =
        if (run) listOf(6.0, 8.5, 10.0, 11.5, 13.0)[zone.coerceIn(1, 5) - 1] else listOf(4.5, 6.0, 7.5, 9.0, 10.0)[zone.coerceIn(1, 5) - 1]

    /** Dépense estimée d'une séance (kcal), d'après l'intensité de chaque étape. */
    fun sessionKcal(w: Workout, weightKg: Double): Int {
        val run = w.sport == Sport.RUNNING
        val kcal = w.steps.sumOf { s ->
            val m = when (s.mode) {
                StepMode.HR_ZONE -> met(s.target.toInt(), run)
                StepMode.PACE -> if (s.target < 300) 12.0 else if (s.target < 360) 10.5 else 9.0
                StepMode.RESISTANCE -> 4.5 + s.target.coerceIn(1.0, 20.0) * 0.35
                StepMode.POWER -> 3.5 + s.target / 25.0
                StepMode.FREE -> if (run) 8.0 else 6.0
            }
            m * weightKg * s.durationSec / 3600.0
        }
        return kcal.roundToInt()
    }

    /** Métabolisme de base : balance > masse maigre (Katch-McArdle) > Mifflin-St Jeor. */
    fun bmr(p: UserProfile, body: BodyCache?): Pair<Int, String> {
        val last = body?.let { BodyStats.latest(it.measures) }
        last?.bmrKcal?.takeIf { it in 900..4000 }?.let { return it to "balance connectée" }
        val weight = last?.weightKg ?: p.weightKg
        val lean = last?.leanMassKg ?: last?.bodyFatPct?.let { weight * (1 - it / 100) }
        if (lean != null && lean in 25.0..120.0) return (370 + 21.6 * lean).roundToInt() to "masse maigre mesurée"
        val sexTerm = when (p.sex) { Sex.MALE -> 5.0; Sex.FEMALE -> -161.0; Sex.OTHER -> -78.0 }
        return (10 * weight + 6.25 * p.heightCm - 5 * p.age + sexTerm).roundToInt() to "formule (poids, taille, âge)"
    }

    /**
     * @param planWorkouts séances d'une semaine du plan (pour estimer la dépense des séances) ; vide = estimation
     *   à partir de la durée habituelle des séances.
     */
    fun build(p: UserProfile, body: BodyCache?, planWorkouts: List<Workout> = emptyList()): NutritionPlan {
        val last = body?.let { BodyStats.latest(it.measures) }
        val weight = last?.weightKg ?: p.weightKg
        val (bmr, source) = bmr(p, body)
        val base = (bmr * DAILY_ACTIVITY).roundToInt()

        val sessions = if (planWorkouts.isNotEmpty()) planWorkouts.size else p.sessionsPerWeek.coerceIn(1, 7)
        val session = if (planWorkouts.isNotEmpty()) {
            planWorkouts.map { sessionKcal(it, weight) }.average().roundToInt()
        } else {
            (7.0 * weight * p.sessionMinutes / 60.0).roundToInt()
        }

        // Objectif : perte (déficit modéré), sinon maintien
        val target = p.targetWeightKg
        val wantsLoss = (p.goal == Goal.WEIGHT_LOSS || (target != null && target < weight - 0.5)) && (target == null || target < weight)
        val avgExpense = base + session * sessions / 7.0
        val adjustment = when {
            wantsLoss && p.goal == Goal.WEIGHT_LOSS -> -minOf(500.0, avgExpense * 0.2).roundToInt()
            wantsLoss -> -minOf(300.0, avgExpense * 0.12).roundToInt()
            else -> 0
        }
        val floor = maxOf((bmr * 1.1).roundToInt(), if (p.sex == Sex.FEMALE) 1200 else 1500)
        val trainingDay = round50((base + session + adjustment).coerceAtLeast(floor))
        val restDay = round50((base + adjustment).coerceAtLeast(floor))

        // Protéines : sur la masse maigre si connue, sinon sur un poids de référence (plafonné à IMC 27)
        val heightM = p.heightCm / 100.0
        val refWeight = minOf(weight, 27 * heightM * heightM)
        val lean = last?.leanMassKg ?: last?.bodyFatPct?.let { weight * (1 - it / 100) }
        val protein = when {
            lean != null -> lean * if (wantsLoss) 2.4 else 2.1
            else -> refWeight * if (wantsLoss) 2.0 else 1.6
        }.roundToInt()
        val fat = maxOf(refWeight * 0.8, restDay * 0.25 / 9).roundToInt()
        val carbsRest = ((restDay - protein * 4 - fat * 9) / 4.0).roundToInt().coerceAtLeast(80)
        val carbsTraining = ((trainingDay - protein * 4 - fat * 9) / 4.0).roundToInt().coerceAtLeast(carbsRest)

        val water = weight * 0.035
        val weeksToTarget = if (wantsLoss && target != null && adjustment < 0) {
            ceil((weight - target) * 7700 / (-adjustment * 7.0)).toInt()
        } else null

        return NutritionPlan(
            bmr = bmr,
            bmrSource = source,
            dailyBase = base,
            sessionKcal = session,
            sessionsPerWeek = sessions,
            adjustment = adjustment,
            trainingDayKcal = trainingDay,
            restDayKcal = restDay,
            proteinG = protein,
            fatG = fat,
            carbsTrainingG = carbsTraining,
            carbsRestG = carbsRest,
            waterL = Math.round(water * 10) / 10.0,
            trainingMeals = meals(trainingDay, protein, carbsTraining, fat, training = true),
            restMeals = meals(restDay, protein, carbsRest, fat, training = false),
            tips = tips(p, wantsLoss, session, last?.bodyFatPct),
            weeksToTarget = weeksToTarget,
        )
    }

    private fun round50(v: Int) = (v / 50.0).roundToInt() * 50

    /** Répartition sur 4 repas et exemples de portions (valeurs indicatives). */
    private fun meals(kcal: Int, protein: Int, carbs: Int, fat: Int, training: Boolean): List<Meal> {
        data class Split(val name: String, val k: Double, val p: Double, val c: Double, val f: Double)

        val splits = listOf(
            Split("Petit-déjeuner", 0.25, 0.25, 0.28, 0.25),
            Split("Déjeuner", 0.35, 0.35, 0.35, 0.35),
            Split(if (training) "Collation (autour de la séance)" else "Collation", if (training) 0.12 else 0.10, 0.12, if (training) 0.17 else 0.12, 0.08),
            Split("Dîner", 0.28, 0.28, 0.20, 0.32),
        )
        return splits.map { s ->
            val pg = (protein * s.p).roundToInt()
            val cg = (carbs * s.c).roundToInt()
            val fg = (fat * s.f).roundToInt()
            Meal(s.name, (kcal * s.k).roundToInt(), pg, cg, fg, example(s.name, pg, cg, training))
        }
    }

    private fun example(name: String, proteinG: Int, carbsG: Int, training: Boolean): String {
        fun r5(v: Double) = ((v / 5).roundToInt() * 5).coerceAtLeast(5)
        return when {
            name.startsWith("Petit") ->
                "${r5(carbsG * 0.6 / 0.6)} g de flocons d'avoine ou ${r5(carbsG * 0.6 / 0.5)} g de pain complet, " +
                    "${r5(proteinG * 0.7 / 0.1)} g de skyr ou fromage blanc, 1 fruit"
            name.startsWith("Déjeuner") ->
                "${r5(proteinG * 0.8 / 0.25)} g de poulet, poisson ou tofu (poids cuit), " +
                    "${r5(carbsG * 0.8 / 0.28)} g de riz, pâtes ou pommes de terre (poids cuit), légumes à volonté, 1 c. à soupe d'huile d'olive"
            name.startsWith("Collation") ->
                if (training) "1 banane + 1 yaourt, ou 1 tartine de pain et miel avant ; après : ${r5(proteinG / 0.1)} g de skyr ou 1 verre de lait"
                else "1 fruit + ${r5(proteinG / 0.1)} g de skyr ou une poignée d'amandes"
            else ->
                "${r5(proteinG * 0.8 / 0.13)} g d'œufs (≈ ${maxOf(1, (proteinG * 0.8 / 6.5).roundToInt())} œufs) ou ${r5(proteinG * 0.8 / 0.22)} g de poisson, " +
                    "${r5(carbsG * 0.8 / 0.2)} g de légumineuses ou ${r5(carbsG * 0.8 / 0.28)} g de féculents cuits, légumes à volonté"
        }
    }

    private fun tips(p: UserProfile, loss: Boolean, sessionKcal: Int, bodyFat: Double?): List<String> = buildList {
        add("Répartis les protéines sur les 4 repas (au moins 20 à 30 g à chaque fois) : c'est ce qui protège le muscle${if (loss) " pendant la perte de poids" else ""}.")
        add("Avant une séance de qualité ou une sortie longue, mange des glucides 1 h 30 à 3 h avant ; après, protéines + glucides dans les 2 h.")
        add("Séance de plus de 75 min : 30 à 60 g de glucides par heure (boisson sucrée, banane, pâte de fruits).")
        if (loss) add("Le déficit se fait surtout les jours de repos : ne coupe pas les glucides avant les séances intenses.")
        if (loss) add("Vise 0,5 kg par semaine au maximum ; au-delà, on perd du muscle et la fatigue s'accumule.")
        bodyFat?.let { add("Masse grasse ${BodyStats.fmt(it)} % : suis sa tendance sur 4 semaines plutôt que le poids d'un jour, qui varie avec l'eau.") }
        add("Légumes à chaque repas principal et 2 fruits par jour pour les fibres et les micronutriments.")
        add("Alcool et boissons sucrées hors séance : à limiter, ce sont les calories les plus faciles à réduire.")
        if (sessionKcal > 700) add("Séances très dépensières : mange une collation de plus les jours de sortie longue.")
    }
}
