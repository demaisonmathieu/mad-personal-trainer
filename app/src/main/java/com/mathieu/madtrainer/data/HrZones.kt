package com.mathieu.madtrainer.data

import kotlin.math.roundToInt

/**
 * Zones cardio 1 à 5.
 * [lows] = borne basse (bpm) de chaque zone, croissante ; la zone 5 va jusqu'à [max].
 * Par défaut : 50 / 60 / 70 / 80 / 90 % de la FC max ; ou zones personnalisées importées de Strava.
 */
data class HrZones(val max: Int, val lows: List<Int>, val custom: Boolean = false) {

    init {
        require(lows.size == 5) { "5 zones attendues" }
    }

    /** Bornes [min, max] en bpm de la zone 1..5. */
    fun range(zone: Int): IntRange {
        val z = zone.coerceIn(1, 5)
        val hi = if (z < 5) lows[z] else max
        return lows[z - 1]..hi
    }

    fun zoneOf(hr: Int): Int = (lows.indexOfLast { hr >= it } + 1).coerceIn(1, 5)

    /** Résumé lisible : « Z1 95–114 · Z2 114–133 … » */
    fun describe(): String = (1..5).joinToString(" · ") { z -> range(z).let { "Z$z ${it.first}–${it.last}" } }

    companion object {
        fun fromMax(max: Int) = HrZones(max, listOf(0.5, 0.6, 0.7, 0.8, 0.9).map { (max * it).roundToInt() })

        /**
         * Zones Strava (GET /athlete/zones) : 5 tranches {min, max}, la première commence souvent à 0
         * et la dernière finit à -1 (« et plus »). On garde les seuils (bornes basses des zones 2 à 5).
         * @return les bornes basses des 5 zones, ou null si le format est inattendu
         */
        fun lowsFromStrava(mins: List<Int>): List<Int>? {
            if (mins.size != 5) return null
            if ((1 until 5).any { mins[it] <= mins[it - 1] } || mins[1] < 60 || mins[4] > 230) return null
            // Zone 1 « 0 et plus » : on prend une borne basse réaliste, de même largeur que la zone 2
            val z1 = if (mins[0] > 40) mins[0] else (mins[1] - (mins[2] - mins[1])).coerceAtLeast(60)
            return listOf(z1) + mins.drop(1)
        }

        /** Zones effectives : personnalisées si présentes, sinon calculées sur la FC max. */
        fun of(maxHr: Int, customLows: List<Int>?): HrZones =
            customLows?.takeIf { it.size == 5 }?.let { HrZones(maxOf(maxHr, it[4] + 5), it, custom = true) } ?: fromMax(maxHr)
    }
}
