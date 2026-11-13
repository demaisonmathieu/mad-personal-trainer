package com.mathieu.madtrainer.ble

import java.util.UUID

/**
 * Bluetooth FTMS (Fitness Machine Service, v1.0) — UUIDs, parsing et commandes.
 * Code Kotlin pur (sans dépendance Android) pour être testable en JVM.
 */
object FtmsUuids {
    fun uuid16(v: Int): UUID = UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", v))

    val FTMS_SERVICE: UUID = uuid16(0x1826)
    val FEATURE: UUID = uuid16(0x2ACC)
    val CROSS_TRAINER_DATA: UUID = uuid16(0x2ACE)
    val INDOOR_BIKE_DATA: UUID = uuid16(0x2AD2)
    val TRAINING_STATUS: UUID = uuid16(0x2AD3)
    val RESISTANCE_RANGE: UUID = uuid16(0x2AD6)
    val POWER_RANGE: UUID = uuid16(0x2AD8)
    val CONTROL_POINT: UUID = uuid16(0x2AD9)
    val MACHINE_STATUS: UUID = uuid16(0x2ADA)

    val HR_SERVICE: UUID = uuid16(0x180D)
    val HR_MEASUREMENT: UUID = uuid16(0x2A37)

    val CCCD: UUID = uuid16(0x2902)

    /** Service UART propriétaire des anciennes consoles Domyos (avant FTMS). */
    val DOMYOS_LEGACY_SERVICE: UUID = UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455")
}

/** Valeurs instantanées remontées par la machine. null = non fourni. */
data class MachineData(
    val speedKmh: Double? = null,
    val avgSpeedKmh: Double? = null,
    val distanceM: Int? = null,
    /** pas/min (vélo elliptique) ou tr/min (vélo). */
    val cadence: Double? = null,
    val avgCadence: Double? = null,
    val strideCount: Int? = null,
    /** Valeur brute du niveau de résistance (à diviser par l'échelle choisie). */
    val resistanceRaw: Int? = null,
    val powerW: Int? = null,
    val avgPowerW: Int? = null,
    val totalKcal: Int? = null,
    val kcalPerHour: Int? = null,
    val heartRate: Int? = null,
    val elapsedSec: Int? = null,
    val remainingSec: Int? = null,
    val inclinationPct: Double? = null,
) {
    /** Les champs non nuls de [o] écrasent ceux-ci (les trames FTMS peuvent être découpées). */
    fun merge(o: MachineData) = MachineData(
        speedKmh = o.speedKmh ?: speedKmh,
        avgSpeedKmh = o.avgSpeedKmh ?: avgSpeedKmh,
        distanceM = o.distanceM ?: distanceM,
        cadence = o.cadence ?: cadence,
        avgCadence = o.avgCadence ?: avgCadence,
        strideCount = o.strideCount ?: strideCount,
        resistanceRaw = o.resistanceRaw ?: resistanceRaw,
        powerW = o.powerW ?: powerW,
        avgPowerW = o.avgPowerW ?: avgPowerW,
        totalKcal = o.totalKcal ?: totalKcal,
        kcalPerHour = o.kcalPerHour ?: kcalPerHour,
        heartRate = o.heartRate ?: heartRate,
        elapsedSec = o.elapsedSec ?: elapsedSec,
        remainingSec = o.remainingSec ?: remainingSec,
        inclinationPct = o.inclinationPct ?: inclinationPct,
    )
}

class ByteReader(private val b: ByteArray) {
    var pos = 0
        private set

    fun u8(): Int = b[pos++].toInt() and 0xFF
    fun u16(): Int {
        val v = (b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        return v
    }
    fun s16(): Int = u16().toShort().toInt()
    fun u24(): Int {
        val v = (b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8) or
            ((b[pos + 2].toInt() and 0xFF) shl 16)
        pos += 3
        return v
    }
    fun u32(): Long {
        val lo = u16().toLong()
        val hi = u16().toLong()
        return lo or (hi shl 16)
    }
}

private fun Int.bit(n: Int) = (this shr n) and 1 == 1
private fun Int.kcalOrNull() = if (this == 0xFFFF) null else this
private fun Int.hrOrNull() = if (this == 0) null else this

object FtmsParser {

