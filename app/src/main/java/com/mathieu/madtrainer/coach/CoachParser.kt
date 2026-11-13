package com.mathieu.madtrainer.coach

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
        val fence = Regex("```(?:json)?\\s*(\\{.*?})\\s*```", RegexOption.DOT_MATCHES_ALL)
        fence.findAll(response).lastOrNull()?.let { return it.groupValues[1] }
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        return if (start >= 0 && end > start) response.substring(start, end + 1) else null
    }

    fun parse(response: String): Result<CoachPlan> = runCatching {
        val raw = extractJson(response) ?: error("Aucun bloc JSON trouvé dans la réponse")
        val plan = json.decodeFromString(CoachPlan.serializer(), raw)
        require(plan.workouts.isNotEmpty()) { "Le plan ne contient aucune séance" }
        plan
    }

    /** Convertit les séances du plan en programmes exécutables (cibles bornées à la machine). */
    fun toWorkouts(plan: CoachPlan, resistanceMin: Int = 1, resistanceMax: Int = 15): List<Workout> =
        plan.workouts.mapIndexed { i, cw ->
            Workout(
                name = cw.name.ifBlank { "Séance ${i + 1}" },
                steps = cw.steps.map { s ->
                    val mode = when (s.mode.trim().uppercase()) {
                        "POWER", "WATTS", "ERG" -> StepMode.POWER
                        "FREE", "LIBRE" -> StepMode.FREE
                        else -> StepMode.RESISTANCE
                    }
                    val target = when (mode) {
                        StepMode.RESISTANCE -> s.target.coerceIn(resistanceMin.toDouble(), resistanceMax.toDouble())
                        StepMode.POWER -> s.target.coerceIn(10.0, 600.0)
                        StepMode.FREE -> 0.0
                    }
                    WorkoutStep(s.name, s.duration_sec.coerceIn(10, 3 * 3600), mode, target)
                }.filter { it.durationSec > 0 },
                planWeek = cw.week,
                planDay = cw.day,
                notes = cw.purpose,
                fromCoach = true,
            )
        }.filter { it.steps.isNotEmpty() }
}
