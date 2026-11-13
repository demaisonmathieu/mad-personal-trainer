package com.mathieu.madtrainer.coach

import com.mathieu.madtrainer.data.SessionRecord
import com.mathieu.madtrainer.data.Sport
import com.mathieu.madtrainer.data.formatPace
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Ce que la machine sait faire, pour que l'IA propose des séances exécutables. */
data class Equipment(
    val machine: String = "Vélo elliptique Domyos EL500",
    val resistanceMin: Int = 1,
    val resistanceMax: Int = 15,
    val powerControl: String = "mode ERG simulé (l'app ajuste la résistance pour viser des watts ; puissance estimée)",
    val hasHeartRate: Boolean = false,
)

object CoachPrompt {

    fun build(
        profile: UserProfile,
        sessions: List<SessionRecord>,
        equipment: Equipment,
        now: Date = Date(),
        otherActivities: String? = null,
        sport: Sport = Sport.ELLIPTICAL,
    ): String {
        val p = profile
        val run = sport == Sport.RUNNING
        val date = SimpleDateFormat("EEEE d MMMM yyyy", Locale.FRANCE).format(now)
        val maxHr = p.estimatedMaxHr
        fun zone(lo: Double, hi: Double) = "${(maxHr * lo).roundToInt()}–${(maxHr * hi).roundToInt()} bpm"

        return buildString {
            appendLine(
                if (run) "Tu es un coach de course à pied diplômé, prudent et bienveillant, spécialiste de la progression des coureurs amateurs."
                else "Tu es un coach sportif diplômé, spécialisé en entraînement cardio sur vélo elliptique, prudent et bienveillant.",
            )
            appendLine(
                "Nous sommes le $date. Construis un programme d'entraînement " +
                    (if (run) "de course à pied (sorties en extérieur) " else "") +
                    "personnalisé pour la personne ci-dessous, en français.",
            )
            appendLine()
            appendLine("## Profil")
            if (p.firstName.isNotBlank()) appendLine("- Prénom : ${p.firstName}")
            appendLine("- Sexe : ${p.sex.label}, âge : ${p.age} ans")
            appendLine("- Poids : ${fmt(p.weightKg)} kg, taille : ${p.heightCm} cm, IMC : ${fmt(p.bmi)}")
            p.targetWeightKg?.let { appendLine("- Poids visé : ${fmt(it)} kg") }
            appendLine("- FC max : $maxHr bpm" + if (p.maxHr == null) " (estimée, formule de Tanaka)" else " (mesurée)")
            p.restingHr?.let { appendLine("- FC de repos : $it bpm") }
            appendLine("- Objectif principal : ${p.goal.label}")
            appendLine("- Niveau : ${p.level.label}")
            appendLine("- Disponibilité : ${p.sessionsPerWeek} séances par semaine, environ ${p.sessionMinutes} min chacune")
            appendLine("- Durée du programme : ${p.horizonWeeks} semaines")
            if (p.preferences.isNotBlank()) appendLine("- Préférences : ${p.preferences.trim()}")
            if (p.constraints.isNotBlank()) appendLine("- Contraintes / douleurs / limites : ${p.constraints.trim()}")
            if (run && p.runningInfo.isNotBlank()) appendLine("- Repères running : ${p.runningInfo.trim()}")
            appendLine()

            if (run) {
                appendLine("## Conditions")
                appendLine("- Sorties en extérieur, guidées par une app : GPS du téléphone (distance, allure), cardio de la montre, annonces vocales à chaque étape et à chaque kilomètre.")
                appendLine("- L'app sait suivre une consigne d'ALLURE (secondes par km) ou de ZONE CARDIO (1 à 5), sur une durée ou une distance.")
            } else {
                appendLine("## Matériel")
                appendLine("- ${equipment.machine}, piloté par une app qui exécute les séances automatiquement.")
                appendLine("- Résistance réglable de ${equipment.resistanceMin} à ${equipment.resistanceMax}.")
                appendLine("- Contrôle par puissance : ${equipment.powerControl}.")
            }
            appendLine("- Cardio : " + if (equipment.hasHeartRate) "disponible en direct (montre connectée)." else "pas forcément disponible pendant la séance.")
            appendLine("- Zones cardio indicatives : Z1 ${zone(0.50, 0.60)}, Z2 ${zone(0.60, 0.70)}, Z3 ${zone(0.70, 0.80)}, Z4 ${zone(0.80, 0.90)}, Z5 ${zone(0.90, 1.0)}.")
            appendLine()

            appendLine("## Historique récent")
            val recent = sessions.sortedByDescending { it.startedAt }.take(15)
            if (recent.isEmpty()) {
                appendLine("Aucune séance enregistrée pour l'instant.")
            } else {
                val df = SimpleDateFormat("dd/MM", Locale.FRANCE)
                val totalMin = recent.sumOf { it.durationSec } / 60
                appendLine("${recent.size} dernières séances ($totalMin min au total) :")
                recent.forEach { s ->
                    val parts = mutableListOf(
                        df.format(Date(s.startedAt)),
                        s.sport.label,
                        s.workoutName ?: "séance libre",
                        "${s.durationSec / 60} min",
                    )
                    if (s.sport == Sport.RUNNING) {
                        parts += "%.2f km".format(Locale.FRANCE, s.distanceM / 1000.0)
                        s.avgPaceSecPerKm?.let { parts += "allure moy ${formatPace(it)}/km" }
                    } else {
                        parts += "${s.avgPower} W moy / ${s.maxPower} W max"
                    }
                    if (s.avgCadence > 0) parts += "cadence ${s.avgCadence.roundToInt()}"
                    s.avgHr?.let { parts += "FC moy $it" }
                    if (s.kcal > 0) parts += "${s.kcal} kcal"
                    val avgRes = s.samples.mapNotNull { it.resistance }.takeIf { it.isNotEmpty() }?.average()
                    avgRes?.let { parts += "résistance moy ${fmt(it)}" }
                    appendLine("- " + parts.joinToString(", "))
                }
            }
            appendLine()

            if (!otherActivities.isNullOrBlank()) {
                appendLine("## Autres activités (Strava)")
                appendLine(otherActivities.trim())
                appendLine(
                    "Tiens compte de cette charge globale : répartis les séances " + (if (run) "de course" else "d'elliptique") +
                        " pour compléter ces activités, sans surcharger les jours qui suivent les séances intenses.",
                )
                appendLine()
            }

            appendLine("## Ce que j'attends")
            appendLine("1. Une courte analyse de ma situation et de ce qui est réaliste en ${p.horizonWeeks} semaines.")
            appendLine("2. La structure hebdomadaire (quels jours, quel type de séance, progression semaine par semaine).")
            appendLine("3. Toutes les séances du programme, précises et exécutables par l'app.")
            appendLine(
                "4. Des conseils concrets : " + (if (run) "technique de course, chaussures, échauffement et renforcement, " else "technique sur elliptique, ") +
                    "nutrition et hydratation (généraux, sans régime extrême), récupération et sommeil, motivation.",
            )
            appendLine("5. Les signaux d'alerte qui doivent me faire arrêter, et quand consulter un médecin.")
            appendLine()
            appendLine("Règles pour les séances :")
            appendLine("- Chaque séance commence par un échauffement progressif (au moins 5 min) et finit par un retour au calme (au moins 3 min).")
            if (run) {
                appendLine("- Les étapes sont de type PACE (target = allure en SECONDES par km, ex. 360 pour 6'00\"/km), HR_ZONE (target = zone cardio 1 à 5) ou FREE (sans consigne, ex. récupération en marchant).")
                appendLine("- Une étape se termine soit à sa durée (duration_sec), soit à sa distance si distance_m est renseigné (fractionné : 400 m, 1000 m…).")
                appendLine("- Privilégie l'endurance fondamentale en zone 2 (environ 80 % du volume), avec au plus 1 à 2 séances de qualité par semaine.")
            } else {
                appendLine("- Les étapes sont de type RESISTANCE (cible = niveau ${equipment.resistanceMin}-${equipment.resistanceMax}), POWER (cible = watts) ou FREE (sans consigne).")
            }
            appendLine("- Pour un niveau ${p.level.label.lowercase()}, reste prudent sur l'intensité et le volume (progression d'environ 10 % par semaine au maximum).")
            appendLine("- Donne à chaque étape un nom court et motivant, en français.")
            appendLine("- Respecte ma durée de séance (± 10 %).")
            appendLine()
            appendLine("## Format de réponse OBLIGATOIRE")
            appendLine("Rédige d'abord tes explications et conseils en texte lisible.")
            appendLine("Puis termine par UN SEUL bloc ```json (sans commentaire à l'intérieur) qui respecte exactement ce schéma :")
            appendLine(if (run) RUN_SCHEMA else SCHEMA)
            appendLine("Le bloc JSON doit contenir toutes les séances de toutes les semaines (week = numéro de semaine, day = jour suggéré).")
            appendLine(
                if (run) "duration_sec est en secondes, distance_m en mètres (ou null). mode vaut PACE, HR_ZONE ou FREE. target est un nombre."
                else "duration_sec est en secondes. mode vaut RESISTANCE, POWER ou FREE. target est un nombre.",
            )
        }
    }

