package com.mathieu.madtrainer.data

import kotlin.math.roundToInt

/**
 * Hydratation (calcul pur) :
 * - objectif du jour = 35 ml/kg + 500 ml par heure de sport du jour, arrondi à 50 ml ;
 * - rythme attendu : réparti uniformément entre le réveil et une heure avant le coucher ;
 * - rappel si le retard dépasse [REMIND_BEHIND_ML], pas plus d'une fois toutes les [REMIND_SPACING_MIN] minutes.
 */
object Hydration {
    const val GLASS_ML = 250
    const val BOTTLE_ML = 500
    const val REMIND_BEHIND_ML = 300
    const val REMIND_SPACING_MIN = 90

    fun targetMl(weightKg: Double, exerciseMin: Int): Int {
        val raw = weightKg * 35 + exerciseMin * 500.0 / 60
        return ((raw / 50).roundToInt() * 50).coerceIn(1500, 5000)
    }

    /** Quantité qu'on devrait avoir bue à [minuteOfDay] (0 = minuit). */
    fun expectedMl(targetMl: Int, minuteOfDay: Int, wakeHour: Int, sleepHour: Int): Int {
        val start = wakeHour * 60
        val end = ((sleepHour - 1) * 60).coerceAtLeast(start + 60)
        val f = ((minuteOfDay - start).toDouble() / (end - start)).coerceIn(0.0, 1.0)
        return (targetMl * f).roundToInt()
    }

    /**
     * Faut-il envoyer un rappel ? Seulement entre le réveil et le coucher, en cas de vrai retard,
     * et pas si un rappel a été envoyé il y a moins de 90 min ([minutesSinceLastReminder] null = jamais aujourd'hui).
     */
    fun shouldRemind(drunkMl: Int, expectedMl: Int, minuteOfDay: Int, wakeHour: Int, sleepHour: Int, minutesSinceLastReminder: Int?): Boolean {
        if (minuteOfDay < wakeHour * 60 + 60 || minuteOfDay >= sleepHour * 60) return false
        if (expectedMl - drunkMl < REMIND_BEHIND_ML) return false
        return minutesSinceLastReminder == null || minutesSinceLastReminder >= REMIND_SPACING_MIN
    }

    fun reminderText(drunkMl: Int, expectedMl: Int, targetMl: Int): String {
        val glasses = ((expectedMl - drunkMl) / GLASS_ML.toDouble()).roundToInt().coerceAtLeast(1)
        return "Tu as bu ${liters(drunkMl)} sur ${liters(targetMl)}. À cette heure, tu devrais en être à ${liters(expectedMl)} : " +
            "bois ${if (glasses == 1) "un verre" else "$glasses verres"} d'eau."
    }

    /** 1250 → « 1,25 L », 800 → « 0,8 L ». */
    fun liters(ml: Int): String {
        val s = "%.2f".format(java.util.Locale.FRANCE, ml / 1000.0).trimEnd('0').trimEnd(',')
        return "$s L"
    }
}
