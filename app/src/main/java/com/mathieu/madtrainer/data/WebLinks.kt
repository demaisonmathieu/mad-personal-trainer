package com.mathieu.madtrainer.data

/** Saisie de l'onglet Écran → adresse web. Un simple nom (« zerator ») est pris comme une chaîne Twitch. */
object WebLinks {
    private val CHANNEL = Regex("^[A-Za-z0-9_]{2,25}$")

    fun normalize(input: String): String? {
        val t = input.trim()
        if (t.isEmpty() || t.contains(' ')) return null
        return when {
            t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true) -> t
            CHANNEL.matches(t) -> "https://m.twitch.tv/${t.lowercase()}"
            t.contains('.') -> "https://$t"
            else -> null
        }
    }

    /** Nom court pour les raccourcis : « Twitch · zerator », « youtube.com »… */
    fun label(url: String): String {
        val noScheme = url.substringAfter("://")
        val host = noScheme.substringBefore('/').removePrefix("www.").removePrefix("m.")
        val path = noScheme.substringAfter('/', "").substringBefore('?').trim('/')
        return if (host == "twitch.tv" && path.isNotEmpty() && '/' !in path) "Twitch · $path" else host
    }

    /** Ajoute en tête des pages récentes (sans doublon, 6 au plus). */
    fun pushRecent(recent: List<String>, url: String): List<String> = (listOf(url) + recent.filter { it != url }).take(6)
}
