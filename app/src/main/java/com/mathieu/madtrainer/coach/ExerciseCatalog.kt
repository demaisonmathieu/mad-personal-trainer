package com.mathieu.madtrainer.coach

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.text.Normalizer

/** Une entrée de la banque d'exercices libre « free-exercise-db » (domaine public, photos début / fin de mouvement). */
@Serializable
data class CatalogExercise(
    val id: String,
    /** Nom (anglais) */
    val n: String,
    /** Muscle principal */
    val m: String = "",
    /** Matériel */
    val e: String = "none",
    /** Catégorie : strength, stretching… */
    val c: String = "",
    /** Chemins des images si différents de id/0.jpg, id/1.jpg */
    val i: List<String>? = null,
) {
    val images: List<String> get() = (i ?: listOf("$id/0.jpg", "$id/1.jpg")).map { BASE_URL + it }

    companion object {
        const val BASE_URL = "https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/"
    }
}

/** Banque d'images d'exercices + correspondance avec les exercices du programme de renfo. */
class ExerciseCatalog(val all: List<CatalogExercise>) {

    private val byKey = all.associateBy { key(it.id) }

    /** Retrouve l'illustration d'un exercice : identifiant choisi par l'IA / l'utilisateur, sinon par le nom français. */
    fun find(ex: StrengthExercise): CatalogExercise? {
        val id = ex.image_id
        if (id != null && id.isBlank()) return null // « aucune illustration » choisi explicitement
        return id?.let { byId(it) } ?: guessFromName(ex.name)
    }

    fun byId(id: String): CatalogExercise? = byKey[key(id)]

    /** Recherche par mots (nom anglais, identifiant, muscle, ou mot-clé français connu). */
    fun search(query: String, limit: Int = 40): List<CatalogExercise> {
        val q = normalize(query)
        if (q.isBlank()) return all.filter { it.c == "stretching" || it.e in BODY_EQUIPMENT }.take(limit)
        val words = (q.split(' ').filter { it.isNotBlank() } +
            FR_KEYWORDS.filter { (fr, _) -> " $q".contains(" $fr") }.flatMap { (_, en) -> en.split('_').map { it.lowercase() } }).distinct()
        return all
            .map { ex ->
                val hay = normalize(ex.n + " " + ex.id.replace('_', ' ') + " " + ex.m)
                ex to words.count { hay.contains(it) }
            }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<CatalogExercise, Int>> { it.second }.thenBy { if (it.first.e in BODY_EQUIPMENT) 0 else 1 }.thenBy { it.first.n.length })
            .take(limit)
            .map { it.first }
    }

    /** Correspondance de secours à partir du nom français (exercices les plus courants en renfo / prévention). */
    fun guessFromName(name: String): CatalogExercise? {
        val n = normalize(name)
        val padded = " $n"
        FR_KEYWORDS.forEach { (fr, id) -> if (padded.contains(" $fr")) byId(id)?.let { return it } }
        return null
    }

    /** Identifiants proposés à l'IA, filtrés selon le matériel disponible pour garder un prompt court. */
    fun idsForPrompt(equipment: List<String>): List<String> {
        val eq = equipment.joinToString(" ").lowercase()
        val allowed = BODY_EQUIPMENT.toMutableSet()
        if ("élastique" in eq || "elastique" in eq) allowed += "bands"
        if ("swiss" in eq || "ballon" in eq) allowed += "exercise ball"
        if ("rouleau" in eq) allowed += "foam roll"
        if ("haltère" in eq || "haltere" in eq) allowed += "dumbbell"
        if ("kettlebell" in eq) allowed += "kettlebells"
        return all.filter { it.e in allowed && (it.c == "strength" || it.c == "stretching") }.map { it.id }
    }

    companion object {
        val BODY_EQUIPMENT = setOf("body only", "none", "other")

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): ExerciseCatalog =
            ExerciseCatalog(json.decodeFromString(ListSerializer(CatalogExercise.serializer()), text))

        fun key(id: String) = id.lowercase().replace('-', '_').replace(' ', '_')

        fun normalize(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
                .replace("\\p{Mn}+".toRegex(), "")
                .map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
                .split(' ').filter { it.isNotBlank() }.joinToString(" ")

        /** Lien de recherche YouTube pour voir une démonstration de l'exercice. */
        fun youtubeSearchUrl(name: String): String =
            "https://www.youtube.com/results?search_query=" + URLEncoder.encode("$name exercice démonstration", "UTF-8")

        /** Mots-clés français (sans accents, minuscules) → identifiant de la banque. Le plus spécifique d'abord. */
        val FR_KEYWORDS: List<Pair<String, String>> = listOf(
            "pont fessier unilateral" to "Single_Leg_Glute_Bridge",
            "pont fessier une jambe" to "Single_Leg_Glute_Bridge",
            "pont fessier" to "Pelvic_Tilt_Into_Bridge",
            "pont" to "Pelvic_Tilt_Into_Bridge",
            "bascule du bassin" to "Pelvic_Tilt_Into_Bridge",
            "retroversion" to "Pelvic_Tilt_Into_Bridge",
            "gainage lateral" to "Side_Bridge",
            "planche laterale" to "Side_Bridge",
            "gainage" to "Plank",
            "planche" to "Plank",
            "dead bug" to "Dead_Bug",
            "superman" to "Superman",
            "chat vache" to "Cat_Stretch",
            "chat" to "Cat_Stretch",
            "posture de l enfant" to "Childs_Pose",
            "enfant" to "Childs_Pose",
            "genou poitrine" to "One_Knee_To_Chest",
            "genoux poitrine" to "One_Knee_To_Chest",
            "ischio" to "Hamstring_Stretch",
            "pyramidal" to "Seated_Glute",
            "piriforme" to "Seated_Glute",
            "fessier etirement" to "Seated_Glute",
            "psoas" to "Kneeling_Hip_Flexor",
            "flechisseur de hanche" to "Kneeling_Hip_Flexor",
            "fente" to "Bodyweight_Walking_Lunge",
            "squat" to "Bodyweight_Squat",
            "chaise" to "Bodyweight_Squat",
            "pompe" to "Pushups",
            "etirement mollet" to "Calf_Stretch_Hands_Against_Wall",
            "mollet" to "Calf_Raise_On_A_Dumbbell",
            "quadriceps" to "Quad_Stretch",
            "adducteur" to "Adductor",
            "abducteur" to "Thigh_Abductor",
            "mountain climber" to "Mountain_Climbers",
            "crunch" to "Crunches",
            "russian twist" to "Russian_Twist",
            "rotation externe" to "External_Rotation_with_Band",
            "rotation du buste" to "Torso_Rotation",
            "nuque" to "Side_Neck_Stretch",
            "elevation laterale de jambe" to "Side_Leg_Raises",
            "releve de jambes" to "Flat_Bench_Lying_Leg_Raise",
            "extension lombaire" to "Hyperextensions_With_No_Hyperextension_Bench",
            "dos" to "Chair_Lower_Back_Stretch",
        )
    }
}
