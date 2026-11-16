package com.mathieu.madtrainer

import com.mathieu.madtrainer.data.Elevation
import com.mathieu.madtrainer.data.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevationTest {
    @Test
    fun hysteresisIgnoresSmallNoise() {
        // Bruit de ±1 m autour de 100 m : aucun dénivelé
        val (g, l) = Elevation.gainLoss(List(100) { 100.0 + if (it % 2 == 0) 1.0 else -1.0 })
        assertEquals(0.0, g, 0.0)
        assertEquals(0.0, l, 0.0)
    }

    @Test
    fun climbAndDescentWithGpsNoise() {
        // Montée de 50 m en 5 min puis redescente, avec ±4 m de bruit une seconde sur deux
        val alts = (0..600).map { t ->
            val base = if (t <= 300) 100.0 + t / 6.0 else 150.0 - (t - 300) / 6.0
            base + if (t % 2 == 0) 4.0 else -4.0
        }
        val samples = alts.mapIndexed { t, a -> Sample(t = t, alt = a) }
        val e = Elevation.stats(samples)!!
        assertTrue("D+ ${e.gainM}", e.gainM in 44..54)
        assertTrue("D− ${e.lossM}", e.lossM in 44..54)
        assertTrue(e.rangeM in 45..55)
    }

    @Test
    fun noAltitudeNoStats() {
        assertNull(Elevation.stats(List(300) { Sample(t = it) }))
    }
}
