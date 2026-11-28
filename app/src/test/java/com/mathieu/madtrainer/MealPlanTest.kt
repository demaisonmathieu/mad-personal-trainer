package com.mathieu.madtrainer

import com.mathieu.madtrainer.coach.Sex
import com.mathieu.madtrainer.data.ActivityLevel
import com.mathieu.madtrainer.data.CalorieGoal
import com.mathieu.madtrainer.data.FoodEntry
import com.mathieu.madtrainer.data.MealPlan
import com.mathieu.madtrainer.data.MealSlot
import com.mathieu.madtrainer.data.NutritionTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MealPlanTest {
    private val man = NutritionTargets(Sex.MALE, 30, 80.0, 180, ActivityLevel.MODERATE, CalorieGoal.MAINTAIN)

    @Test
    fun mifflinAndTdee() {
        // 10 × 80 + 6,25 × 180 − 5 × 30 + 5 = 1780
        assertEquals(1780, MealPlan.bmr(man))
        assertEquals(1614, MealPlan.bmr(man.copy(sex = Sex.FEMALE)))
        assertEquals(2759, MealPlan.tdee(man)) // × 1,55
        assertEquals(2259, MealPlan.targetKcal(man.copy(goal = CalorieGoal.LOSS)))
        assertEquals(3059, MealPlan.targetKcal(man.copy(goal = CalorieGoal.GAIN)))
        assertEquals(1900, MealPlan.targetKcal(man.copy(goal = CalorieGoal.CUSTOM, customKcal = 1900)))
        // Jamais sous le métabolisme de base
        assertEquals(1780, MealPlan.targetKcal(man.copy(activity = ActivityLevel.SEDENTARY, goal = CalorieGoal.LOSS)))
    }

    @Test
    fun macroGramsFromPercentages() {
        val g = MealPlan.macroGrams(man.copy(goal = CalorieGoal.CUSTOM, customKcal = 2000, proteinPct = 30, carbsPct = 45, fatPct = 25))
        assertEquals(150.0, g.protein, 0.01)
        assertEquals(225.0, g.carbs, 0.01)
        assertEquals(55.6, g.fat, 0.1)
    }

    @Test
    fun slidersStaySynchronisedAt100() {
        assertEquals(Triple(40, 39, 21), MealPlan.rebalance(Triple(30, 45, 25), 0, 40))
        assertEquals(Triple(15, 70, 15), MealPlan.rebalance(Triple(30, 40, 30), 1, 70))
        val r = MealPlan.rebalance(Triple(0, 100, 0), 1, 60)
        assertEquals(Triple(20, 60, 20), r)
        for (i in 0..2) for (v in 0..100 step 7) {
            val t = MealPlan.rebalance(Triple(33, 33, 34), i, v)
            assertEquals(100, t.first + t.second + t.third)
        }
    }

    @Test
    fun entriesInGramsOrUnits() {
        val foods = MealPlan.foodsById(emptyList())
        val egg = foods.getValue("egg")
        val twoEggs = FoodEntry(1, "egg", MealSlot.BREAKFAST, 2.0, inUnits = true)
        assertEquals(110.0, MealPlan.grams(twoEggs, egg), 0.01)
        assertEquals(154.0, MealPlan.nutrients(twoEggs, egg).kcal, 0.01)
        val grams = MealPlan.toggleUnits(twoEggs, egg)
        assertEquals(110.0, grams.quantity, 0.01)
        assertEquals(twoEggs.quantity, MealPlan.toggleUnits(grams, egg).quantity, 0.01)

        val day = listOf(twoEggs, FoodEntry(2, "rice", MealSlot.LUNCH, 200.0))
        assertEquals(414.0, MealPlan.total(day, foods).kcal, 0.01)
        assertEquals(260.0, MealPlan.bySlot(day, foods).getValue(MealSlot.LUNCH).kcal, 0.01)
    }

    @Test
    fun weekStartsOnMonday() {
        val saturday = java.time.LocalDate.of(2026, 11, 28).toEpochDay()
        val monday = java.time.LocalDate.of(2026, 11, 23).toEpochDay()
        assertEquals(monday, MealPlan.weekStart(saturday))
        assertEquals(monday, MealPlan.weekStart(monday))
    }

    @Test
    fun shoppingListAddsUpAndConvertsToRawWeight() {
        val foods = MealPlan.foodsById(emptyList())
        val week = listOf(
            FoodEntry(1, "egg", MealSlot.BREAKFAST, 2.0, inUnits = true),
            FoodEntry(2, "egg", MealSlot.BREAKFAST, 110.0), // 2 œufs saisis en grammes un autre jour
            FoodEntry(3, "rice", MealSlot.LUNCH, 200.0),
            FoodEntry(4, "rice", MealSlot.DINNER, 200.0),
            FoodEntry(5, "olive_oil", MealSlot.LUNCH, 10.0, inUnits = false),
        )
        val list = MealPlan.shoppingList(week, foods)
        assertEquals(listOf("egg", "rice", "olive_oil"), list.map { it.food.id }) // par rayon
        val eggs = list[0]
        assertEquals(220.0, eggs.plannedGrams, 0.01)
        assertEquals(4, eggs.units)
        assertEquals(2, eggs.uses)
        val rice = list[1]
        assertEquals(160.0, rice.buyGrams, 0.01) // 400 g cuit → 160 g sec
        assertEquals("160 g sec", rice.label)
        assertEquals(null, rice.units)
        val text = MealPlan.shoppingText("Courses", list)
        assertTrue(text.contains("PROTÉINES\n☐ Œuf : 4 × œuf"))
        assertTrue(text.contains("☐ Riz blanc : 160 g sec"))
    }

    @Test
    fun catalogAndTemplatesAreConsistent() {
        assertTrue(MealPlan.catalog.size >= 100)
        assertEquals(MealPlan.catalog.size, MealPlan.catalog.map { it.id }.toSet().size)
        val foods = MealPlan.foodsById(emptyList())
        MealPlan.templates.forEach { tpl ->
            tpl.items.forEach { (_, id, _) -> assertTrue("$id inconnu", id in foods) }
            val entries = MealPlan.applyTemplate(tpl, 2500, foods, 1)
            assertEquals(tpl.items.size, entries.size)
            val kcal = MealPlan.total(entries, foods).kcal
            assertTrue("${tpl.name} : $kcal kcal", kcal in 2150.0..2850.0)
        }
    }
}
