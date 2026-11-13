package com.mathieu.madtrainer.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExerciseCatalogTest {
    private val catalog: ExerciseCatalog by lazy {
        val f = listOf("src/main/assets/exercises.json", "app/src/main/assets/exercises.json").map(::File).first { it.exists() }
        ExerciseCatalog.parse(f.readText())
    }

    @Test
    fun allFrenchKeywordsPointToExistingExercises() {
        ExerciseCatalog.FR_KEYWORDS.forEach { (fr, id) -> assertTrue("$fr → $id", catalog.byId(id) != null) }
    }

    @Test
    fun guessesFromFrenchNames() {
        assertEquals("Side_Bridge", catalog.find(StrengthExercise(name = "Gainage latéral sur les genoux"))?.id)
        assertEquals("Plank", catalog.find(StrengthExercise(name = "Gainage ventral"))?.id)
        assertEquals("Cat_Stretch", catalog.find(StrengthExercise(name = "Chat-vache"))?.id)
        assertEquals("Bodyweight_Squat", catalog.find(StrengthExercise(name = "Squats au poids du corps"))?.id)
        assertEquals("Single_Leg_Glute_Bridge", catalog.find(StrengthExercise(name = "Pont fessier unilatéral"))?.id)
        assertEquals(null, catalog.find(StrengthExercise(name = "Respiration diaphragmatique")))
    }

    @Test
    fun aiIdWinsAndEmptyMeansNone() {
        assertEquals("Dead_Bug", catalog.find(StrengthExercise(name = "Gainage", image_id = "dead_bug"))?.id)
        assertEquals(null, catalog.find(StrengthExercise(name = "Gainage", image_id = "")))
        // identifiant inventé → repli sur le nom
        assertEquals("Plank", catalog.find(StrengthExercise(name = "Gainage", image_id = "Invented_Thing"))?.id)
    }

    @Test
    fun searchAndPromptIds() {
        assertEquals("Superman", catalog.search("superman").first().id)
        assertTrue(catalog.search("pont fessier").any { it.id.contains("Bridge") })
        val ids = catalog.idsForPrompt(listOf("Tapis"))
        assertTrue(ids.size in 50..400)
        assertTrue("Plank" in ids && "Barbell_Squat" !in ids)
        assertTrue(ExerciseCatalog.youtubeSearchUrl("Pont fessier").startsWith("https://www.youtube.com/results?search_query=Pont+fessier"))
        assertTrue(catalog.byId("Plank")!!.images.first().endsWith("/exercises/Plank/0.jpg"))
    }
}
