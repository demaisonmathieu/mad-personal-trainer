package com.mathieu.madtrainer.data

import kotlin.math.roundToInt

/** Dénivelé d'une course : [gainM] montée cumulée, [lossM] descente cumulée, [rangeM] écart entre le point le plus haut et le plus bas. */
data class ElevationStats(val gainM: Int, val lossM: Int, val rangeM: Int)

/**
 * Dénivelé à partir de l'altitude GPS, bruitée de plusieurs mètres :
 * moyenne glissante sur ~20 s puis hystérésis (une variation n'est comptée qu'au-delà de [THRESHOLD] m).
 */
object Elevation {
    private const val WINDOW = 21
    const val THRESHOLD = 3.0

    /** Altitudes lissées, alignées sur [samples] (null tant qu'aucune altitude n'est connue). */
    fun smoothed(samples: List<Sample>): List<Double?> {
        val alts = samples.map { it.alt }
        val half = WINDOW / 2
        return alts.indices.map { i ->
            if (alts[i] == null) null
            else alts.subList((i - half).coerceAtLeast(0), (i + half + 1).coerceAtMost(alts.size)).filterNotNull().average()
        }
    }

    fun gainLoss(values: List<Double>, threshold: Double = THRESHOLD): Pair<Double, Double> {
        if (values.isEmpty()) return 0.0 to 0.0
        var ref = values.first()
        var gain = 0.0
        var loss = 0.0
        for (v in values) {
            if (v - ref >= threshold) {
                gain += v - ref
                ref = v
            } else if (ref - v >= threshold) {
                loss += ref - v
                ref = v
            }
        }
        return gain to loss
    }

    /** Null si moins d'une minute de mesures d'altitude (anciennes courses, GPS sans altitude). */
    fun stats(samples: List<Sample>): ElevationStats? {
        val s = smoothed(samples).filterNotNull()
        if (s.size < 60) return null
        val (g, l) = gainLoss(s)
        return ElevationStats(g.roundToInt(), l.roundToInt(), (s.max() - s.min()).roundToInt())
    }
}
