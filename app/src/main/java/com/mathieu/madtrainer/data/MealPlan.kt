package com.mathieu.madtrainer.data

import com.mathieu.madtrainer.coach.Sex
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** Niveau d'activité quotidienne (facteur appliqué au métabolisme de base). */
@Serializable
enum class ActivityLevel(val label: String, val detail: String, val factor: Double) {
    SEDENTARY("Sédentaire", "Peu ou pas de sport", 1.2),
    LIGHT("Léger", "1 à 3 séances par semaine", 1.375),
    MODERATE("Modéré", "3 à 5 séances par semaine", 1.55),
    ACTIVE("Actif", "6 à 7 séances par semaine", 1.725),
    VERY_ACTIVE("Très actif", "Sport intense ou métier physique", 1.9),
}

/** Objectif calorique : écart appliqué à la dépense totale (TDEE). */
@Serializable
enum class CalorieGoal(val label: String, val delta: Int) {
    LOSS("Perte", -500),
    MAINTAIN("Maintien", 0),
    GAIN("Prise de masse", 300),
    CUSTOM("Cible perso", 0),
}

@Serializable
enum class MealSlot(val label: String) {
    BREAKFAST("Petit-déjeuner"),
    LUNCH("Déjeuner"),
    DINNER("Dîner"),
    SNACK("Collations"),
}

@Serializable
enum class FoodCategory(val label: String) {
    PROTEIN("Protéines"),
    STARCH("Féculents"),
    DAIRY("Laitiers"),
    FRUIT("Fruits"),
    VEGETABLE("Légumes"),
    FAT("Matières grasses"),
    OTHER("Autres"),
}

/** Aliment, valeurs pour 100 g ; [unitGrams] > 0 permet de compter en unités (1 œuf, 1 tranche…). */
@Serializable
data class Food(
    val id: String,
    val name: String,
    val category: FoodCategory,
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val unitName: String? = null,
    val unitGrams: Double = 0.0,
    val custom: Boolean = false,
)

/** Aliment ajouté à un repas ; [quantity] en grammes, ou en unités si [inUnits]. */
@Serializable
data class FoodEntry(
    val id: Long,
    val foodId: String,
    val slot: MealSlot,
    val quantity: Double,
    val inUnits: Boolean = false,
)

@Serializable
data class Nutrients(val kcal: Double = 0.0, val protein: Double = 0.0, val carbs: Double = 0.0, val fat: Double = 0.0) {
    operator fun plus(o: Nutrients) = Nutrients(kcal + o.kcal, protein + o.protein, carbs + o.carbs, fat + o.fat)
}

/** Paramètres du calculateur ; répartition des macros en % de l'apport calorique (somme = 100). */
@Serializable
data class NutritionTargets(
    val sex: Sex = Sex.MALE,
    val age: Int = 35,
    val weightKg: Double = 75.0,
    val heightCm: Int = 175,
    val activity: ActivityLevel = ActivityLevel.MODERATE,
    val goal: CalorieGoal = CalorieGoal.MAINTAIN,
    val customKcal: Int = 2200,
    val proteinPct: Int = 30,
    val carbsPct: Int = 45,
    val fatPct: Int = 25,
)

@Serializable
data class MealDay(val epochDay: Long, val entries: List<FoodEntry> = emptyList())

/** Tout ce que l'écran Repas sauvegarde (fichier `meal_plan.json`). */
@Serializable
data class MealPlanState(
    val targets: NutritionTargets = NutritionTargets(),
    /** Vrai une fois que l'utilisateur a modifié le calculateur : on ne recopie plus le profil. */
    val targetsEdited: Boolean = false,
    val customFoods: List<Food> = emptyList(),
    val days: List<MealDay> = emptyList(),
)

object MealPlan {

    /** Métabolisme de base, Mifflin-St Jeor (« non précisé » : moyenne homme/femme). */
    fun bmr(t: NutritionTargets): Int {
        val sexTerm = when (t.sex) { Sex.MALE -> 5.0; Sex.FEMALE -> -161.0; Sex.OTHER -> -78.0 }
        return (10 * t.weightKg + 6.25 * t.heightCm - 5 * t.age + sexTerm).roundToInt()
    }

    fun tdee(t: NutritionTargets): Int = (bmr(t) * t.activity.factor).roundToInt()

