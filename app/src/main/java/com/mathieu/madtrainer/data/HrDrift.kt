package com.mathieu.madtrainer.data

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Dérive cardiaque d'une séance : hausse (en %) des battements nécessaires pour un même effort
 * entre la 1re et la 2e partie de la séance (découplage cardio / effort).
 */
data class HrDriftResult(
    val pct: Double,
    /** Minutes d'effort réellement comparées (les deux parties cumulées). */
    val minutes: Int,
    /** true : effort régulier, rapport effort / cardio global ; false : comparaison palier d'effort par palier (fractionné). */
    val steady: Boolean,
)

/**
 * Calcul robuste de la dérive cardiaque :
 * - on écarte les 5 premières minutes, les 2 dernières, et les étapes d'échauffement / retour au calme ;
 * - on ne garde que les secondes où l'effort (puissance, sinon vitesse) est stable depuis 90 s,
 *   car le cardio suit l'effort avec une à deux minutes de retard ; les arrêts, relances et changements de palier sont exclus ;
 * - en course, les portions en côte ou en descente (pente > 4 %) sont exclues ;
 * - effort régulier : rapport global somme(cardio) / somme(effort) entre les deux moitiés du temps retenu ;
 * - effort variable (fractionné) : comparaison par paliers d'effort de 6 %, pondérée par le temps commun aux deux moitiés,
 *   pour ne comparer que des efforts équivalents.
 */
object HrDrift {
    const val MIN_SESSION_SEC = 20 * 60
    private const val SKIP_START = 300
    private const val SKIP_END = 120
    private const val SMOOTH = 30
    private const val STABLE = 90
    private const val MIN_PER_HALF = 300
    private const val MIN_PER_BIN = 60
    private const val BIN_STEP = 1.06
    private const val STEADY_CV = 0.12
    private const val MAX_GRADE = 0.04
    private val EXCLUDED_STEP_WORDS = listOf("chauff", "calme", "warm", "cool")

    fun compute(rec: SessionRecord): HrDriftResult? {
        if (rec.durationSec < MIN_SESSION_SEC) return null
        val samples = rec.samples
        if (samples.size < MIN_SESSION_SEC / 2) return null
        val effort = smooth(rawEffort(rec), SMOOTH)
        val tol = if (rec.sport == Sport.RUNNING) 0.15 else 0.10
        val grades = if (rec.sport == Sport.RUNNING) grades(samples) else null
        val excludedSteps = rec.steps.indices.filter { i ->
            val n = rec.steps[i].name.lowercase()
            EXCLUDED_STEP_WORDS.any { it in n }
        }.toSet()
        val lastT = samples.last().t

        data class Point(val t: Int, val hr: Double, val effort: Double)
        val kept = samples.indices.mapNotNull { i ->
            val s = samples[i]
            val hr = s.hr?.takeIf { it in 70..220 } ?: return@mapNotNull null
            val e = effort[i] ?: return@mapNotNull null
            if (s.t < SKIP_START || s.t > lastT - SKIP_END) return@mapNotNull null
            if (excludedSteps.isNotEmpty() && SessionReport.stepIndexOf(s, rec.steps) in excludedSteps) return@mapNotNull null
            if (!stable(effort, i, tol)) return@mapNotNull null
            val g = grades?.get(i)
            if (g != null && abs(g) > MAX_GRADE) return@mapNotNull null
            Point(s.t, hr.toDouble(), e)
        }
        if (kept.size < 2 * MIN_PER_HALF) return null

        // Récupérations et marche : trop loin de l'effort principal pour être comparables
        val median = kept.map { it.effort }.sorted()[kept.size / 2]
        val work = kept.filter { it.effort >= median * 0.6 }
        if (work.size < 2 * MIN_PER_HALF) return null

        val mid = (work.first().t + work.last().t) / 2.0
        val (first, second) = work.partition { it.t < mid }
        if (first.size < MIN_PER_HALF || second.size < MIN_PER_HALF) return null

        val mean = work.sumOf { it.effort } / work.size
        val cv = sqrt(work.sumOf { (it.effort - mean) * (it.effort - mean) } / work.size) / mean
        val result = if (cv <= STEADY_CV) {
            val a = first.sumOf { it.hr } / first.sumOf { it.effort }
            val b = second.sumOf { it.hr } / second.sumOf { it.effort }
            HrDriftResult((b / a - 1) * 100, work.size / 60, steady = true)
        } else {
            val logStep = ln(BIN_STEP)
            fun bin(e: Double) = floor(ln(e) / logStep).toInt()
            val b1 = first.groupBy { bin(it.effort) }
            val b2 = second.groupBy { bin(it.effort) }
            var weight = 0
            var matched = 0
            var sum = 0.0
            b1.forEach { (k, p1) ->
                val p2 = b2[k] ?: return@forEach
                val w = minOf(p1.size, p2.size)
                if (w < MIN_PER_BIN) return@forEach
                val r1 = p1.sumOf { it.hr } / p1.sumOf { it.effort }
                val r2 = p2.sumOf { it.hr } / p2.sumOf { it.effort }
                sum += (r2 / r1 - 1) * w
                weight += w
                matched += p1.size + p2.size
            }
            if (weight < MIN_PER_HALF) return null
            HrDriftResult(sum / weight * 100, matched / 60, steady = false)
        }
        // Au-delà, c'est un capteur qui décroche (ceinture, montre) plutôt qu'une vraie dérive
        return result.takeIf { abs(it.pct) <= 40 }
    }