    /**
     * Plan commun : elliptique + course (+ jours de renfo réservés). Les séances privilégient les zones
     * cardio, langue commune aux deux machines.
     */
    fun buildGlobal(
        profile: UserProfile,
        sessions: List<SessionRecord>,
        equipment: Equipment,
        otherActivities: String? = null,
        strength: StrengthProfile? = null,
        now: Date = Date(),
    ): String {
        // On part du prompt elliptique (profil, matériel, historique, Strava) et on remplace la fin
        val base = build(profile, sessions, equipment, now, otherActivities, Sport.ELLIPTICAL)
        val head = base.substringBefore("## Ce que j'attends")
            .replace(
                "Tu es un coach sportif diplômé, spécialisé en entraînement cardio sur vélo elliptique, prudent et bienveillant.",
                "Tu es un coach sportif diplômé (course à pied et cardio en salle), prudent et bienveillant.",
            )
        val p = profile
        return buildString {
            append(head)
            appendLine("## Sports pratiqués")
            appendLine("- Course à pied en extérieur (app avec GPS, cardio de la montre, annonces vocales).")
            appendLine("- Vélo elliptique à la maison : sans impact, idéal pour la récupération active, les jours de douleur ou de mauvais temps.")
            if (p.runningInfo.isNotBlank()) appendLine("- Repères running : ${p.runningInfo.trim()}")
            if (strength != null && strength.sessionsPerWeek > 0) {
                appendLine("- Renforcement : ${strength.sessionsPerWeek} séance(s) de ${strength.sessionMinutes} min par semaine, programmées à part. Réserve-leur des jours (sans les détailler) et évite de placer une séance cardio intense le même jour." +
                    if (strength.painAreas.isNotEmpty()) " Douleurs actuelles : ${strength.painAreas.joinToString(", ")} (${strength.painLevel}/10) : favorise l'elliptique quand l'impact de la course est déconseillé." else "")
            }
            appendLine()
            appendLine("## Ce que j'attends")
            appendLine("1. Une courte analyse et ce qui est réaliste en ${p.horizonWeeks} semaines.")
            appendLine("2. UN SEUL plan hebdomadaire qui répartit intelligemment course et elliptique (${p.sessionsPerWeek} séances cardio par semaine au total, environ ${p.sessionMinutes} min), avec la progression semaine par semaine.")
            appendLine("3. Toutes les séances, exécutables par l'app, chacune avec son sport (ELLIPTICAL ou RUNNING).")
            appendLine("4. Des conseils concrets : technique, nutrition et hydratation (généraux), récupération, sommeil, motivation.")
            appendLine("5. Les signaux d'alerte qui imposent d'arrêter ou de consulter.")
            appendLine()
            appendLine("Règles pour les séances :")
            appendLine("- Chaque séance commence par un échauffement (≥ 5 min) et finit par un retour au calme (≥ 3 min).")
            appendLine("- Utilise de préférence le mode HR_ZONE (target = zone 1 à 5) : il fonctionne sur les DEUX sports (sur l'elliptique, l'app ajuste la résistance pour tenir la zone). La séance peut ainsi être faite sur l'autre machine si besoin.")
            appendLine("- Autres modes : RUNNING → PACE (target = secondes par km) avec distance_m possible ; ELLIPTICAL → RESISTANCE (niveau ${equipment.resistanceMin}-${equipment.resistanceMax}) ou POWER (watts). FREE = sans consigne.")
            appendLine("- Environ 80 % du volume en zone 1-2, 1 à 2 séances de qualité par semaine maximum, progression ≤ 10 % par semaine.")
            appendLine("- Pas deux séances intenses consécutives ; l'elliptique sert volontiers de récupération active le lendemain d'une séance de course difficile.")
            appendLine()
            appendLine("## Format de réponse OBLIGATOIRE")
            appendLine("Rédige d'abord tes explications, puis termine par UN SEUL bloc ```json (sans commentaire à l'intérieur) respectant ce schéma :")
            appendLine(GLOBAL_SCHEMA)
            appendLine("sport vaut ELLIPTICAL ou RUNNING. duration_sec en secondes, distance_m en mètres (course uniquement, sinon null). target est un nombre.")
        }
    }

