package com.mathieu.madtrainer.data

import kotlin.math.roundToInt

/** Bilan d'une semaine (lundi → dimanche). */
data class WeekStat(val weekStart: Long, val sessions: Int, val minutes: Int, val load: Int, val runKm: Double)

/** État de forme calculé à partir de la charge d'entraînement. */
enum class FormStatus(val label: String, val advice: String) {
    NOT_ENOUGH_DATA("Pas assez d'historique", "Il faut quelques semaines de séances enregistrées pour juger la fatigue."),
    FRESH("Frais", "Charge récente plus faible que d'habitude : tu peux augmenter un peu."),
    OPTIMAL("Charge optimale", "Charge récente proche de ta charge habituelle : c'est la zone où l'on progresse sans risque."),
    LOADED("Chargé", "Charge récente nettement au-dessus de l'habitude : garde les séances faciles vraiment faciles."),
    OVERLOAD("Surcharge", "Hausse de charge trop brutale : risque de blessure et de fatigue, allège quelques jours."),
}

/**
 * Tendances sur plusieurs semaines.
 * @param atl charge aiguë (moyenne pondérée sur ~7 jours) ; [ctl] charge chronique (~42 jours) ; [tsb] = ctl − atl (fraîcheur)
 */
data class TrendReport(
    val weeks: List<WeekStat>,
    val atl: Double,
    val ctl: Double,
    val tsb: Double,
    /** Ratio charge aiguë / chronique (null si historique insuffisant). */
    val acwr: Double?,
    val status: FormStatus,
    /** Évolution de l'efficacité (% : 4 dernières semaines vs les 4 précédentes), à cardio égal. */
    val runEfficiencyPct: Double?,
    val ellipticalEfficiencyPct: Double?,
    /** FC de repos des 7 derniers jours moins celle des 4 semaines précédentes (bpm). */
    val restingHrDelta: Int?,
    val highlights: List<String>,
)

object TrainingLoad {
    private const val DAY = 86_400_000L

    /** Poids d'effort par zone cardio (TRIMP d'Edwards : 1 à 5 points par minute). */
    fun sessionLoad(rec: SessionRecord, zones: HrZones): Double {
        val minutes = rec.durationSec / 60.0
        val hrs = rec.samples.mapNotNull { it.hr }.filter { it > 40 }
        val weight = when {
            // Assez de mesures cardio : zone moyenne pondérée sur toute la séance
            hrs.size >= 60 -> hrs.map { zones.zoneOf(it) }.average()
            rec.avgHr != null && rec.avgHr > 40 -> zones.zoneOf(rec.avgHr).toDouble()
            // Sans cardio : effort modéré par défaut
            else -> 2.0
        }
        return minutes * weight
    }

    /** Charge quotidienne sur [days] jours jusqu'à [now] (index 0 = le plus ancien). */
    fun dailyLoads(sessions: List<SessionRecord>, zones: HrZones, now: Long, days: Int): DoubleArray {
        val today = Math.floorDiv(now, DAY)
        val out = DoubleArray(days)
        sessions.forEach { s ->
            val i = (Math.floorDiv(s.startedAt, DAY) - (today - days + 1)).toInt()
            if (i in 0 until days) out[i] += sessionLoad(s, zones)
        }
        return out
    }

