package com.mathieu.madtrainer.ble

import java.util.UUID
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Protocole propriétaire des consoles Domyos sans FTMS (ex. Domyos-EL-xxxx, puce Microchip "transparent UART").
 * Trames documentées d'après le projet open source qdomyos-zwift (cagnulein/qdomyos-zwift).
 * Code Kotlin pur, testable en JVM.
 */
object DomyosLegacy {
    val SERVICE: UUID = UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455")
    val WRITE: UUID = UUID.fromString("49535343-8841-43f4-a8d4-ecbe34729bb3")
    val NOTIFY: UUID = UUID.fromString("49535343-1e4d-4bd9-ba61-23c647249616")

    const val MIN_RESISTANCE = 1
    const val MAX_RESISTANCE = 15
    const val STATUS_LENGTH = 26

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** Une trame à écrire + faut-il attendre une réponse de la console avant la suivante. */
    data class Frame(val bytes: ByteArray, val waitResponse: Boolean)

    val NOOP = b(0xf0, 0xac, 0x9c)
    val STOP = b(0xf0, 0xc8, 0x00, 0xb8)

    /** Séquence d'initialisation ; [start] ajoute la commande de démarrage de séance. */
    fun initSequence(start: Boolean): List<Frame> = buildList {
        add(Frame(b(0xf0, 0xc8, 0x01, 0xb9), true))
        add(Frame(b(0xf0, 0xc9, 0xb9), true))
        add(Frame(b(0xf0, 0xa3, 0x93), true))
        add(Frame(b(0xf0, 0xa4, 0x94), true))
        add(Frame(b(0xf0, 0xa5, 0x95), true))
        add(Frame(b(0xf0, 0xab, 0x9b), true))
        add(Frame(b(0xf0, 0xc4, 0x03, 0xb7), true))
        add(Frame(b(0xf0, 0xad, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x01, 0xff), false))
        add(Frame(b(0xff, 0xff, 0x8b), true))
        add(Frame(b(0xf0, 0xcb, 0x02, 0x00, 0x08, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x01, 0x00), false))
        add(Frame(b(0x00, 0x01, 0xff, 0xff, 0xff, 0xff, 0xb6), true))
        add(Frame(b(0xf0, 0xad, 0xff, 0xff, 0x00, 0x05, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x00, 0x00, 0xff, 0xff, 0xff, 0x01, 0xff), false))
        if (start) add(Frame(b(0xff, 0xff, 0x94), true))
        add(Frame(b(0xf0, 0xcb, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x01, 0x00, 0x14, 0x01, 0xff, 0xff), false))
        add(Frame(b(0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xbd), true))
    }

    /**
     * Commande de résistance (1..15), découpée en 20 + 3 octets ; dernier octet = somme des précédents.
     * Trame "F0 AD" : chaque octet est un paramètre, 0xFF = inchangé. L'octet 17 doit rester à 0xFF
     * (la console Domyos-EL rejette la trame silencieusement s'il vaut 0x00 — constaté sur EL500).
     * La console confirme par une trame "F0 BD" en écho.
     */
    fun resistanceFrames(level: Int): List<Frame> {
        val w = b(
            0xf0, 0xad, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
            0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x01, 0xff, 0xff, 0xff, 0x00,
        )
        w[10] = level.coerceIn(MIN_RESISTANCE, MAX_RESISTANCE).toByte()
        var sum = 0
        for (i in 0 until 22) sum += w[i].toInt() and 0xFF
        w[22] = (sum and 0xFF).toByte()
        // Les deux morceaux doivent partir sans pause entre eux (comme à l'init), sinon la console
        // abandonne la trame incomplète : on n'attend la réponse qu'après le dernier morceau.
        return listOf(Frame(w.copyOfRange(0, 20), false), Frame(w.copyOfRange(20, 23), true))
    }

    private fun withChecksum(w: ByteArray): ByteArray {
        var sum = 0
        for (i in 0 until w.size - 1) sum += w[i].toInt() and 0xFF
        w[w.size - 1] = (sum and 0xFF).toByte()
        return w
    }

    private fun split(w: ByteArray) = listOf(Frame(w.copyOfRange(0, 20), false), Frame(w.copyOfRange(20, w.size), true))

