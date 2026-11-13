package com.mathieu.madtrainer.coach

import com.mathieu.madtrainer.data.SessionRecord
import com.mathieu.madtrainer.data.Sport
import com.mathieu.madtrainer.data.formatPace
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Prompt et lecture de la réponse pour les séances de renforcement personnalisées. */
object StrengthPrompt {

    fun build(
        profile: UserProfile,
        sp: StrengthProfile,
        sessions: List<SessionRecord>,
        painLogs: List<PainLog>,
        otherActivities: String? = null,
        now: Date = Date(),
    ): String = buildString {
        val date = SimpleDateFormat("EEEE d MMMM yyyy", Locale.FRANCE).format(now)
        appendLine("Tu es un préparateur physique, spécialiste du renforcement musculaire et de la prévention des blessures chez les sportifs amateurs. Tu es prudent et pédagogue.")
        appendLine("Nous sommes le $date. Propose un programme de renforcement personnalisé, en français, pour la personne ci-dessous.")
        appendLine("IMPORTANT : tu ne poses aucun diagnostic et tu ne remplaces pas un médecin ou un kinésithérapeute. Si les douleurs décrites le justifient, recommande clairement de consulter.")
        appendLine()

        appendLine("## Profil")
        appendLine("- ${profile.sex.label}, ${profile.age} ans, ${fmt(profile.weightKg)} kg, ${profile.heightCm} cm")
        appendLine("- Niveau sportif : ${profile.level.label}, objectif général : ${profile.goal.label}")
        if (profile.constraints.isNotBlank()) appendLine("- Contraintes déclarées : ${profile.constraints.trim()}")
        if (profile.runningInfo.isNotBlank()) appendLine("- Repères running : ${profile.runningInfo.trim()}")
        appendLine()

        appendLine("## Douleurs / problèmes actuels")
        if (sp.painAreas.isEmpty() && sp.painDetails.isBlank()) {
            appendLine("Aucune douleur signalée : programme de prévention.")
        } else {
            if (sp.painAreas.isNotEmpty()) appendLine("- Zones : ${sp.painAreas.joinToString(", ")}")
            appendLine("- Intensité actuelle : ${sp.painLevel}/10")
            if (sp.painDetails.isNotBlank()) appendLine("- Détails : ${sp.painDetails.trim()}")
        }
        appendLine()

        val logs = painLogs.sortedByDescending { it.date }.take(10)
        if (logs.isNotEmpty()) {
            val df = SimpleDateFormat("dd/MM", Locale.FRANCE)
            appendLine("## Suivi de la douleur après les dernières séances de renfo")
            logs.forEach { l ->
                appendLine("- ${df.format(Date(l.date))} « ${l.sessionName} » : douleur ${l.painBefore}/10 avant → ${l.painAfter}/10 après" + if (l.note.isNotBlank()) " (${l.note.trim()})" else "")
            }
            appendLine("Adapte le programme : retire ou modifie les exercices des séances après lesquelles la douleur a augmenté, progresse sur celles bien tolérées.")
            appendLine()
        }

        appendLine("## Activité cardio récente (pour doser la charge)")
        val recent = sessions.sortedByDescending { it.startedAt }.take(12)
        if (recent.isEmpty()) {
            appendLine("Pas encore de séance enregistrée.")
        } else {
            val df = SimpleDateFormat("dd/MM", Locale.FRANCE)
            recent.forEach { s ->
                val detail = if (s.sport == Sport.RUNNING) {
                    "%.1f km".format(Locale.FRANCE, s.distanceM / 1000.0) + (s.avgPaceSecPerKm?.let { " à ${formatPace(it)}/km" } ?: "")
                } else {
                    "${s.avgPower} W moy"
                }
                appendLine("- ${df.format(Date(s.startedAt))} ${s.sport.label} ${s.durationSec / 60} min, $detail" + (s.avgHr?.let { ", FC moy $it" } ?: ""))
            }
        }
        if (!otherActivities.isNullOrBlank()) {
            appendLine()
            appendLine(otherActivities.trim())
        }
        appendLine()

        appendLine("## Cadre")
        appendLine("- ${sp.sessionsPerWeek} séance(s) de renfo par semaine, ${sp.sessionMinutes} minutes environ, sur 4 semaines.")
        appendLine("- Matériel disponible : ${sp.equipment.ifEmpty { listOf("aucun") }.joinToString(", ")}.")
        if (sp.goals.isNotBlank()) appendLine("- Objectifs du renfo : ${sp.goals.trim()}")
        appendLine("- Les séances sont lues par une app avec chrono et annonces vocales : chaque exercice a un nombre de séries, des répétitions OU une durée, et un temps de repos.")
        appendLine()

        appendLine("## Ce que j'attends")
        appendLine("1. Une explication simple de la logique du programme au regard de mes douleurs et de mon sport (sans diagnostic).")
        appendLine("2. Les règles de sécurité : douleur acceptable pendant l'effort (ex. ≤ 3/10 et qui ne s'aggrave pas le lendemain), quand alléger ou arrêter.")
        appendLine("3. Les signaux d'alerte qui imposent de consulter rapidement" + if (sp.painAreas.any { it.contains("Sciatique", true) || it.contains("Lombaires", true) }) " (pour une sciatique notamment : perte de force dans la jambe, engourdissement du périnée, troubles urinaires ou intestinaux, douleur des deux côtés, douleur nocturne qui ne cède pas)." else ".")
        appendLine("4. Des habitudes quotidiennes utiles (mobilité, posture, pauses), courtes et concrètes.")
        appendLine("5. Toutes les séances, avec des exercices précis : consignes d'exécution, points d'attention, version plus facile et plus difficile, et quand l'éviter.")
        appendLine("Privilégie des exercices simples, sûrs et progressifs. Évite les mouvements connus pour aggraver les douleurs décrites.")
        appendLine()

        appendLine("## Format de réponse OBLIGATOIRE")
        appendLine("Rédige d'abord tes explications en texte lisible, puis termine par UN SEUL bloc ```json (sans commentaire à l'intérieur) respectant exactement ce schéma :")
        appendLine(SCHEMA)
        appendLine("reps OU duration_sec doit être renseigné (l'autre à null). rest_sec en secondes. per_side = true si l'exercice se fait de chaque côté.")
    }