    /** Apport visé : TDEE + écart de l'objectif, ou cible perso ; jamais sous le métabolisme de base pour perte/prise. */
    fun targetKcal(t: NutritionTargets): Int = when (t.goal) {
        CalorieGoal.CUSTOM -> t.customKcal.coerceIn(800, 6000)
        else -> maxOf(tdee(t) + t.goal.delta, bmr(t))
    }

    /** Grammes de macros correspondant aux pourcentages (4 kcal/g protéines et glucides, 9 kcal/g lipides). */
    fun macroGrams(t: NutritionTargets): Nutrients {
        val k = targetKcal(t).toDouble()
        return Nutrients(k, k * t.proteinPct / 100 / 4, k * t.carbsPct / 100 / 4, k * t.fatPct / 100 / 9)
    }

    /**
     * Fixe une macro à [value] % et répartit le reste sur les deux autres au prorata de leurs valeurs actuelles,
     * pour que la somme reste à 100. Ordre des valeurs : protéines, glucides, lipides.
     */
    fun rebalance(current: Triple<Int, Int, Int>, index: Int, value: Int): Triple<Int, Int, Int> {
        val v = value.coerceIn(0, 100)
        val list = current.toList()
        val others = (0..2).filter { it != index }
        val rest = 100 - v
        val a = list[others[0]]
        val b = list[others[1]]
        val newA = if (a + b == 0) rest / 2 else (rest * a.toDouble() / (a + b)).roundToInt()
        val out = IntArray(3)
        out[index] = v
        out[others[0]] = newA
        out[others[1]] = rest - newA
        return Triple(out[0], out[1], out[2])
    }

    fun grams(e: FoodEntry, f: Food): Double = if (e.inUnits && f.unitGrams > 0) e.quantity * f.unitGrams else e.quantity

    fun nutrients(e: FoodEntry, f: Food): Nutrients {
        val r = grams(e, f) / 100
        return Nutrients(f.kcal * r, f.protein * r, f.carbs * r, f.fat * r)
    }

    fun total(entries: List<FoodEntry>, foods: Map<String, Food>): Nutrients =
        entries.fold(Nutrients()) { acc, e -> foods[e.foodId]?.let { acc + nutrients(e, it) } ?: acc }

    fun bySlot(entries: List<FoodEntry>, foods: Map<String, Food>): Map<MealSlot, Nutrients> =
        MealSlot.entries.associateWith { s -> total(entries.filter { it.slot == s }, foods) }

    /** Quantité par défaut à l'ajout : 1 unité si l'aliment se compte, sinon une portion courante. */
    fun defaultEntry(id: Long, f: Food, slot: MealSlot): FoodEntry =
        if (f.unitGrams > 0) FoodEntry(id, f.id, slot, 1.0, inUnits = true)
        else FoodEntry(id, f.id, slot, if (f.category == FoodCategory.FAT) 10.0 else 100.0)

    /** Bascule grammes ↔ unités en gardant la même quantité réelle. */
    fun toggleUnits(e: FoodEntry, f: Food): FoodEntry = when {
        f.unitGrams <= 0 -> e
        e.inUnits -> e.copy(inUnits = false, quantity = (e.quantity * f.unitGrams).roundToInt().toDouble())
        else -> e.copy(inUnits = true, quantity = maxOf(0.5, (e.quantity / f.unitGrams * 2).roundToInt() / 2.0))
    }

    // --- Catalogue ---

    private fun f(id: String, name: String, c: FoodCategory, kcal: Double, p: Double, g: Double, l: Double, unit: String? = null, ug: Double = 0.0) =
        Food(id, name, c, kcal, p, g, l, unit, ug)

