package com.mathieu.madtrainer.coach

/**
 * Réparation d'un JSON tronqué (réponse d'IA coupée par sa limite de longueur ou copie incomplète).
 * On coupe après le dernier élément COMPLET d'un tableau de premier niveau (ex. la dernière séance
 * entière de "workouts") puis on referme proprement les tableaux / objets encore ouverts.
 */
object JsonRepair {

    /** Renvoie le JSON réparé, ou null s'il n'y a rien de récupérable. */
    fun repairTruncated(raw: String): String? {
        val s = raw.trim()
        if (!s.startsWith("{")) return null
        val stack = ArrayDeque<Char>()
        var inString = false
        var escape = false
        var bestCut = -1
        var bestStack: List<Char> = emptyList()
        for (i in s.indices) {
            val c = s[i]
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> stack.addLast(c)
                '}', ']' -> {
                    if (stack.isEmpty()) return null
                    stack.removeLast()
                    // Élément complet d'un tableau de 1er niveau : pile = [ '{' (racine), '[' ]
                    if (c == '}' && stack.size == 2 && stack[0] == '{' && stack[1] == '[') {
                        bestCut = i + 1
                        bestStack = stack.toList()
                    }
                    if (stack.isEmpty()) return s.substring(0, i + 1) // JSON déjà complet
                }
            }
        }
        if (bestCut < 0) return null
        val closing = bestStack.reversed().joinToString("") { if (it == '{') "}" else "]" }
        return s.substring(0, bestCut) + closing
    }
}