    /** Cross Trainer Data (0x2ACE) — c'est ce qu'envoie un vélo elliptique FTMS. */
    fun parseCrossTrainerData(data: ByteArray): MachineData {
        val r = ByteReader(data)
        var m = MachineData()
        try {
            val f = r.u24()
            if (!f.bit(0)) m = m.copy(speedKmh = r.u16() / 100.0)
            if (f.bit(1)) m = m.copy(avgSpeedKmh = r.u16() / 100.0)
            if (f.bit(2)) m = m.copy(distanceM = r.u24())
            if (f.bit(3)) m = m.copy(cadence = r.u16().toDouble(), avgCadence = r.u16().toDouble())
            if (f.bit(4)) m = m.copy(strideCount = r.u16() / 10)
            if (f.bit(5)) { r.u16(); r.u16() } // dénivelé +/-
            if (f.bit(6)) { m = m.copy(inclinationPct = r.s16() / 10.0); r.s16() }
            if (f.bit(7)) m = m.copy(resistanceRaw = r.s16())
            if (f.bit(8)) m = m.copy(powerW = r.s16())
            if (f.bit(9)) m = m.copy(avgPowerW = r.s16())
            if (f.bit(10)) {
                m = m.copy(totalKcal = r.u16().kcalOrNull(), kcalPerHour = r.u16().kcalOrNull())
                r.u8()
            }
            if (f.bit(11)) m = m.copy(heartRate = r.u8().hrOrNull())
            if (f.bit(12)) r.u8() // MET
            if (f.bit(13)) m = m.copy(elapsedSec = r.u16())
            if (f.bit(14)) m = m.copy(remainingSec = r.u16())
        } catch (_: IndexOutOfBoundsException) {
            // trame tronquée : on garde ce qui a été lu
        }
        return m
    }

    /** Indoor Bike Data (0x2AD2) — certaines consoles Domyos s'annoncent comme vélo. */
    fun parseIndoorBikeData(data: ByteArray): MachineData {
        val r = ByteReader(data)
        var m = MachineData()
        try {
            val f = r.u16()
            if (!f.bit(0)) m = m.copy(speedKmh = r.u16() / 100.0)
            if (f.bit(1)) m = m.copy(avgSpeedKmh = r.u16() / 100.0)
            if (f.bit(2)) m = m.copy(cadence = r.u16() / 2.0)
            if (f.bit(3)) m = m.copy(avgCadence = r.u16() / 2.0)
            if (f.bit(4)) m = m.copy(distanceM = r.u24())
            if (f.bit(5)) m = m.copy(resistanceRaw = r.s16())
            if (f.bit(6)) m = m.copy(powerW = r.s16())
            if (f.bit(7)) m = m.copy(avgPowerW = r.s16())
            if (f.bit(8)) {
                m = m.copy(totalKcal = r.u16().kcalOrNull(), kcalPerHour = r.u16().kcalOrNull())
                r.u8()
            }
            if (f.bit(9)) m = m.copy(heartRate = r.u8().hrOrNull())
            if (f.bit(10)) r.u8()
            if (f.bit(11)) m = m.copy(elapsedSec = r.u16())
            if (f.bit(12)) m = m.copy(remainingSec = r.u16())
        } catch (_: IndexOutOfBoundsException) {
        }
        return m
    }

