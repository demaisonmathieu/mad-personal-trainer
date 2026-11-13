package com.mathieu.madtrainer.strava

import kotlinx.serialization.Serializable

/** Résumé d'une activité Strava (sous-ensemble de l'API /athlete/activities). */
@Serializable
data class StravaActivity(
    val id: Long = 0,
    val name: String = "",
    val sport_type: String = "",
    val type: String = "",
    val start_date: String = "",
    val start_date_local: String = "",
    val moving_time: Int = 0,
    val elapsed_time: Int = 0,
    val distance: Double = 0.0,
    val total_elevation_gain: Double = 0.0,
    val average_speed: Double = 0.0,
    val average_heartrate: Double? = null,
    val max_heartrate: Double? = null,
    val average_watts: Double? = null,
    val kilojoules: Double? = null,
    val suffer_score: Double? = null,
) {
    val sport: String get() = sport_type.ifBlank { type }
}

@Serializable
data class StravaTokens(
    val access_token: String = "",
    val refresh_token: String = "",
    val expires_at: Long = 0,
)

@Serializable
data class StravaCache(
    val athleteName: String = "",
    val weightKg: Double? = null,
    val lastSync: Long = 0,
    val activities: List<StravaActivity> = emptyList(),
    /** Zones cardio du profil Strava (bornes basses des zones 1 à 5) */
    val hrZoneLows: List<Int>? = null,
)

/** Réponse de GET /athlete/zones (seule la partie cardio nous intéresse). */
@Serializable
data class StravaZoneRange(val min: Int = 0, val max: Int = -1)

@Serializable
data class StravaHrZones(val custom_zones: Boolean = false, val zones: List<StravaZoneRange> = emptyList())

@Serializable
data class StravaAthleteZones(val heart_rate: StravaHrZones? = null)

object StravaZones {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** Bornes basses des 5 zones cardio, ou null si absentes / inattendues. */
    fun parseHrLows(text: String): List<Int>? = runCatching {
        val z = json.decodeFromString(StravaAthleteZones.serializer(), text).heart_rate?.zones.orEmpty()
        com.mathieu.madtrainer.data.HrZones.lowsFromStrava(z.map { it.min })
    }.getOrNull()
}

/** Libellés français des principaux sports Strava. */
fun sportLabel(sport: String): String = when (sport) {
    "Run", "TrailRun", "VirtualRun" -> "Course à pied"
    "Ride", "VirtualRide", "GravelRide", "MountainBikeRide", "EBikeRide", "EMountainBikeRide" -> "Vélo"
    "Walk" -> "Marche"
    "Hike" -> "Randonnée"
    "Swim" -> "Natation"
    "Elliptical" -> "Elliptique"
    "WeightTraining" -> "Musculation"
    "Workout", "HighIntensityIntervalTraining", "Crossfit" -> "Renforcement / HIIT"
    "Yoga", "Pilates" -> "Yoga / Pilates"
    "Rowing", "VirtualRow" -> "Rameur"
    else -> sport
}

/** Résumé texte de l'historique Strava, inséré dans le prompt du coach (Kotlin pur). */
object StravaSummary {
    fun describe(cache: StravaCache, nowMs: Long = System.currentTimeMillis(), maxLines: Int = 25): String? {
        if (cache.activities.isEmpty()) return null
        val acts = cache.activities
        val days = 90
        val weeks = days / 7.0
        return buildString {
            appendLine("Activités Strava des $days derniers jours (${acts.size} activités) :")
            acts.groupBy { sportLabel(it.sport) }
                .toList()
                .sortedByDescending { (_, l) -> l.sumOf { it.moving_time } }
                .forEach { (sport, l) ->
                    val h = l.sumOf { it.moving_time } / 3600.0
                    val km = l.sumOf { it.distance } / 1000.0
                    val hr = l.mapNotNull { it.average_heartrate }.takeIf { it.isNotEmpty() }?.average()
                    append("- $sport : ${l.size} séances, ${fmt1(h)} h")
                    if (km >= 1) append(", ${fmt1(km)} km")
                    append(", soit ${fmt1(l.size / weeks)} séance(s)/semaine")
                    hr?.let { append(", FC moy ${it.toInt()} bpm") }
                    appendLine()
                }
            val totalH = acts.sumOf { it.moving_time } / 3600.0
            appendLine("Volume moyen toutes activités : ${fmt1(totalH / weeks)} h/semaine.")
            appendLine("Dernières activités :")
            acts.take(maxLines).forEach { a ->
                val parts = mutableListOf(
                    a.start_date_local.take(10),
                    sportLabel(a.sport),
                    "${a.moving_time / 60} min",
                )
                if (a.distance >= 100) parts += "${fmt1(a.distance / 1000)} km"
                if (a.total_elevation_gain >= 50) parts += "D+ ${a.total_elevation_gain.toInt()} m"
                a.average_heartrate?.let { parts += "FC moy ${it.toInt()}" + (a.max_heartrate?.let { m -> " / max ${m.toInt()}" } ?: "") }
                a.average_watts?.let { parts += "${it.toInt()} W moy" }
                a.suffer_score?.let { parts += "effort relatif ${it.toInt()}" }
                appendLine("- " + parts.joinToString(", "))
            }
        }.trimEnd()
    }

    private fun fmt1(v: Double) = String.format(java.util.Locale.FRANCE, "%.1f", v)
}