    /** Aliments courants, valeurs moyennes pour 100 g (tables Ciqual arrondies). */
    val catalog: List<Food> = listOf(
        f("chicken", "Blanc de poulet (cuit)", FoodCategory.PROTEIN, 150.0, 31.0, 0.0, 2.5),
        f("beef5", "Steak haché 5 %", FoodCategory.PROTEIN, 125.0, 21.0, 0.0, 5.0, "steak", 100.0),
        f("salmon", "Saumon", FoodCategory.PROTEIN, 200.0, 20.0, 0.0, 13.0, "pavé", 125.0),
        f("tuna", "Thon au naturel", FoodCategory.PROTEIN, 115.0, 26.0, 0.0, 1.0, "boîte", 112.0),
        f("egg", "Œuf", FoodCategory.PROTEIN, 140.0, 12.5, 0.5, 10.0, "œuf", 55.0),
        f("ham", "Jambon blanc", FoodCategory.PROTEIN, 115.0, 20.0, 1.0, 3.5, "tranche", 40.0),
        f("tofu", "Tofu nature", FoodCategory.PROTEIN, 125.0, 13.0, 1.5, 7.5),
        f("lentils", "Lentilles (cuites)", FoodCategory.STARCH, 115.0, 9.0, 17.0, 0.5),
        f("rice", "Riz blanc (cuit)", FoodCategory.STARCH, 130.0, 2.7, 28.0, 0.3),
        f("pasta", "Pâtes (cuites)", FoodCategory.STARCH, 150.0, 5.5, 30.0, 0.9),
        f("potato", "Pommes de terre (cuites)", FoodCategory.STARCH, 80.0, 2.0, 17.0, 0.1),
        f("oats", "Flocons d'avoine", FoodCategory.STARCH, 370.0, 13.5, 59.0, 7.0),
        f("bread", "Pain complet", FoodCategory.STARCH, 245.0, 9.0, 43.0, 3.0, "tranche", 35.0),
        f("baguette", "Baguette", FoodCategory.STARCH, 270.0, 9.0, 55.0, 1.5, "1/4 de baguette", 62.0),
        f("skyr", "Skyr nature", FoodCategory.DAIRY, 63.0, 11.0, 4.0, 0.2, "pot", 150.0),
        f("fromage_blanc", "Fromage blanc 3 %", FoodCategory.DAIRY, 85.0, 7.5, 4.0, 3.2),
        f("yogurt", "Yaourt nature", FoodCategory.DAIRY, 60.0, 4.0, 5.0, 2.0, "pot", 125.0),
        f("milk", "Lait demi-écrémé", FoodCategory.DAIRY, 46.0, 3.3, 4.8, 1.5, "verre", 250.0),
        f("emmental", "Emmental", FoodCategory.DAIRY, 380.0, 28.0, 0.0, 29.0, "portion", 30.0),
        f("banana", "Banane", FoodCategory.FRUIT, 90.0, 1.1, 20.0, 0.3, "banane", 120.0),
        f("apple", "Pomme", FoodCategory.FRUIT, 54.0, 0.3, 12.0, 0.2, "pomme", 150.0),
        f("orange", "Orange", FoodCategory.FRUIT, 47.0, 0.9, 9.5, 0.1, "orange", 170.0),
        f("berries", "Fruits rouges", FoodCategory.FRUIT, 45.0, 1.0, 8.0, 0.3),
        f("broccoli", "Brocoli (cuit)", FoodCategory.VEGETABLE, 35.0, 2.8, 4.0, 0.4),
        f("green_beans", "Haricots verts (cuits)", FoodCategory.VEGETABLE, 30.0, 1.8, 4.5, 0.2),
        f("tomato", "Tomate", FoodCategory.VEGETABLE, 19.0, 0.9, 3.0, 0.2, "tomate", 120.0),
        f("salad", "Salade verte", FoodCategory.VEGETABLE, 15.0, 1.3, 1.5, 0.2),
        f("olive_oil", "Huile d'olive", FoodCategory.FAT, 900.0, 0.0, 0.0, 100.0, "c. à soupe", 10.0),
        f("almonds", "Amandes", FoodCategory.FAT, 600.0, 21.0, 8.0, 52.0, "poignée", 25.0),
        f("peanut_butter", "Beurre de cacahuète", FoodCategory.FAT, 600.0, 25.0, 14.0, 50.0, "c. à soupe", 15.0),
        f("avocado", "Avocat", FoodCategory.FAT, 160.0, 2.0, 2.0, 15.0, "1/2 avocat", 75.0),
        f("honey", "Miel", FoodCategory.OTHER, 320.0, 0.4, 80.0, 0.0, "c. à café", 8.0),
        f("dark_chocolate", "Chocolat noir 70 %", FoodCategory.OTHER, 570.0, 8.0, 33.0, 42.0, "carré", 10.0),
        f("whey", "Protéine en poudre (whey)", FoodCategory.OTHER, 380.0, 78.0, 6.0, 5.0, "dose", 30.0),
    )

    fun foodsById(custom: List<Food>): Map<String, Food> = (catalog + custom).associateBy { it.id }

    // --- Journées types ---

    data class Template(val name: String, val detail: String, val items: List<Triple<MealSlot, String, Double>>)