    /** Heart Rate Measurement (0x2A37). */
    fun parseHeartRate(data: ByteArray): Int? {
        if (data.size < 2) return null
        val r = ByteReader(data)
        val flags = r.u8()
        return try {
            (if (flags.bit(0)) r.u16() else r.u8()).hrOrNull()
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }

    data class Features(val machine: Long, val target: Long) {
        val supportsResistanceTarget get() = (target shr 2) and 1L == 1L
        val supportsPowerTarget get() = (target shr 3) and 1L == 1L
    }

    fun parseFeatures(data: ByteArray): Features? {
        if (data.size < 8) return null
        val r = ByteReader(data)
        return Features(r.u32(), r.u32())
    }

    /** Plage (min, max, pas) — utilisée pour la résistance (0x2AD6) et la puissance (0x2AD8). */
    data class Range(val min: Int, val max: Int, val step: Int)

    fun parseRange(data: ByteArray): Range? {
        if (data.size < 6) return null
        val r = ByteReader(data)
        return Range(r.s16(), r.s16(), r.u16())
    }

    fun describeMachineStatus(data: ByteArray): String {
        if (data.isEmpty()) return "?"
        return when (data[0].toInt() and 0xFF) {
            0x01 -> "Reset"
            0x02 -> "Arrêt/pause par la console"
            0x03 -> "Arrêt par sécurité"
            0x04 -> "Démarré"
            0x07 -> "Nouvelle résistance cible"
            0x08 -> "Nouvelle puissance cible"
            0xFF -> "Contrôle perdu"
            else -> "Statut 0x%02x".format(data[0].toInt() and 0xFF)
        }
    }
}

/** Commandes du Fitness Machine Control Point (0x2AD9). */
object FtmsControl {
    const val OP_REQUEST_CONTROL = 0x00
    const val OP_RESET = 0x01
    const val OP_SET_RESISTANCE = 0x04
    const val OP_SET_POWER = 0x05
    const val OP_START = 0x07
    const val OP_STOP_PAUSE = 0x08
    const val OP_RESPONSE = 0x80

    fun requestControl() = byteArrayOf(OP_REQUEST_CONTROL.toByte())
    fun reset() = byteArrayOf(OP_RESET.toByte())
    fun start() = byteArrayOf(OP_START.toByte())
    fun stop() = byteArrayOf(OP_STOP_PAUSE.toByte(), 0x01)
    fun pause() = byteArrayOf(OP_STOP_PAUSE.toByte(), 0x02)

    /**
     * Résistance cible. FTMS 1.0 définit un UINT8 (résolution 0.1) ; certaines machines
     * attendent un SINT16 → option [wide].
     */
    fun setResistance(raw: Int, wide: Boolean): ByteArray =
        if (wide) {
            byteArrayOf(OP_SET_RESISTANCE.toByte(), (raw and 0xFF).toByte(), ((raw shr 8) and 0xFF).toByte())
        } else {
            byteArrayOf(OP_SET_RESISTANCE.toByte(), raw.coerceIn(0, 255).toByte())
        }

    fun setPower(watts: Int): ByteArray =
        byteArrayOf(OP_SET_POWER.toByte(), (watts and 0xFF).toByte(), ((watts shr 8) and 0xFF).toByte())

    data class Response(val requestOp: Int, val result: Int) {
        val success get() = result == 0x01
        val resultText
            get() = when (result) {
                0x01 -> "OK"
                0x02 -> "non supporté"
                0x03 -> "paramètre invalide"
                0x04 -> "échec"
                0x05 -> "contrôle non accordé"
                else -> "code $result"
            }
    }

    fun parseResponse(data: ByteArray): Response? {
        if (data.size < 3 || (data[0].toInt() and 0xFF) != OP_RESPONSE) return null
        return Response(data[1].toInt() and 0xFF, data[2].toInt() and 0xFF)
    }

    fun opName(op: Int) = when (op) {
        OP_REQUEST_CONTROL -> "Prise de contrôle"
        OP_RESET -> "Reset"
        OP_SET_RESISTANCE -> "Résistance"
        OP_SET_POWER -> "Puissance"
        OP_START -> "Start"
        OP_STOP_PAUSE -> "Stop/Pause"
        else -> "Op 0x%02x".format(op)
    }
}

fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