    /** Effort instantané : puissance (elliptique, à défaut vitesse) ou vitesse en km/h (course), null à l'arrêt. */
    private fun rawEffort(rec: SessionRecord): List<Double?> {
        val s = rec.samples
        return when (rec.sport) {
            Sport.ELLIPTICAL -> {
                val withPower = s.count { (it.power ?: 0) > 0 }
                val withSpeed = s.count { (it.speed ?: 0.0) > 0.5 }
                if (withPower > 0 && withPower >= withSpeed / 2) s.map { it.power?.takeIf { p -> p > 0 }?.toDouble() }
                else s.map { it.speed?.takeIf { v -> v > 0.5 } }
            }
            Sport.RUNNING -> s.map { it.paceSecPerKm?.takeIf { p -> p in 120..1200 }?.let { p -> 3600.0 / p } }
        }
    }

    /** Moyenne glissante sur les [w] dernières secondes ; null si la valeur courante manque ou si le trou dépasse 20 %. */
    private fun smooth(v: List<Double?>, w: Int): List<Double?> = v.indices.map { i ->
        if (v[i] == null) {
            null
        } else {
            val win = v.subList((i - w + 1).coerceAtLeast(0), i + 1)
            val ok = win.filterNotNull()
            if (ok.size < win.size * 0.8) null else ok.average()
        }
    }

    /** Effort resté à ±[tol] de sa moyenne sur les [STABLE] dernières secondes. */
    private fun stable(e: List<Double?>, i: Int, tol: Double): Boolean {
        if (i < STABLE - 1) return false
        var min = Double.MAX_VALUE
        var max = 0.0
        var sum = 0.0
        for (j in i - STABLE + 1..i) {
            val x = e[j] ?: return false
            if (x < min) min = x
            if (x > max) max = x
            sum += x
        }
        val mean = sum / STABLE
        return max <= mean * (1 + tol) && min >= mean * (1 - tol)
    }

    /** Pente moyenne sur la dernière minute (altitude GPS lissée), null si inconnue. */
    private fun grades(samples: List<Sample>): List<Double?> {
        val alt = Elevation.smoothed(samples)
        return samples.indices.map { i ->
            val j = i - 60
            if (j < 0) return@map null
            val a1 = alt[i] ?: return@map null
            val a0 = alt[j] ?: return@map null
            val d1 = samples[i].distanceM ?: return@map null
            val d0 = samples[j].distanceM ?: return@map null
            val dd = d1 - d0
            if (dd < 50) null else (a1 - a0) / dd
        }
    }
}
