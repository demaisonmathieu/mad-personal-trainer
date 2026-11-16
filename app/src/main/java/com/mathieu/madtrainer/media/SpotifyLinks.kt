package com.mathieu.madtrainer.media

/** Liens Spotify (calcul pur, testable en JVM). */
object SpotifyLinks {
    const val PACKAGE = "com.spotify.music"
    private val KINDS = listOf("playlist", "album", "artist", "track", "show", "episode")

    /**
     * Normalise un lien collé par l'utilisateur en URI Spotify :
     * « https://open.spotify.com/intl-fr/playlist/37i9…?si=… » → « spotify:playlist:37i9… ».
     * @return null si ce n'est pas un lien Spotify reconnu
     */
    fun normalize(input: String): String? {
        val s = input.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("spotify:")) {
            val parts = s.split(':')
            return if (parts.size >= 3 && parts[1] in KINDS && parts[2].isNotBlank()) "spotify:${parts[1]}:${parts[2]}" else null
        }
        val noScheme = s.substringAfter("://", s)
        if (!noScheme.startsWith("open.spotify.com/")) return null
        val segments = noScheme.substringAfter('/').substringBefore('?').substringBefore('#').split('/').filter { it.isNotBlank() }
        val i = segments.indexOfFirst { it in KINDS }
        if (i < 0 || i + 1 >= segments.size) return null
        return "spotify:${segments[i]}:${segments[i + 1]}"
    }

    /** Lien web équivalent (repli si l'appli Spotify n'est pas installée). */
    fun webUrl(uri: String): String? {
        val parts = uri.split(':')
        return if (parts.size >= 3) "https://open.spotify.com/${parts[1]}/${parts[2]}" else null
    }
}