    private fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(Locale.FRANCE, v)

    val SCHEMA = """
```json
{
  "plan_name": "string",
  "summary": "string — logique du programme",
  "safety": ["règle de sécurité", "..."],
  "red_flags": ["signal d'alerte : consulter", "..."],
  "daily_habits": ["habitude quotidienne", "..."],
  "progression": "string — comment progresser ensuite",
  "sessions": [
    {
      "name": "Renfo A – Gainage et hanches",
      "focus": "string",
      "week": 1,
      "day": "Lundi",
      "duration_min": 20,
      "exercises": [
        { "name": "Pont fessier", "sets": 3, "reps": 12, "duration_sec": null, "rest_sec": 45, "per_side": false,
          "instructions": "string", "cues": "string", "easier": "string", "harder": "string", "avoid_if": "string" },
        { "name": "Gainage latéral", "sets": 2, "reps": null, "duration_sec": 30, "rest_sec": 30, "per_side": true,
          "instructions": "string", "cues": "string", "easier": "string", "harder": "string", "avoid_if": "string" }
      ]
    }
  ]
}
```""".trimIndent()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    fun parse(response: String): Result<StrengthPlan> = runCatching {
        val raw = CoachParser.extractJson(response) ?: error("Aucun bloc JSON trouvé dans la réponse")
        val plan = json.decodeFromString(StrengthPlan.serializer(), raw)
        require(plan.sessions.any { it.exercises.isNotEmpty() }) { "Le programme ne contient aucun exercice" }
        // Bornes de sécurité sur les valeurs
        plan.copy(
            sessions = plan.sessions.map { s ->
                s.copy(
                    exercises = s.exercises.map { e ->
                        e.copy(
                            sets = e.sets.coerceIn(1, 10),
                            reps = e.reps?.coerceIn(1, 100),
                            duration_sec = e.duration_sec?.coerceIn(5, 600),
                            rest_sec = e.rest_sec.coerceIn(0, 300),
                        )
                    }.filter { it.name.isNotBlank() },
                )
            }.filter { it.exercises.isNotEmpty() },
        )
    }
}
