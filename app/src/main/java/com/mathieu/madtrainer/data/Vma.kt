package com.mathieu.madtrainer.data

import kotlin.math.roundToInt

/**
 * Estimation de la VMA (vitesse maximale aérobie) et de la VO2max à partir d'une course, sans test d'effort.
 *
 * - Cardio : par tranches d'une minute (après 5 min d'échauffement), la vitesse rapportée au % de FC de réserve
 *   (formule de Karvonen ; %FCR ≈ %VO2 de réserve, Swain) donne la vitesse qu'on tiendrait à 100 %. On garde la médiane.
 * - Allure : la meilleure vitesse tenue 6 min est une borne basse de la VMA (demi-Cooper).
 * VO2max ≈ 3,5 × VMA (Léger).
 */
data class VmaEstimate(
    val vmaKmh: Double,
    val vo2max: Double,
    /** Explication courte de la méthode utilisée. */
    val method: String,
    /** Assez de mesures cardio exploitables, FC de repos connue. */
    val reliable: Boolean,
)

/** Une ligne du tableau d'allures : [fastSecKm] à [pctHigh] % de VMA, [slowSecKm] à [pctLow] %. */
data class PaceBand(val name: String, val use: String, val pctLow: Int, val pctHigh: Int, val slowSecKm: Int, val fastSecKm: Int)

object Vma {
    private const val WINDOW = 60
    private const val WARMUP = 300

    fun vo2max(vmaKmh: Double): Double = vmaKmh * 3.5

    /** Allure (s/km) à [pct] de la VMA. */
    fun paceAt(vmaKmh: Double, pct: Double): Int = (3600.0 / (vmaKmh * pct)).roundToInt()

    fun estimate(rec: SessionRecord, maxHr: Int, restingHr: Int?): VmaEstimate? {
        if (rec.sport != Sport.RUNNING || rec.durationSec < 10 * 60 || rec.distanceM < 1500) return null
        val withDist = rec.samples.filter { it.distanceM != null }
        if (withDist.size < 6 * 60) return null

        // Vitesse (km/h) et cardio moyens par minute
        val rest = restingHr ?: 60
        val ratios = mutableListOf<Double>()
        val byT = withDist.associateBy { it.t }
        var t = WARMUP
        val end = withDist.last().t
        while (t + WINDOW <= end) {
            val a = byT[t]
            val b = byT[t + WINDOW]
            if (a != null && b != null) {
                val kmh = (b.distanceM!! - a.distanceM!!) / WINDOW.toDouble() * 3.6
                val hrs = rec.samples.filter { it.t in t until t + WINDOW }.mapNotNull { it.hr }.filter { it > 40 }
                if (kmh in 5.0..25.0 && hrs.size >= WINDOW / 2) {
                    // Il faut un effort soutenu : en dessous de 55 % de FCR, la relation cardio/vitesse est peu fiable
                    val pct = (hrs.average() - rest) / (maxHr - rest).toDouble()
                    if (pct in 0.55..0.98) ratios += kmh / pct
                }
            }
            t += WINDOW
        }
        val fromHr = ratios.takeIf { it.size >= 5 }?.sorted()?.let { it[it.size / 2] }
        val best6 = bestSpeed(withDist, 6 * 60)

        val vma = listOfNotNull(fromHr, best6).maxOrNull() ?: return null
        if (vma !in 7.0..26.0) return null
        val method = when {
            fromHr != null && best6 != null && best6 >= fromHr -> "meilleure vitesse tenue 6 min (${fmt(best6)} km/h), au-dessus de l'estimation par le cardio"
            fromHr != null -> "vitesse rapportée à ta FC de réserve sur ${ratios.size} min" + if (restingHr == null) " (FC de repos supposée à 60)" else ""
            else -> "meilleure vitesse tenue 6 min, sans cardio : valeur minimale"
        }
        return VmaEstimate(round1(vma), round1(vo2max(vma)), method, fromHr != null && restingHr != null && ratios.size >= 10)
    }

    /** Meilleure vitesse moyenne (km/h) sur [sec] secondes consécutives, null si la course est plus courte. */
    fun bestSpeed(samples: List<Sample>, sec: Int): Double? {
        val byT = samples.filter { it.distanceM != null }.associate { it.t to it.distanceM!! }
        if (byT.isEmpty()) return null
        var best: Double? = null
        for ((t, d) in byT) {
            val d2 = byT[t + sec] ?: continue
            val kmh = (d2 - d) / sec.toDouble() * 3.6
            if (best == null || kmh > best) best = kmh
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
