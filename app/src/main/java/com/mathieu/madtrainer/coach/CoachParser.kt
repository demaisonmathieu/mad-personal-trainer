package com.mathieu.madtrainer.coach

import com.mathieu.madtrainer.data.Sport
import com.mathieu.madtrainer.data.StepMode
import com.mathieu.madtrainer.data.Workout
import com.mathieu.madtrainer.data.WorkoutStep
import kotlinx.serialization.json.Json

object CoachParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** Extrait le bloc JSON de la réponse de l'IA (bloc ```json, sinon du premier « { » au dernier « } »). */
    fun extractJson(response: String): String? {
        // Sans expression régulière (le moteur d'Android refuse certaines syntaxes acceptées par Java) :
        // on prend le DERNIER bloc ``` … ``` qui contient un objet JSON.
        var searchEnd = response.length
        while (true) {
            val close = response.lastIndexOf("```", searchEnd - 1)
            if (close <= 0) break
            val open = response.lastIndexOf("```", close - 1)
            if (open < 0) break
            val inner = response.substring(open + 3, close).trim().removePrefix("json").removePrefix("JSON").trim()
            if (inner.startsWith("{") && inner.endsWith("}")) return inner
            searchEnd = open
        }
        // Pas de bloc ``` : du premier « { » au dernier « } »
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        return if (start >= 0 && end > start) response.substring(start, end + 1) else null
    }

    /** Résultat d'import : [truncated] = la réponse était coupée, seule la partie complète a été gardée. */
    data class Parsed<T>(val value: T, val truncated: Boolean)

    /** Décode le JSON ; s'il est tronqué (réponse d'IA coupée), répare en gardant les éléments complets. */
    fun <T> decodeWithRepair(raw: String, decode: (String) -> T): Parsed<T> =
        try {
            Parsed(decode(raw), false)
        } catch (e: Exception) {
            val repaired = JsonRepair.repairTruncated(raw)
            if (repaired == null || repaired == raw.trim()) {
                throw IllegalStateException(
                    "Réponse incomplète ou mal formée (${e.message?.take(80)}). " +
                        "Si l'IA s'est arrêtée en cours de route, demande-lui « continue » et colle aussi la suite.",
                )
            }
            Parsed(decode(repaired), true)
        }

    fun parse(response: String): Result<CoachPlan> = parseDetailed(response).map { it.value }

    fun parseDetailed(response: String): Result<Parsed<CoachPlan>> = runCatching {
        val raw = extractJson(response) ?: error("Aucun bloc JSON trouvé dans la réponse")
        val parsed = decodeWithRepair(raw) { json.decodeFromString(CoachPlan.serializer(), it) }
        require(parsed.value.workouts.isNotEmpty()) { "Le plan ne contient aucune séance complète" }
        parsed
    }

    /** Convertit les séances du plan en programmes exécutables (cibles bornées à la machine). */
    fun toWorkouts(
        plan: CoachPlan,
        resistanceMin: Int = 1,
        resistanceMax: Int = 15,
        sport: Sport = Sport.ELLIPTICAL,
    ): List<Workout> =
        plan.workouts.mapIndexed { i, cw ->
            Workout(
                name = cw.name.ifBlank { "Séance ${i + 1}" },
                steps = cw.steps.map { s -> if (sport == Sport.RUNNING) runStep(s) else ellipticalStep(s, resistanceMin, resistanceMax) },
                planWeek = cw.week,
                planDay = cw.day,
                notes = cw.purpose,
                fromCoach = true,
                sport = sport,
            )
        }.filter { it.steps.isNotEmpty() }

    /** Plan commun : chaque séance porte son sport (ou on le déduit des consignes). */
    fun toGlobalWorkouts(plan: CoachPlan, resistanceMin: Int = 1, resistanceMax: Int = 15): List<Workout> =
        plan.workouts.mapIndexed { i, cw ->
            val sport = when (cw.sport.trim().uppercase()) {
                "RUNNING", "RUN", "COURSE" -> Sport.RUNNING
                "ELLIPTICAL", "ELLIPTIQUE" -> Sport.ELLIPTICAL
                else -> if (cw.steps.any { it.mode.uppercase() == "PACE" || it.distance_m != null }) Sport.RUNNING else Sport.ELLIPTICAL
            }
            Workout(
                name = cw.name.ifBlank { "Séance ${i + 1}" },
                steps = cw.steps.map { s ->
                    when {
                        sport == Sport.RUNNING -> runStep(s)
                        // Elliptique : la zone cardio est acceptée (résistance pilotée par le cardio)
                        s.mode.trim().uppercase() in listOf("HR_ZONE", "ZONE") ->
                            WorkoutStep(s.name, s.duration_sec.coerceIn(10, 3 * 3600), StepMode.HR_ZONE, s.target.coerceIn(1.0, 5.0))
                        else -> ellipticalStep(s, resistanceMin, resistanceMax)
                    }
                },
                planWeek = cw.week,
                planDay = cw.day,
                notes = cw.purpose,
                fromCoach = true,
                sport = sport,
            )
        }.filter { it.steps.isNotEmpty() }

    private fun ellipticalStep(s: CoachStep, rMin: Int, rMax: Int): WorkoutStep {
        val mode = when (s.mode.trim().uppercase()) {
            "POWER", "WATTS", "ERG" -> StepMode.POWER
            "FREE", "LIBRE" -> StepMode.FREE
            else -> StepMode.RESISTANCE
        }
        val target = when (mode) {
            StepMode.POWER -> s.target.coerceIn(10.0, 600.0)
            StepMode.FREE -> 0.0
            else -> s.target.coerceIn(rMin.toDouble(), rMax.toDouble())
        }
        return WorkoutStep(s.name, s.duration_sec.coerceIn(10, 3 * 3600), mode, target)
    }

    private fun runStep(s: CoachStep): WorkoutStep {
        val mode = when (s.mode.trim().uppercase()) {
            "PACE", "ALLURE" -> StepMode.PACE
            "HR_ZONE", "ZONE", "HR" -> StepMode.HR_ZONE
            else -> StepMode.FREE
        }
        val target = when (mode) {
            StepMode.PACE -> s.target.coerceIn(150.0, 900.0)
            StepMode.HR_ZONE -> s.target.coerceIn(1.0, 5.0)
            else -> 0.0
        }
        val dist = s.distance_m?.takeIf { it > 0 }?.coerceIn(50, 50_000)
        // Durée estimée pour les étapes en distance (allure cible, sinon 6'30/km)
        val estPace = if (mode == StepMode.PACE) target else 390.0
        val duration = dist?.let { (it / 1000.0 * estPace).toInt() } ?: s.duration_sec
        return WorkoutStep(s.name, duration.coerceIn(10, 6 * 3600), mode, target, distanceM = dist)
    }
}
