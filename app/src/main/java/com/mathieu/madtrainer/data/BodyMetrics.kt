package com.mathieu.madtrainer.data

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Type de mesure lue dans Health Connect. */
enum class BodyKind { WEIGHT, BODY_FAT_PCT, LEAN_MASS, BONE_MASS, WATER_MASS, BMR, HEIGHT }

/** Une valeur brute lue dans Health Connect (une ligne par enregistrement). */
data class BodyPoint(val time: Long, val kind: BodyKind, val value: Double, val source: String)

/** Une pesée : toutes les valeurs écrites par la balance au même moment. */
@Serializable
data class BodyMeasure(
    val time: Long,
    val source: String = "",
    val weightKg: Double? = null,
    val bodyFatPct: Double? = null,
    val leanMassKg: Double? = null,
    val boneMassKg: Double? = null,
    val waterKg: Double? = null,
    val bmrKcal: Int? = null,
) {
    /** Eau corporelle en % du poids (Health Connect la stocke en kg). */
    val waterPct: Double? get() = if (waterKg != null && weightKg != null && weightKg > 0) waterKg / weightKg * 100 else null

    /** Masse grasse en kg. */
    val fatMassKg: Double? get() = if (bodyFatPct != null && weightKg != null) weightKg * bodyFatPct / 100 else null
}

@Serializable
data class RestingHr(val time: Long, val bpm: Int, val source: String = "")

/** VO2max (ml/kg/min) calculée par la montre (Zepp) et lue dans Health Connect. */
@Serializable
data class Vo2MaxPoint(val time: Long, val value: Double, val source: String = "")

/** Copie locale des données Health Connect (affichage hors ligne, prompts IA). */
@Serializable
data class BodyCache(
    val measures: List<BodyMeasure> = emptyList(),
    val restingHr: List<RestingHr> = emptyList(),
    val heightCm: Int? = null,
    val lastSync: Long = 0,
    val vo2max: List<Vo2MaxPoint> = emptyList(),
)

/** Évolution d'une grandeur sur une période. */
data class BodyTrend(val days: Int, val from: Double, val to: Double) {
    val delta: Double get() = to - from
    val perWeek: Double get() = if (days > 0) delta / days * 7 else 0.0
}

object BodyStats {
    private const val DAY = 86_400_000L

    /** Écart maximal entre les valeurs d'une même pesée (la balance les écrit à quelques secondes d'intervalle). */
    const val SAME_WEIGHING_MS = 2 * 60_000L

    /** Regroupe les valeurs brutes en pesées (même source, à moins de 2 min d'écart), de la plus ancienne à la plus récente. */
    fun group(points: List<BodyPoint>): List<BodyMeasure> {
        val out = mutableListOf<BodyMeasure>()
        points.filter { it.kind != BodyKind.HEIGHT }.groupBy { it.source }.forEach { (source, list) ->
            var current: BodyMeasure? = null
            list.sortedBy { it.time }.forEach { p ->
                val c = current
                current = if (c != null && p.time - c.time <= SAME_WEIGHING_MS && !c.has(p.kind)) {
                    c.with(p)
                } else {
                    c?.let { out += it }
                    BodyMeasure(time = p.time, source = source).with(p)
                }
            }
            current?.let { out += it }
        }
        return out.sortedBy { it.time }
    }

    private fun BodyMeasure.has(k: BodyKind) = when (k) {
        BodyKind.WEIGHT -> weightKg != null
        BodyKind.BODY_FAT_PCT -> bodyFatPct != null
        BodyKind.LEAN_MASS -> leanMassKg != null
        BodyKind.BONE_MASS -> boneMassKg != null
        BodyKind.WATER_MASS -> waterKg != null
        BodyKind.BMR -> bmrKcal != null
        BodyKind.HEIGHT -> false
    }

    private fun BodyMeasure.with(p: BodyPoint) = when (p.kind) {
        BodyKind.WEIGHT -> copy(weightKg = p.value)
        BodyKind.BODY_FAT_PCT -> copy(bodyFatPct = p.value)
        BodyKind.LEAN_MASS -> copy(leanMassKg = p.value)
        BodyKind.BONE_MASS -> copy(boneMassKg = p.value)
        BodyKind.WATER_MASS -> copy(waterKg = p.value)
        BodyKind.BMR -> copy(bmrKcal = p.value.roundToInt())
        BodyKind.HEIGHT -> this
    }

