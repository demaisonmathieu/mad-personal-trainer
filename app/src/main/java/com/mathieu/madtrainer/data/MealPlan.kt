package com.mathieu.madtrainer.data

import com.mathieu.madtrainer.coach.Sex
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.ceil
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
    /** Liste de courses : aliments cochés, par lundi de la semaine (epochDay). */
    val shoppingChecked: Map<Long, List<String>> = emptyMap(),
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
        // Viandes, poissons, œufs
        f("turkey", "Escalope de dinde (cuite)", FoodCategory.PROTEIN, 135.0, 29.0, 0.0, 2.0, "escalope", 120.0),
        f("chicken_thigh", "Cuisse de poulet (cuite, sans peau)", FoodCategory.PROTEIN, 180.0, 26.0, 0.0, 8.5),
        f("beef15", "Steak haché 15 %", FoodCategory.PROTEIN, 215.0, 18.0, 0.0, 15.0, "steak", 100.0),
        f("rumsteak", "Rumsteck (grillé)", FoodCategory.PROTEIN, 160.0, 29.0, 0.0, 5.0),
        f("pork_loin", "Filet mignon de porc (cuit)", FoodCategory.PROTEIN, 145.0, 27.0, 0.0, 4.0),
        f("sausage", "Saucisse de Toulouse (cuite)", FoodCategory.PROTEIN, 290.0, 17.0, 1.0, 24.0, "saucisse", 100.0),
        f("bacon", "Lardons", FoodCategory.PROTEIN, 270.0, 15.0, 0.5, 23.0),
        f("chicken_slice", "Blanc de poulet en tranches", FoodCategory.PROTEIN, 105.0, 21.0, 1.0, 2.0, "tranche", 30.0),
        f("cod", "Cabillaud (cuit)", FoodCategory.PROTEIN, 95.0, 21.0, 0.0, 1.0, "filet", 130.0),
        f("white_fish", "Colin / lieu (cuit)", FoodCategory.PROTEIN, 100.0, 21.0, 0.0, 1.5, "filet", 120.0),
        f("sardines", "Sardines à l'huile (égouttées)", FoodCategory.PROTEIN, 210.0, 24.0, 0.0, 13.0, "boîte", 90.0),
        f("mackerel", "Maquereau", FoodCategory.PROTEIN, 230.0, 19.0, 0.0, 17.0),
        f("shrimp", "Crevettes (cuites)", FoodCategory.PROTEIN, 95.0, 21.0, 0.5, 1.2),
        f("surimi", "Surimi", FoodCategory.PROTEIN, 105.0, 8.0, 15.0, 1.0, "bâtonnet", 17.0),
        f("egg_white", "Blanc d'œuf", FoodCategory.PROTEIN, 48.0, 10.5, 0.7, 0.2, "blanc", 33.0),
        f("chickpeas", "Pois chiches (cuits)", FoodCategory.STARCH, 140.0, 8.0, 19.0, 2.5),
        f("red_beans", "Haricots rouges (cuits)", FoodCategory.STARCH, 115.0, 8.5, 15.0, 0.5),
        // Féculents, céréales
        f("brown_rice", "Riz complet (cuit)", FoodCategory.STARCH, 125.0, 2.8, 25.0, 1.0),
        f("wholewheat_pasta", "Pâtes complètes (cuites)", FoodCategory.STARCH, 140.0, 5.5, 26.0, 1.0),
        f("quinoa", "Quinoa (cuit)", FoodCategory.STARCH, 125.0, 4.5, 21.0, 2.0),
        f("semolina", "Semoule (cuite)", FoodCategory.STARCH, 115.0, 4.0, 23.0, 0.5),
        f("sweet_potato", "Patate douce (cuite)", FoodCategory.STARCH, 85.0, 1.5, 18.0, 0.2),
        f("fries", "Frites (four)", FoodCategory.STARCH, 220.0, 3.5, 32.0, 8.0),
        f("sandwich_bread", "Pain de mie complet", FoodCategory.STARCH, 255.0, 9.5, 43.0, 4.0, "tranche", 28.0),
        f("tortilla", "Galette de blé (wrap)", FoodCategory.STARCH, 300.0, 8.5, 50.0, 7.0, "galette", 60.0),
        f("rice_cake", "Galette de riz soufflé", FoodCategory.STARCH, 385.0, 8.0, 80.0, 3.0, "galette", 8.0),
        f("muesli", "Muesli sans sucre ajouté", FoodCategory.STARCH, 370.0, 10.0, 60.0, 7.0),
        f("cornflakes", "Céréales type corn flakes", FoodCategory.STARCH, 375.0, 7.0, 84.0, 1.0),
        f("croissant", "Croissant", FoodCategory.STARCH, 420.0, 8.0, 45.0, 22.0, "croissant", 45.0),
        f("pain_choc", "Pain au chocolat", FoodCategory.STARCH, 420.0, 7.5, 47.0, 22.0, "pain au chocolat", 65.0),
        // Laitiers
        f("greek_yogurt", "Yaourt à la grecque", FoodCategory.DAIRY, 120.0, 5.5, 4.0, 9.0, "pot", 150.0),
        f("fromage_blanc0", "Fromage blanc 0 %", FoodCategory.DAIRY, 48.0, 7.5, 4.0, 0.1),
        f("cottage", "Cottage cheese", FoodCategory.DAIRY, 95.0, 11.0, 3.0, 4.5),
        f("skim_milk", "Lait écrémé", FoodCategory.DAIRY, 34.0, 3.4, 4.8, 0.1, "verre", 250.0),
        f("soy_drink", "Boisson au soja", FoodCategory.DAIRY, 40.0, 3.5, 2.0, 2.0, "verre", 250.0),
        f("mozzarella", "Mozzarella", FoodCategory.DAIRY, 250.0, 18.0, 1.0, 19.0, "boule", 125.0),
        f("camembert", "Camembert", FoodCategory.DAIRY, 270.0, 20.0, 0.5, 21.0, "portion", 30.0),
        f("comte", "Comté", FoodCategory.DAIRY, 410.0, 28.0, 0.0, 33.0, "portion", 30.0),
        f("feta", "Feta", FoodCategory.DAIRY, 265.0, 15.0, 1.0, 22.0),
        f("parmesan", "Parmesan", FoodCategory.DAIRY, 400.0, 33.0, 0.0, 29.0, "c. à soupe râpé", 7.0),
        // Fruits
        f("kiwi", "Kiwi", FoodCategory.FRUIT, 60.0, 1.0, 11.0, 0.5, "kiwi", 75.0),
        f("pear", "Poire", FoodCategory.FRUIT, 55.0, 0.4, 12.0, 0.2, "poire", 170.0),
        f("grapes", "Raisin", FoodCategory.FRUIT, 75.0, 0.6, 17.0, 0.2),
        f("clementine", "Clémentine", FoodCategory.FRUIT, 45.0, 0.8, 9.5, 0.2, "clémentine", 70.0),
        f("strawberries", "Fraises", FoodCategory.FRUIT, 35.0, 0.7, 6.0, 0.3),
        f("mango", "Mangue", FoodCategory.FRUIT, 65.0, 0.7, 14.0, 0.3),
        f("pineapple", "Ananas", FoodCategory.FRUIT, 50.0, 0.5, 11.0, 0.2),
        f("compote", "Compote sans sucre ajouté", FoodCategory.FRUIT, 60.0, 0.3, 13.0, 0.2, "gourde", 90.0),
        f("dates", "Dattes sèches", FoodCategory.FRUIT, 285.0, 2.5, 65.0, 0.4, "datte", 8.0),
        f("orange_juice", "Jus d'orange", FoodCategory.FRUIT, 45.0, 0.7, 9.5, 0.2, "verre", 200.0),
        // Légumes
        f("carrot", "Carottes", FoodCategory.VEGETABLE, 35.0, 0.8, 6.5, 0.3, "carotte", 100.0),
        f("zucchini", "Courgettes (cuites)", FoodCategory.VEGETABLE, 18.0, 1.2, 2.0, 0.3),
        f("spinach", "Épinards (cuits)", FoodCategory.VEGETABLE, 25.0, 3.0, 1.0, 0.5),
        f("cauliflower", "Chou-fleur (cuit)", FoodCategory.VEGETABLE, 25.0, 2.0, 2.5, 0.3),
        f("mushrooms", "Champignons de Paris", FoodCategory.VEGETABLE, 25.0, 3.0, 1.0, 0.3),
        f("pepper", "Poivron", FoodCategory.VEGETABLE, 30.0, 1.0, 5.0, 0.3, "poivron", 150.0),
        f("cucumber", "Concombre", FoodCategory.VEGETABLE, 13.0, 0.6, 2.0, 0.1),
        f("peas", "Petits pois (cuits)", FoodCategory.VEGETABLE, 70.0, 5.0, 9.0, 0.5),
        f("corn", "Maïs doux", FoodCategory.VEGETABLE, 100.0, 3.0, 17.0, 1.5),
        f("ratatouille", "Ratatouille", FoodCategory.VEGETABLE, 60.0, 1.2, 5.0, 3.5),
        f("veg_soup", "Soupe de légumes", FoodCategory.VEGETABLE, 35.0, 1.0, 5.0, 1.0, "bol", 300.0),
        // Matières grasses, oléagineux
        f("butter", "Beurre", FoodCategory.FAT, 745.0, 0.7, 0.6, 82.0, "noisette", 10.0),
        f("rapeseed_oil", "Huile de colza", FoodCategory.FAT, 900.0, 0.0, 0.0, 100.0, "c. à soupe", 10.0),
        f("walnuts", "Noix", FoodCategory.FAT, 700.0, 15.0, 7.0, 65.0, "poignée", 25.0),
        f("cashews", "Noix de cajou", FoodCategory.FAT, 590.0, 19.0, 26.0, 46.0, "poignée", 25.0),
        f("chia", "Graines de chia", FoodCategory.FAT, 490.0, 17.0, 8.0, 31.0, "c. à soupe", 12.0),
        f("vinaigrette", "Vinaigrette", FoodCategory.FAT, 450.0, 0.5, 4.0, 48.0, "c. à soupe", 15.0),
        // Autres
        f("jam", "Confiture", FoodCategory.OTHER, 250.0, 0.5, 60.0, 0.1, "c. à café", 15.0),
        f("hazelnut_spread", "Pâte à tartiner chocolat-noisette", FoodCategory.OTHER, 540.0, 6.0, 57.0, 31.0, "c. à café", 15.0),
        f("protein_bar", "Barre protéinée", FoodCategory.OTHER, 360.0, 33.0, 35.0, 11.0, "barre", 60.0),
        f("cereal_bar", "Barre de céréales", FoodCategory.OTHER, 410.0, 6.0, 68.0, 12.0, "barre", 25.0),
        f("fruit_paste", "Pâte de fruits", FoodCategory.OTHER, 300.0, 0.3, 75.0, 0.1, "pâte", 20.0),
        f("energy_gel", "Gel énergétique", FoodCategory.OTHER, 270.0, 0.0, 67.0, 0.0, "gel", 32.0),
        f("sports_drink", "Boisson d'effort", FoodCategory.OTHER, 28.0, 0.0, 7.0, 0.0, "bidon", 500.0),
        f("pizza", "Pizza margherita", FoodCategory.OTHER, 240.0, 10.0, 30.0, 8.5, "part", 125.0),
        f("hummus", "Houmous", FoodCategory.OTHER, 300.0, 7.5, 13.0, 24.0, "c. à soupe", 20.0),
        f("sugar", "Sucre", FoodCategory.OTHER, 400.0, 0.0, 100.0, 0.0, "morceau", 5.0),
        f("wine", "Vin rouge", FoodCategory.OTHER, 85.0, 0.1, 2.6, 0.0, "verre", 125.0),
        f("beer", "Bière blonde", FoodCategory.OTHER, 42.0, 0.4, 3.5, 0.0, "demi", 250.0),
    )

    fun foodsById(custom: List<Food>): Map<String, Food> = (catalog + custom).associateBy { it.id }

    // --- Semaine et courses ---

    /** Lundi de la semaine contenant [day] (epochDay). */
    fun weekStart(day: Long): Long = LocalDate.ofEpochDay(day).with(DayOfWeek.MONDAY).toEpochDay()

    /**
     * Conversion du poids saisi (cuit) en poids à acheter : féculents et légumineuses en poids sec,
     * viandes et poissons en poids cru. Absent = même poids.
     */
    val purchase: Map<String, Pair<Double, String>> = mapOf(
        "rice" to (0.4 to "sec"), "brown_rice" to (0.4 to "sec"), "pasta" to (0.45 to "sèches"),
        "wholewheat_pasta" to (0.45 to "sèches"), "quinoa" to (0.37 to "sec"), "semolina" to (0.4 to "sèche"),
        "lentils" to (0.4 to "sèches"), "chickpeas" to (0.45 to "secs"), "red_beans" to (0.45 to "secs"),
        "chicken" to (1.3 to "cru"), "chicken_thigh" to (1.3 to "crue"), "turkey" to (1.3 to "crue"),
        "rumsteak" to (1.25 to "cru"), "pork_loin" to (1.3 to "cru"), "cod" to (1.25 to "cru"),
        "white_fish" to (1.25 to "cru"), "shrimp" to (1.2 to "crues"), "sausage" to (1.2 to "crue"),
        "broccoli" to (1.1 to "cru"), "green_beans" to (1.1 to "crus"), "zucchini" to (1.15 to "crues"),
        "spinach" to (1.3 to "crus"), "cauliflower" to (1.1 to "cru"),
    )

    data class ShoppingItem(
        val food: Food,
        /** Poids total prévu dans les repas (tel que saisi). */
        val plannedGrams: Double,
        /** Poids à acheter (sec / cru si conversion). */
        val buyGrams: Double,
        /** « sec », « cru »… si le poids à acheter est converti. */
        val buyState: String?,
        /** Nombre d'unités à acheter (arrondi au-dessus) pour les aliments qui se comptent. */
        val units: Int?,
        /** Nombre de repas où il apparaît. */
        val uses: Int,
    ) {
        /** Nom en rayon : sans « (cuit) » quand le poids à acheter est converti. */
        val shopName: String get() =
            if (buyState != null) food.name.replace(Regex(" \\((cuit|cuite|cuits|cuites)\\)"), "") else food.name

        val label: String get() {
            val g = if (buyGrams >= 1000) "${BodyStats.fmt(buyGrams / 1000)} kg" else "${(ceil(buyGrams / 10) * 10).toInt()} g"
            return when {
                units != null -> "$units × ${food.unitName} (≈ $g)"
                buyState != null -> "$g $buyState"
                else -> g
            }
        }
    }

    /** Liste de courses : quantités cumulées par aliment, triées par rayon puis par nom. */
    fun shoppingList(entries: List<FoodEntry>, foods: Map<String, Food>): List<ShoppingItem> =
        entries.groupBy { it.foodId }.mapNotNull { (id, list) ->
            val f = foods[id] ?: return@mapNotNull null
            val grams = list.sumOf { grams(it, f) }
            if (grams <= 0) return@mapNotNull null
            val conv = purchase[id]
            ShoppingItem(
                food = f,
                plannedGrams = grams,
                buyGrams = grams * (conv?.first ?: 1.0),
                buyState = conv?.second,
                units = if (f.unitGrams > 0 && conv == null) ceil(grams / f.unitGrams - 0.01).toInt().coerceAtLeast(1) else null,
                uses = list.size,
            )
        }.sortedWith(compareBy<ShoppingItem>({ it.food.category.ordinal }, { it.food.name.lowercase() }))

    /** Texte de la liste pour l'envoyer (SMS, notes…) : un rayon par bloc, cases à cocher. */
    fun shoppingText(title: String, items: List<ShoppingItem>): String = buildString {
        append(title)
        items.groupBy { it.food.category }.forEach { (cat, list) ->
            append("\n\n").append(cat.label.uppercase())
            list.forEach { append("\n☐ ").append(it.shopName).append(" : ").append(it.label) }
        }
    }

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
     * Familles d'aliments interchangeables pour varier les journées types. [byProtein] : la quantité de remplacement
     * garde les mêmes protéines (plats principaux), sinon les mêmes calories.
     */
    private class Pool(val slots: Set<MealSlot>, val ids: List<String>, val byProtein: Boolean = false)

    private val mains = setOf(MealSlot.LUNCH, MealSlot.DINNER)
    private val morningAndSnack = setOf(MealSlot.BREAKFAST, MealSlot.SNACK)

    private val pools = listOf(
        Pool(
            mains,
            listOf("chicken", "white_fish", "beef5", "salmon", "turkey", "tuna", "pork_loin", "cod", "chicken_thigh", "shrimp", "rumsteak"),
            byProtein = true,
        ),
        Pool(mains, listOf("rice", "potato", "pasta", "quinoa", "lentils", "semolina", "sweet_potato", "wholewheat_pasta", "chickpeas", "brown_rice")),
        Pool(mains, listOf("broccoli", "carrot", "green_beans", "ratatouille", "zucchini", "spinach", "cauliflower", "pepper", "mushrooms")),
        Pool(mains, listOf("salad", "tomato", "cucumber")),
        Pool(mains, listOf("yogurt", "fromage_blanc", "skyr", "greek_yogurt", "compote")),
        Pool(MealSlot.entries.toSet(), listOf("apple", "banana", "kiwi", "orange", "pear", "clementine", "berries", "strawberries", "grapes", "mango")),
        Pool(morningAndSnack, listOf("skyr", "fromage_blanc", "greek_yogurt", "cottage", "fromage_blanc0")),
        Pool(morningAndSnack, listOf("oats", "muesli")),
        Pool(morningAndSnack, listOf("bread", "sandwich_bread", "baguette")),
        Pool(morningAndSnack, listOf("almonds", "walnuts", "cashews")),
    )

    /**
     * Journée type déclinée pour la variante [variant] (le jour, en pratique) : chaque aliment d'une famille est
     * remplacé par le suivant de [variant] crans. Deux aliments différents d'une même famille dans un même jour
     * restent différents (même décalage). Variante 0 = journée type d'origine.
     */
    fun vary(tpl: Template, variant: Long, foods: Map<String, Food>): List<Triple<MealSlot, String, Double>> =
        tpl.items.map { item ->
            val (slot, id, grams) = item
            val pool = pools.firstOrNull { slot in it.slots && id in it.ids } ?: return@map item
            val idx = pool.ids.indexOf(id)
            val newId = pool.ids[Math.floorMod(idx + variant, pool.ids.size.toLong()).toInt()]
            val old = foods[id]
            val new = foods[newId]
            if (newId == id || old == null || new == null) return@map item
            // Portion recalculée, mais jamais plus de 30 % au-dessus ou 25 % en dessous de celle d'origine
            val ratio = (if (pool.byProtein) old.protein / new.protein else old.kcal / new.kcal).coerceIn(0.75, 1.3)
            Triple(slot, newId, grams * ratio)
        }

    /**
     * Entrées d'une journée type, quantités mises à l'échelle pour approcher [targetKcal] (facteur 0,6 à 1,8,
     * arrondi à 5 g ; les aliments comptés à l'unité sont repassés en unités par demi-unité).
     */
    fun applyTemplate(tpl: Template, targetKcal: Int, foods: Map<String, Food>, firstId: Long, variant: Long = 0): List<FoodEntry> {
        val raw = vary(tpl, variant, foods).mapIndexedNotNull { i, (slot, id, g) ->
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
