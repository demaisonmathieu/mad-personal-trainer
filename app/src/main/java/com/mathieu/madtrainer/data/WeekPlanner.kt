package com.mathieu.madtrainer.data

import java.util.Calendar

/** Calcul pur de l'écran « Ma semaine » (testable en JVM). */
object WeekPlanner {
    val DAYS = listOf("Lundi", "Mardi", "Mercredi", "Jeudi", "Vendredi", "Samedi", "Dimanche")
    private const val DAY_MS = 24L * 3600 * 1000
    const val WEEK_MS = 7 * DAY_MS

    /** Lundi 0 h de la semaine contenant [t]. */
    fun mondayOf(t: Long): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = t
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            firstDayOfWeek = Calendar.MONDAY
        }
        val dow = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 // lundi = 0
        c.add(Calendar.DAY_OF_YEAR, -dow)
        return c.timeInMillis
    }

    /** Numéro de semaine du plan (1..maxWeek) correspondant à [now]. */
    fun currentWeek(planCreatedAt: Long, now: Long, maxWeek: Int): Int {
        val start = mondayOf(planCreatedAt)
        val w = ((mondayOf(now) - start) / WEEK_MS).toInt() + 1
        return w.coerceIn(1, maxWeek.coerceAtLeast(1))
    }

    /** Début (lundi 0 h) de la semaine [week] du plan. */
    fun weekStart(planCreatedAt: Long, week: Int): Long = mondayOf(planCreatedAt) + (week - 1) * WEEK_MS

    /** Index 0..6 du jour, ou -1 si le texte ne correspond à aucun jour (« Jour 1 », vide…). */
    fun dayIndex(day: String?): Int {
        val d = day?.trim()?.lowercase() ?: return -1
        return DAYS.indexOfFirst { d.startsWith(it.lowercase().take(3)) }
    }

    /** Minutes passées dans chaque zone cardio (1..5) à partir des échantillons (1/s). */
    fun minutesPerZone(sessions: List<SessionRecord>, maxHr: Int): IntArray {
        val sec = IntArray(5)
        sessions.forEach { s ->
            s.samples.forEach { p ->
                val hr = p.hr ?: return@forEach
                if (hr > 0) sec[RunEngine.zoneOf(hr, maxHr) - 1]++
            }
        }
        return IntArray(5) { (sec[it] + 30) / 60 }
    }
}
