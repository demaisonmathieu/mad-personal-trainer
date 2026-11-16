package com.mathieu.madtrainer

import com.mathieu.madtrainer.data.Hydration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HydrationTest {
    @Test
    fun targetFromWeightAndExercise() {
        assertEquals(2800, Hydration.targetMl(80.0, 0)) // 80 × 35
        assertEquals(3300, Hydration.targetMl(80.0, 60)) // + 500 ml pour 1 h de sport
        assertEquals(1500, Hydration.targetMl(30.0, 0)) // plancher
    }

    @Test
    fun expectedIsSpreadFromWakeToOneHourBeforeBed() {
        // Réveil 8 h, coucher 22 h → réparti de 8 h à 21 h (13 h)
        assertEquals(0, Hydration.expectedMl(2600, 7 * 60, 8, 22))
        assertEquals(1300, Hydration.expectedMl(2600, (14 * 60 + 30), 8, 22))
        assertEquals(2600, Hydration.expectedMl(2600, 21 * 60 + 30, 8, 22))
    }

    @Test
    fun remindOnlyWhenBehindAwakeAndNotTooOften() {
        val noon = 12 * 60
        assertTrue(Hydration.shouldRemind(300, 800, noon, 8, 22, null))
        assertFalse(Hydration.shouldRemind(600, 800, noon, 8, 22, null)) // 200 ml de retard : tolérance
        assertFalse(Hydration.shouldRemind(300, 800, noon, 8, 22, 45)) // rappel récent
        assertTrue(Hydration.shouldRemind(300, 800, noon, 8, 22, 95))
        assertFalse(Hydration.shouldRemind(0, 2000, 23 * 60, 8, 22, null)) // la nuit
        assertFalse(Hydration.shouldRemind(0, 100, 8 * 60 + 30, 8, 22, null)) // juste après le réveil
    }

    @Test
    fun litersAndText() {
        assertEquals("1,25 L", Hydration.liters(1250))
        assertEquals("0,8 L", Hydration.liters(800))
        assertEquals("2 L", Hydration.liters(2000))
        assertTrue(Hydration.reminderText(500, 1000, 2600).contains("bois 2 verres"))
    }
}
