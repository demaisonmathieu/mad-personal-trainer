package com.mathieu.madtrainer.data

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Estimation de la VMA (vitesse maximale aérobie) et de la VO2max à partir des courses, sans test d'effort.
 *
 * - Cardio : par minute de course (après 5 min d'échauffement), la vitesse rapportée au % de FC de réserve
 *   (formule de Karvonen ; %FCR ≈ %VO2 de réserve, Swain) donne la vitesse qu'on tiendrait à 100 %. On garde la médiane.
 *   Ne comptent que les minutes courues (≥ 7 km/h), à vitesse stable depuis 2 min (le cardio a rattrapé l'effort),
 *   sur le plat (pente ≤ 4 %) et entre 60 et 95 % de FCR (au-dessous, l'extrapolation jusqu'à 100 % amplifie les erreurs).
 * - Allure : la meilleure vitesse tenue 6 min est une borne basse de la VMA (demi-Cooper), sans saut de GPS.
 * - VMA retenue : médiane des estimations des dernières courses, pour ne pas suivre les variations d'une sortie à l'autre.
 * VO2max ≈ 3,5 × VMA (Léger).
 */
data class VmaEstimate(
    val vmaKmh: Double,
    val vo2max: Double,
    /** Explication courte de la méthode utilisée. */
    val method: String,
    /** Assez de mesures cardio exploitables, FC de repos connue. */
    val reliable: Boolean,
    /** Nombre de courses prises en compte (1 pour une estimation sur une seule sortie). */
    val runs: Int = 1,
)

/** Une ligne du tableau d'allures : [fastSecKm] à [pctHigh] % de VMA, [slowSecKm] à [pctLow] %. */
data class PaceBand(val name: String, val use: String, val pctLow: Int, val pctHigh: Int, val slowSecKm: Int, val fastSecKm: Int)

object Vma {
    private const val WINDOW = 60
    private const val WARMUP = 300
    private const val MIN_RUN_KMH = 7.0
    private const val STABLE_TOL = 0.10
    private const val MAX_GRADE = 0.04
    private const val MAX_GPS_KMH = 25.0
    const val RECENT_RUNS = 5
    const val RECENT_DAYS = 60

    fun vo2max(vmaKmh: Double): Double = vmaKmh * 3.5

    /** Allure (s/km) à [pct] de la VMA. */
    fun paceAt(vmaKmh: Double, pct: Double): Int = (3600.0 / (vmaKmh * pct)).roundToInt()

    fun estimate(rec: SessionRecord, maxHr: Int, restingHr: Int?): VmaEstimate? {
        if (rec.sport != Sport.RUNNING || rec.durationSec < 10 * 60 || rec.distanceM < 1500) return null
        val withDist = rec.samples.filter { it.distanceM != null }
        if (withDist.size < 6 * 60) return null

        val rest = restingHr ?: 60
        val byT = withDist.associateBy { it.t }
        val altByT = Elevation.smoothed(rec.samples).let { alts -> rec.samples.indices.associate { rec.samples[it].t to alts[it] } }
        val hrByT = rec.samples.associate { it.t to it.hr }

        // Vitesse (km/h) de chaque minute, indexée par son début
        val speeds = mutableMapOf<Int, Double>()
        val start = withDist.first().t
        val end = withDist.last().t
        var t = start
        while (t + WINDOW <= end) {
            val a = byT[t]
            val b = byT[t + WINDOW]
            if (a != null && b != null) speeds[t] = (b.distanceM!! - a.distanceM!!) / WINDOW.toDouble() * 3.6
            t += WINDOW
        }

        val ratios = mutableListOf<Double>()
        speeds.forEach { (t0, kmh) ->
            if (t0 < start + WARMUP || kmh !in MIN_RUN_KMH..MAX_GPS_KMH) return@forEach
            // Vitesse stable sur les 2 minutes précédentes : le cardio suit l'effort avec 1 à 2 min de retard
            val stable = listOf(t0 - WINDOW, t0 - 2 * WINDOW).all { p -> speeds[p]?.let { abs(it - kmh) <= kmh * STABLE_TOL } == true }
            if (!stable) return@forEach
            val a0 = altByT[t0]
            val a1 = altByT[t0 + WINDOW]
            val dist = kmh / 3.6 * WINDOW
            if (a0 != null && a1 != null && dist > 0 && abs(a1 - a0) / dist > MAX_GRADE) return@forEach
            val hrs = (t0 until t0 + WINDOW).mapNotNull { hrByT[it] }.filter { it > 40 }
            if (hrs.size < WINDOW / 2) return@forEach
            val pct = (hrs.average() - rest) / (maxHr - rest).toDouble()
            if (pct in 0.60..0.95) ratios += kmh / pct
        }
        val fromHr = ratios.takeIf { it.size >= 5 }?.sorted()?.let { it[it.size / 2] }
        val best6 = bestSpeed(withDist, 6 * 60)

        val vma = listOfNotNull(fromHr, best6).maxOrNull() ?: return null
        if (vma !in 7.0..26.0) return null
        val method = when {
            fromHr != null && best6 != null && best6 >= fromHr -> "meilleure vitesse tenue 6 min (${fmt(best6)} km/h), au-dessus de l'estimation par le cardio"
            fromHr != null -> "vitesse rapportée à ta FC de réserve sur ${ratios.size} min de course régulière (FC max $maxHr, repos " +
                (if (restingHr == null) "supposée à 60" else "$restingHr") + ")"
            else -> "meilleure vitesse tenue 6 min, sans cardio exploitable : valeur minimale"
        }
        return VmaEstimate(round1(vma), round1(vo2max(vma)), method, fromHr != null && restingHr != null && ratios.size >= 10)
    }

    /**
     * VMA retenue : médiane des estimations des [RECENT_RUNS] dernières courses des [RECENT_DAYS] derniers jours
     * (jusqu'à [until] inclus). Une seule sortie, bonne ou mauvaise, ne fait plus bouger les allures.
     */
    fun recent(runs: List<SessionRecord>, maxHr: Int, restingHr: Int?, until: Long = Long.MAX_VALUE): VmaEstimate? {
        val last = runs.filter { it.sport == Sport.RUNNING && it.startedAt <= until }.maxOfOrNull { it.startedAt } ?: return null
        val ests = runs.asSequence()
            .filter { it.sport == Sport.RUNNING && it.startedAt <= until && it.startedAt >= last - RECENT_DAYS * 24L * 3600 * 1000 }
            .sortedByDescending { it.startedAt }
            .mapNotNull { estimate(it, maxHr, restingHr) }
            .take(RECENT_RUNS)
            .toList()
        if (ests.isEmpty()) return null
        val sorted = ests.map { it.vmaKmh }.sorted()
        val n = sorted.size
        val med = if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
        val method = if (n == 1) ests[0].method else "médiane de tes $n dernières courses (de ${fmt(sorted.first())} à ${fmt(sorted.last())} km/h)"
        return VmaEstimate(round1(med), round1(vo2max(med)), method, n >= 3 && ests.count { it.reliable } >= 2, n)
    }

    /**
     * Meilleure vitesse moyenne (km/h) sur [sec] secondes consécutives, null si la course est plus courte.
     * Les fenêtres avec un saut de GPS (30 s à plus de 25 km/h ou 35 % au-dessus de la moyenne) sont ignorées.
     */
    fun bestSpeed(samples: List<Sample>, sec: Int): Double? {
        val byT = samples.filter { it.distanceM != null }.associate { it.t to it.distanceM!! }
        if (byT.isEmpty()) return null
        var best: Double? = null
        for ((t, d) in byT) {
            val d2 = byT[t + sec] ?: continue
            val kmh = (d2 - d) / sec.toDouble() * 3.6
            if (best != null && kmh <= best) continue
            var clean = true
            var k = t
            while (k + 30 <= t + sec) {
                val x0 = byT[k]
                val x1 = byT[k + 30]
                if (x0 != null && x1 != null) {
                    val s = (x1 - x0) / 30.0 * 3.6
                    if (s > MAX_GPS_KMH || s > kmh * 1.35) {
                        clean = false
                        break
                    }
                }
                k += 30
            }
            if (clean) best = kmh
        }
        return best
    }

    /** Tableau d'allures d'entraînement en % de VMA. */
    fun paceTable(vmaKmh: Double): List<PaceBand> = listOf(
        band("Récupération", "footing très lent, retour au calme", 60, 65, vmaKmh),
        band("Endurance fondamentale", "la majorité des sorties (zone 2)", 65, 75, vmaKmh),
        band("Allure marathon", "sortie longue rythmée", 75, 82, vmaKmh),
        band("Seuil / tempo", "blocs de 8 à 20 min", 85, 90, vmaKmh),
        band("Allure 10 km", "répétitions de 2 à 5 min", 90, 95, vmaKmh),
        band("VMA", "fractionné court (30 s à 1 min 30)", 95, 105, vmaKmh),
    )

    private fun band(name: String, use: String, lo: Int, hi: Int, vma: Double) =
        PaceBand(name, use, lo, hi, paceAt(vma, lo / 100.0), paceAt(vma, hi / 100.0))

    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0

    fun fmt(v: Double): String = "%.1f".format(java.util.Locale.FRANCE, v)
}