    val GLOBAL_SCHEMA = """
```json
{
  "plan_name": "string",
  "summary": "string",
  "weekly_structure": "string — organisation type : jours course / elliptique / renfo / repos",
  "advice": ["..."],
  "nutrition": ["..."],
  "recovery": ["..."],
  "progression": "string",
  "warnings": ["..."],
  "workouts": [
    {
      "name": "S1 – Lundi – Footing",
      "sport": "RUNNING",
      "week": 1,
      "day": "Lundi",
      "purpose": "string",
      "steps": [
        { "name": "Échauffement", "duration_sec": 600, "distance_m": null, "mode": "HR_ZONE", "target": 2 }
      ]
    },
    {
      "name": "S1 – Mercredi – Récup active",
      "sport": "ELLIPTICAL",
      "week": 1,
      "day": "Mercredi",
      "purpose": "string",
      "steps": [
        { "name": "Zone 2", "duration_sec": 1500, "distance_m": null, "mode": "HR_ZONE", "target": 2 }
      ]
    }
  ]
}
```""".trimIndent()

    private fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(Locale.FRANCE, v)

    val SCHEMA = """
```json
{
  "plan_name": "string",
  "summary": "string — analyse et objectif en 3-5 phrases",
  "weekly_structure": "string — organisation type de la semaine",
  "advice": ["conseil d'entraînement", "..."],
  "nutrition": ["conseil nutrition / hydratation", "..."],
  "recovery": ["conseil récupération / sommeil", "..."],
  "progression": "string — comment faire évoluer le plan ensuite",
  "warnings": ["signal d'alerte", "..."],
  "workouts": [
    {
      "name": "S1 – Lundi – Endurance douce",
      "week": 1,
      "day": "Lundi",
      "purpose": "string — but de la séance",
      "steps": [
        { "name": "Échauffement", "duration_sec": 300, "mode": "RESISTANCE", "target": 3 },
        { "name": "Bloc tempo", "duration_sec": 600, "mode": "POWER", "target": 110 }
      ]
    }
  ]
}
```""".trimIndent()

    val RUN_SCHEMA = """
```json
{
  "plan_name": "string",
  "summary": "string — analyse et objectif en 3-5 phrases",
  "weekly_structure": "string — organisation type de la semaine",
  "advice": ["conseil d'entraînement", "..."],
  "nutrition": ["conseil nutrition / hydratation", "..."],
  "recovery": ["conseil récupération / sommeil", "..."],
  "progression": "string — comment faire évoluer le plan ensuite",
  "warnings": ["signal d'alerte", "..."],
  "workouts": [
    {
      "name": "S1 – Mardi – Fractionné court",
      "week": 1,
      "day": "Mardi",
      "purpose": "string — but de la séance",
      "steps": [
        { "name": "Échauffement", "duration_sec": 600, "distance_m": null, "mode": "HR_ZONE", "target": 2 },
        { "name": "400 m vite", "duration_sec": 120, "distance_m": 400, "mode": "PACE", "target": 300 },
        { "name": "Récup marche", "duration_sec": 90, "distance_m": null, "mode": "FREE", "target": 0 }
      ]
    }
  ]
}
```""".trimIndent()
}
