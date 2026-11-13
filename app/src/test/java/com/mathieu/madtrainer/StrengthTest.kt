package com.mathieu.madtrainer

import com.mathieu.madtrainer.coach.PainLog
import com.mathieu.madtrainer.coach.StrengthExercise
import com.mathieu.madtrainer.coach.StrengthPlayer
import com.mathieu.madtrainer.coach.StrengthProfile
import com.mathieu.madtrainer.coach.StrengthPrompt
import com.mathieu.madtrainer.coach.StrengthSession
import com.mathieu.madtrainer.coach.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrengthTest {
    @Test
    fun playerExpandsSetsSidesAndRests() {
        val s = StrengthSession(
            name = "A",
            exercises = listOf(
                StrengthExercise("Pont fessier", sets = 2, reps = 12, rest_sec = 45),
                StrengthExercise("Gainage latéral", sets = 2, duration_sec = 30, rest_sec = 30, per_side = true),
            ),
        )
        val steps = StrengthPlayer.expand(s)
        // Pont : T R T R ; Gainage : (G, bascule, D, R) ×2 sans repos final → 4 + 4 + 3 = 11
        assertEquals(11, steps.size)
        assertTrue(steps[0].seconds == null && !steps[0].rest)
        assertEquals(45, steps[1].seconds)
        assertEquals("gauche", steps[4].side)
        assertEquals(10, steps[5].seconds) // bascule entre les côtés
        assertTrue(!steps.last().rest)
    }

    @Test
    fun promptMentionsSciaticRedFlagsAndPainLogs() {
        val prompt = StrengthPrompt.build(
            UserProfile(),
            StrengthProfile(painAreas = listOf("Sciatique"), painLevel = 4, painDetails = "jambe gauche depuis 3 semaines"),
            emptyList(),
            listOf(PainLog(1794560000000, "Renfo A", 4, 6, "pire en flexion")),
        )
        assertTrue(prompt.contains("Sciatique"))
        assertTrue(prompt.contains("4/10"))
        assertTrue(prompt.contains("engourdissement du périnée"))
        assertTrue(prompt.contains("4/10 avant → 6/10 après"))
        assertTrue(prompt.contains("aucun diagnostic"))
    }
}