    fun report(sessions: List<SessionRecord>, zones: HrZones, body: BodyCache?, now: Long = System.currentTimeMillis()): TrendReport {
        val loads = dailyLoads(sessions, zones, now, 120)
        // On démarre au premier jour d'entraînement, initialisé sur la charge moyenne des 4 premières semaines :
        // partir de zéro ferait croire à une surcharge tant qu'il n'y a pas 3 mois d'historique.
        val first = loads.indexOfFirst { it > 0 }
        var atl = 0.0
        var ctl = 0.0
        if (first >= 0) {
            val seed = loads.copyOfRange(first, minOf(first + 28, loads.size)).average()
            atl = seed
            ctl = seed
            for (i in first until loads.size) {
                atl += (loads[i] - atl) / 7.0
                ctl += (loads[i] - ctl) / 42.0
            }
        }
        val historyDays = sessions.minOfOrNull { it.startedAt }?.let { ((now - it) / DAY).toInt() } ?: 0
        val acwr = if (ctl >= 3 && historyDays >= 21) atl / ctl else null
        val tsb = ctl - atl
        val status = when {
            acwr == null -> FormStatus.NOT_ENOUGH_DATA
            acwr > 1.5 -> FormStatus.OVERLOAD
            acwr > 1.3 -> FormStatus.LOADED
            acwr < 0.8 -> FormStatus.FRESH
            else -> FormStatus.OPTIMAL
        }

        val monday = WeekPlanner.mondayOf(now)
        val weeks = (7 downTo 0).map { k ->
            val start = monday - k * WeekPlanner.WEEK_MS
            val inWeek = sessions.filter { it.startedAt >= start && it.startedAt < start + WeekPlanner.WEEK_MS }
            WeekStat(
                weekStart = start,
                sessions = inWeek.size,
                minutes = inWeek.sumOf { it.durationSec } / 60,
                load = inWeek.sumOf { sessionLoad(it, zones) }.roundToInt(),
                runKm = inWeek.filter { it.sport == Sport.RUNNING }.sumOf { it.distanceM } / 1000.0,
            )
        }

        val runEff = efficiencyChange(sessions, Sport.RUNNING, now)
        val ellEff = efficiencyChange(sessions, Sport.ELLIPTICAL, now)
        val restingDelta = body?.let { restingHrDelta(it.restingHr, now) }

        val highlights = buildList {
            val cur = weeks.last()
            val prev = weeks[weeks.size - 2]
            val avg4 = weeks.dropLast(1).takeLast(4).map { it.minutes }.average()
            add("Cette semaine : ${cur.sessions} séance(s), ${cur.minutes} min (semaine dernière : ${prev.minutes} min, moyenne 4 semaines : ${avg4.roundToInt()} min)")
            if (prev.minutes > 0 && avg4 > 0 && prev.minutes > avg4 * 1.3) add("La semaine dernière était ${((prev.minutes / avg4 - 1) * 100).roundToInt()} % au-dessus de ta moyenne")
            runEff?.let { add("Course : efficacité ${signedPct(it)} à cardio égal sur 4 semaines" + if (it >= 2) " : tu cours plus vite pour le même effort" else "") }
            ellEff?.let { add("Elliptique : puissance par battement ${signedPct(it)} sur 4 semaines") }
            restingDelta?.let {
                when {
                    it >= 5 -> add("FC de repos +$it bpm par rapport à ton habitude : signe de fatigue, de stress ou de maladie")
                    it <= -3 -> add("FC de repos ${it} bpm par rapport à ton habitude : bonne adaptation à l'entraînement")
                    else -> add("FC de repos stable (${if (it > 0) "+" else ""}$it bpm)")
                }
            }
        }

        return TrendReport(weeks, atl, ctl, tsb, acwr, status, runEff, ellEff, restingDelta, highlights)
    }

    /** Efficacité moyenne des 4 dernières semaines comparée aux 4 précédentes (au moins 2 séances de chaque côté). */
    fun efficiencyChange(sessions: List<SessionRecord>, sport: Sport, now: Long): Double? {
        val same = sessions.filter { it.sport == sport }
        val recent = same.filter { it.startedAt in (now - 28 * DAY)..now }.mapNotNull { SessionReport.efficiency(it) }
        val before = same.filter { it.startedAt in (now - 56 * DAY) until (now - 28 * DAY) }.mapNotNull { SessionReport.efficiency(it) }
        if (recent.size < 2 || before.size < 2) return null
        return (recent.average() / before.average() - 1) * 100
    }

    /** FC de repos : moyenne des 7 derniers jours moins la moyenne des jours 8 à 35. */
    fun restingHrDelta(list: List<RestingHr>, now: Long): Int? {
        val recent = list.filter { it.time in (now - 7 * DAY)..now }.map { it.bpm }
        val base = list.filter { it.time in (now - 35 * DAY) until (now - 7 * DAY) }.map { it.bpm }
        if (recent.isEmpty() || base.size < 5) return null
        return (recent.average() - base.average()).roundToInt()
    }

    private fun signedPct(v: Double): String = (if (v >= 0) "+" else "") + "%.1f".format(java.util.Locale.FRANCE, v) + " %"
}