    /**
     * Mise à jour de l'écran de la console pendant une séance (trames "F0 CD" puis "F0 CB 03").
     * Fait passer la console de l'écran "Bluetooth" à l'affichage de séance.
     */
    fun displayFrames(elapsedSec: Int, speedKmh: Double, cadence: Int, heartRate: Int, kcal: Int, odometerKm: Double): List<Frame> {
        val d2 = b(
            0xf0, 0xcd, 0x01, 0x00, 0x00, 0x01, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
            0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x00,
        )
        val odo = (odometerKm * 10).toInt().coerceIn(0, 0xFFFF)
        d2[3] = (odo shr 8).toByte()
        d2[4] = (odo and 0xFF).toByte()
        val d = b(
            0xf0, 0xcb, 0x03, 0x00, 0x00, 0xff, 0x01, 0x00, 0x00, 0x02, 0x01, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0xff, 0xff, 0xff, 0xff, 0x00,
        )
        d[3] = ((elapsedSec / 60) and 0xFF).toByte()
        d[4] = ((elapsedSec % 60) and 0xFF).toByte()
        val sp = speedKmh.toInt().coerceIn(0, 0xFFFF)
        d[7] = (sp shr 8).toByte()
        d[8] = (sp and 0xFF).toByte()
        d[12] = heartRate.coerceIn(0, 255).toByte()
        d[16] = cadence.coerceIn(0, 255).toByte()
        val k = kcal.coerceIn(0, 0xFFFF)
        d[19] = (k shr 8).toByte()
        d[20] = (k and 0xFF).toByte()
        return split(withChecksum(d2)) + split(withChecksum(d))
    }

    /** Écho "F0 BD" d'une commande "F0 AD" acceptée : renvoie le niveau de résistance confirmé, sinon null. */
    fun parseCommandAck(p: ByteArray): Int? {
        if (p.size < 12 || (p[0].toInt() and 0xFF) != 0xF0 || (p[1].toInt() and 0xFF) != 0xBD) return null
        return (p[10].toInt() and 0xFF).takeIf { it in MIN_RESISTANCE..MAX_RESISTANCE }
    }

    data class Status(
        val speedKmh: Double,
        val cadence: Int,
        val kcal: Int,
        val resistance: Int,
        val heartRate: Int?,
    )

    /** Trame d'état de 26 octets envoyée par la console. */
    fun parseStatus(p: ByteArray): Status? {
        if (p.size != STATUS_LENGTH) return null
        fun u(i: Int) = p[i].toInt() and 0xFF
        val res = u(14).let { if (it < MIN_RESISTANCE) MIN_RESISTANCE else it }
        return Status(
            speedKmh = ((u(6) shl 8) or u(7)) / 10.0,
            cadence = u(9),
            kcal = (u(10) shl 8) or u(11),
            resistance = res,
            heartRate = u(18).takeIf { it in 30..230 },
        )
    }

    /** Puissance estimée (la console ne la transmet pas) : modèle résistance × cadence. */
    fun estimatePower(resistance: Int, cadence: Int, speedKmh: Double): Int {
        if (speedKmh <= 0.0 || cadence <= 0) return 0
        return ((10.39 + 1.45 * (resistance - 1.0)) * exp(0.028 * cadence)).roundToInt()
    }

    /** Résistance qui donne [targetW] à la cadence actuelle (mode ERG simulé). */
    fun resistanceForPower(targetW: Int, cadence: Int): Int {
        if (cadence <= 0) return MIN_RESISTANCE
        val r = (targetW / exp(0.028 * cadence) - 10.39) / 1.45 + 1.0
        return r.roundToInt().coerceIn(MIN_RESISTANCE, MAX_RESISTANCE)
    }

    /**
     * Réassemble les notifications : une trame d'état peut arriver en plusieurs morceaux
     * (20 + 6 octets si le MTU est petit). Les trames commencent par 0xF0.
     */
    class Assembler {
        private var buf = ByteArray(0)

        fun push(chunk: ByteArray): ByteArray? {
            if (chunk.size == STATUS_LENGTH) {
                buf = ByteArray(0)
                return chunk
            }
            buf = if (chunk.isNotEmpty() && (chunk[0].toInt() and 0xFF) == 0xF0) chunk.copyOf() else buf + chunk
            return when {
                buf.size == STATUS_LENGTH -> buf.also { buf = ByteArray(0) }
                buf.size > STATUS_LENGTH -> {
                    buf = ByteArray(0)
                    null
                }
                else -> null
            }
        }
    }
}