    /** Dernière taille connue, en cm. */
    fun latestHeightCm(points: List<BodyPoint>): Int? =
        points.filter { it.kind == BodyKind.HEIGHT }.maxByOrNull { it.time }?.value?.let { (it * 100).roundToInt() }

    /** Dernière pesée comportant un poids. */
    fun latest(measures: List<BodyMeasure>): BodyMeasure? = measures.filter { it.weightKg != null }.maxByOrNull { it.time }

    /**
     * Tendance d'une grandeur sur les [days] derniers jours : moyenne des 7 premiers jours de la période
     * comparée à la moyenne des 7 derniers (lisse les variations d'eau d'un jour à l'autre).
     */
    fun trend(measures: List<BodyMeasure>, days: Int, now: Long, value: (BodyMeasure) -> Double?): BodyTrend? {
        val start = now - days * DAY
        val pts = measures.filter { it.time in start..now }.mapNotNull { m -> value(m)?.let { m.time to it } }.sortedBy { it.first }
        if (pts.size < 2) return null
        val first = pts.first().first
        val last = pts.last().first
        val span = ((last - first) / DAY).toInt()
        if (span < 3) return null
        val window = minOf(7L, span / 3L) * DAY
        val head = pts.filter { it.first <= first + window }.map { it.second }.average()
        val tail = pts.filter { it.first >= last - window }.map { it.second }.average()
        return BodyTrend(span, head, tail)
    }

    /** Moyenne de la FC de repos sur les 7 derniers jours. */
    /** Dernière VO2max de la montre mesurée au plus tard à [at] (et pas plus de 60 jours avant). */
    fun watchVo2max(list: List<Vo2MaxPoint>, at: Long): Vo2MaxPoint? =
        list.filter { it.time <= at && it.time >= at - 60 * DAY && it.value in 10.0..90.0 }.maxByOrNull { it.time }

    fun recentRestingHr(list: List<RestingHr>, now: Long): Int? =
        list.filter { it.time >= now - 7 * DAY && it.time <= now }.map { it.bpm }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()

    /** Valeurs pour une courbe : une par jour (la dernière pesée du jour), nulle les jours sans pesée. */
    fun dailySeries(measures: List<BodyMeasure>, days: Int, now: Long, value: (BodyMeasure) -> Double?): List<Float?> {
        val startDay = now / DAY - days + 1
        val byDay = measures.mapNotNull { m -> value(m)?.let { (m.time / DAY) to it } }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.last() }
        return (0 until days).map { i -> byDay[startDay + i]?.toFloat() }
    }

    /** Résumé pour les prompts du coach IA. */
    fun describe(cache: BodyCache, now: Long = System.currentTimeMillis()): String? {
        val last = latest(cache.measures) ?: return null
        val date = SimpleDateFormat("d MMMM yyyy", Locale.FRANCE).format(Date(last.time))
        return buildString {
            val parts = mutableListOf("poids ${fmt(last.weightKg!!)} kg")
            last.bodyFatPct?.let { parts += "masse grasse ${fmt(it)} %" }
            last.leanMassKg?.let { parts += "masse maigre ${fmt(it)} kg" }
            last.waterPct?.let { parts += "eau ${fmt(it)} %" }
            last.boneMassKg?.let { parts += "masse osseuse ${fmt(it)} kg" }
            last.bmrKcal?.let { parts += "métabolisme de base $it kcal/j" }
            appendLine("- Dernière pesée (balance connectée, $date) : ${parts.joinToString(", ")}")
            listOf(30, 90).forEach { d ->
                val w = trend(cache.measures, d, now) { it.weightKg }
                val f = trend(cache.measures, d, now) { it.bodyFatPct }
                if (w != null) {
                    appendLine(
                        "- Sur $d jours : poids ${signed(w.delta)} kg (${signed(w.perWeek)} kg/semaine)" +
                            (f?.let { ", masse grasse ${signed(it.delta)} point(s)" } ?: ""),
                    )
                }
            }
            recentRestingHr(cache.restingHr, now)?.let { appendLine("- FC de repos (moyenne 7 jours, montre) : $it bpm") }
            watchVo2max(cache.vo2max, now)?.let {
                appendLine("- VO2max calculée par la montre : ${fmt(it.value)} ml/kg/min (${SimpleDateFormat("d MMMM", Locale.FRANCE).format(Date(it.time))})")
            }
        }.trimEnd()
    }

    fun fmt(v: Double): String = "%.1f".format(Locale.FRANCE, v)

    fun signed(v: Double): String = (if (v > 0) "+" else if (v < 0) "−" else "") + fmt(abs(v))
}