    private fun t(slot: MealSlot, id: String, qty: Double) = Triple(slot, id, qty)

    /** Quantités en grammes pour environ 2 200 kcal (équilibrée) ; elles sont mises à l'échelle de l'apport visé. */
    val templates: List<Template> = listOf(
        Template(
            "Équilibrée", "Environ 30 % protéines, 45 % glucides, 25 % lipides",
            listOf(
                t(MealSlot.BREAKFAST, "oats", 60.0), t(MealSlot.BREAKFAST, "skyr", 150.0), t(MealSlot.BREAKFAST, "banana", 120.0),
                t(MealSlot.LUNCH, "chicken", 130.0), t(MealSlot.LUNCH, "rice", 200.0), t(MealSlot.LUNCH, "broccoli", 150.0),
                t(MealSlot.LUNCH, "olive_oil", 10.0),
                t(MealSlot.SNACK, "apple", 150.0), t(MealSlot.SNACK, "almonds", 25.0),
                t(MealSlot.DINNER, "salmon", 125.0), t(MealSlot.DINNER, "potato", 250.0), t(MealSlot.DINNER, "green_beans", 150.0),
                t(MealSlot.DINNER, "yogurt", 125.0),
            ),
        ),
        Template(
            "Prise de masse", "Plus de féculents et de collations",
            listOf(
                t(MealSlot.BREAKFAST, "oats", 90.0), t(MealSlot.BREAKFAST, "milk", 250.0), t(MealSlot.BREAKFAST, "peanut_butter", 15.0),
                t(MealSlot.BREAKFAST, "banana", 120.0),
                t(MealSlot.LUNCH, "beef5", 150.0), t(MealSlot.LUNCH, "pasta", 300.0), t(MealSlot.LUNCH, "olive_oil", 10.0),
                t(MealSlot.LUNCH, "tomato", 120.0),
                t(MealSlot.SNACK, "whey", 30.0), t(MealSlot.SNACK, "bread", 70.0), t(MealSlot.SNACK, "honey", 16.0),
                t(MealSlot.DINNER, "chicken", 150.0), t(MealSlot.DINNER, "rice", 250.0), t(MealSlot.DINNER, "avocado", 75.0),
                t(MealSlot.DINNER, "fromage_blanc", 150.0),
            ),
        ),
        Template(
            "Sèche", "Protéines élevées, peu de matières grasses",
            listOf(
                t(MealSlot.BREAKFAST, "egg", 110.0), t(MealSlot.BREAKFAST, "bread", 35.0), t(MealSlot.BREAKFAST, "skyr", 150.0),
                t(MealSlot.LUNCH, "chicken", 150.0), t(MealSlot.LUNCH, "lentils", 150.0), t(MealSlot.LUNCH, "salad", 100.0),
                t(MealSlot.LUNCH, "olive_oil", 5.0),
                t(MealSlot.SNACK, "fromage_blanc", 200.0), t(MealSlot.SNACK, "berries", 100.0),
                t(MealSlot.DINNER, "tuna", 112.0), t(MealSlot.DINNER, "potato", 200.0), t(MealSlot.DINNER, "broccoli", 200.0),
            ),
        ),
    )

    /**
     * Entrées d'une journée type, quantités mises à l'échelle pour approcher [targetKcal] (facteur 0,6 à 1,8,
     * arrondi à 5 g ; les aliments comptés à l'unité sont repassés en unités par demi-unité).
     */
    fun applyTemplate(tpl: Template, targetKcal: Int, foods: Map<String, Food>, firstId: Long): List<FoodEntry> {
        val raw = tpl.items.mapIndexedNotNull { i, (slot, id, g) ->
            foods[id]?.let { FoodEntry(firstId + i, id, slot, g) }
        }
        val base = total(raw, foods).kcal
        val k = if (base > 0) (targetKcal / base).coerceIn(0.6, 1.8) else 1.0
        return raw.map { e ->
            val food = foods.getValue(e.foodId)
            val grams = maxOf(5.0, (e.quantity * k / 5).roundToInt() * 5.0)
            if (food.unitGrams > 0 && food.category != FoodCategory.FAT) {
                FoodEntry(e.id, e.foodId, e.slot, maxOf(0.5, (grams / food.unitGrams * 2).roundToInt() / 2.0), inUnits = true)
            } else e.copy(quantity = grams)
        }
    }
}
