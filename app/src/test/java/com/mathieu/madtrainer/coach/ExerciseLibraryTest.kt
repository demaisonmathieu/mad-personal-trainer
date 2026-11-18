package com.mathieu.madtrainer.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExerciseLibraryTest {
    private val catalog: ExerciseCatalog by lazy {
        val f = listOf("src/main/assets/exercises.json", "app/src/main/assets/exercises.json").map(::File).first { it.exists() }
        ExerciseCatalog.parse(f.readText())
    }
    private val homeProfile = StrengthProfile(equipment = listOf("Tapis", "Élastiques"), sessionMinutes = 30)

    private fun ex(id: String, sets: Int = 3) = ExerciseLibrary.toSessionExercise(catalog.byId(id)!!).copy(sets = sets)
    private fun analyze(vararg ids: String, profile: StrengthProfile = homeProfile) =
        SessionAdvisor.analyze(StrengthSession(exercises = ids.map { ex(it) }), catalog, profile, 20 * 60)

    @Test
    fun enrichedFieldsAreLoaded() {
        val squat = catalog.byId("Bodyweight_Squat")!!
        assertEquals("Squat au poids du corps", squat.label)
        assertEquals("c", squat.k)
        assertTrue(squat.d.isNotBlank())
        assertTrue(catalog.all.all { !it.fr.isNullOrBlank() })
        assertTrue(catalog.byId("Pushups")!!.d.startsWith("Allonge-toi"))
        assertEquals(catalog.byId("Pushups")!!.d, ex("Pushups").instructions)
        assertTrue(catalog.all.all { it.l in ExerciseLabels.LEVELS.keys })
        assertTrue(catalog.all.all { it.m.isEmpty() || it.m in ExerciseLabels.MUSCLES.keys })
        assertTrue(catalog.all.all { it.e in ExerciseLabels.EQUIPMENT.keys })
    }

    @Test
    fun filtersByGroupEquipmentAndLevel() {
        val allowed = ExerciseCatalog.allowedEquipment(homeProfile.equipment)
        val f = ExerciseFilter(groups = setOf(MuscleGroup.QUADS), equipment = allowed, categories = setOf("strength"), levels = setOf("b"))
        val res = ExerciseLibrary.filter(catalog, f)
        assertTrue(res.isNotEmpty())
        assertTrue(res.all { it.m == "quadriceps" && it.e in allowed && it.l == "b" && it.c == "strength" })
        // les exercices traduits passent en premier
        assertTrue(res.first().fr != null)
    }

    @Test
    fun searchFindsFrenchNames() {
        val res = ExerciseLibrary.filter(catalog, ExerciseFilter(query = "monster walk"))
        assertEquals("Monster_Walk", res.first().id)
        assertTrue(ExerciseLibrary.filter(catalog, ExerciseFilter(query = "oiseau")).any { it.id == "Reverse_Flyes" })
    }

    @Test
    fun complementsAreAntagonists() {
        val pushups = catalog.byId("Pushups")!!
        val comp = ExerciseLibrary.complements(catalog, pushups, ExerciseCatalog.allowedEquipment(homeProfile.equipment))
        assertTrue(comp.isNotEmpty())
        assertTrue(comp.all { it.m in setOf("lats", "middle back") })
        val alt = ExerciseLibrary.alternatives(catalog, pushups, null)
        assertTrue(alt.all { it.m == "chest" && it.id != "Pushups" })
    }

    @Test
    fun defaultsDependOnExerciseType() {
        assertEquals(30, ex("Plank").duration_sec)
        assertEquals(null, ex("Plank").reps)
        assertEquals(10, ex("Bodyweight_Squat").reps)
        assertTrue(ex("Single_Leg_Glute_Bridge").per_side)
        assertEquals(30, ex("Hamstring_Stretch").duration_sec)
        assertEquals("Pushups", ex("Pushups").image_id)
    }

    @Test
    fun pushPullImbalanceSuggestsBackWork() {
        val advice = analyze("Pushups", "Push-Up_Wide", "Dumbbell_Shoulder_Press")
        val a = advice.first { it.title.contains("poussée") }
        assertTrue(a.suggestions.isNotEmpty())
        assertTrue(a.suggestions.all { it.m in setOf("lats", "middle back") })
    }

    @Test
    fun painAreasFlagRiskyExercises() {
        val knees = homeProfile.copy(painAreas = listOf("Genoux"))
        val advice = analyze("Bodyweight_Squat", "Freehand_Jump_Squat", "Plank", profile = knees)
        val warn = advice.first { it.level == SessionAdvice.Level.WARNING && it.title.contains("douleurs") }
        assertEquals(listOf(1), warn.exercises)
    }

    @Test
    fun antagonistNeighboursAreSupersets() {
        val advice = analyze("Pushups", "Inverted_Row", "Bodyweight_Squat", "Plank")
        assertTrue(advice.any { it.level == SessionAdvice.Level.GOOD && it.title.contains("Superséries") })
    }

    @Test
    fun orderAndCoverageTips() {
        // isolation des fessiers avant un polyarticulaire du même groupe, rien pour les abdos
        val advice = analyze("Single_Leg_Glute_Bridge", "Glute_Kickback", "Bodyweight_Squat", "Pushups", "Inverted_Row")
        assertTrue(advice.any { it.title == "Ordre des exercices" })
        val missing = advice.first { it.title == "Groupes non travaillés" }
        assertTrue(missing.text.contains("abdominale"))
        assertTrue(advice.any { it.title == "Retour au calme" && it.suggestions.all { s -> s.c == "stretching" } })
    }

    @Test
    fun emptySessionGivesStartingPoint() {
        val advice = analyze()
        assertEquals(1, advice.size)
        assertFalse(advice.first().suggestions.isEmpty())
    }
}
