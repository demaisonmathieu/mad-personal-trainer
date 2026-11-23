package com.mathieu.madtrainer.data

/** Vidéo reconnue dans une adresse : affichée seule, dans le lecteur intégrable officiel (sans chat ni page autour). */
sealed interface VideoEmbed {
    data class TwitchChannel(val channel: String) : VideoEmbed
    data class TwitchVod(val id: String) : VideoEmbed
    data class YouTube(val id: String) : VideoEmbed
}

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

    /** Domaine fictif de la page qui héberge le lecteur (Twitch exige un « parent » déclaré). */
    const val EMBED_HOST = "madtrainer.app"

    private val YOUTUBE_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val TWITCH_RESERVED = setOf(
        "directory", "videos", "settings", "search", "downloads", "p", "subscriptions", "inventory", "wallet", "drops", "login", "signup", "turbo",
    )

    /** Vidéo Twitch (live d'une chaîne, rediffusion) ou YouTube contenue dans [url], sinon null (page web affichée telle quelle). */
    fun embedOf(url: String): VideoEmbed? {
        val noScheme = url.trim().substringAfter("://")
        val host = noScheme.substringBefore('/').substringBefore('?').lowercase().removePrefix("www.").removePrefix("m.")
        val rest = noScheme.substringAfter('/', "")
        val path = rest.substringBefore('?').substringBefore('#').trim('/')
        val query = rest.substringAfter('?', "").substringBefore('#')
        val seg = path.split('/').filter { it.isNotEmpty() }
        return when (host) {
            "twitch.tv" -> when {
                seg.size == 1 && CHANNEL.matches(seg[0]) && seg[0].lowercase() !in TWITCH_RESERVED -> VideoEmbed.TwitchChannel(seg[0].lowercase())
                seg.size >= 2 && seg[0] == "videos" && seg[1].all { it.isDigit() } -> VideoEmbed.TwitchVod(seg[1])
                else -> null
            }
            "youtube.com", "music.youtube.com" -> when {
                seg.firstOrNull() == "watch" -> query.split('&').firstOrNull { it.startsWith("v=") }?.substringAfter("v=")
                seg.size >= 2 && seg[0] in setOf("live", "shorts", "embed") -> seg[1]
                else -> null
            }?.takeIf { YOUTUBE_ID.matches(it) }?.let { VideoEmbed.YouTube(it) }
            "youtu.be" -> seg.firstOrNull()?.takeIf { YOUTUBE_ID.matches(it) }?.let { VideoEmbed.YouTube(it) }
            else -> null
        }
    }

    /**
     * Page HTML du lecteur seul, chargée avec l'adresse de base https://[EMBED_HOST]/.
     * Elle expose madPlay() / madPause() pour mettre la vidéo en pause quand elle n'est plus affichée.
     * Largeur de page fixée à 640 px (mise à l'échelle) : le lecteur Twitch refuse de jouer sous 400 × 300.
     */
    fun playerHtml(e: VideoEmbed): String {
        val head = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=640">
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p,#p iframe{position:absolute;inset:0;width:100%;height:100%;border:0}</style>"""
        val body = when (e) {
            is VideoEmbed.TwitchChannel, is VideoEmbed.TwitchVod -> {
                val target = if (e is VideoEmbed.TwitchChannel) "channel:\"${e.channel}\"" else "video:\"v${(e as VideoEmbed.TwitchVod).id}\""
                """<script src="https://player.twitch.tv/js/embed/v1.js"></script></head><body><div id="p"></div><script>
var pl=new Twitch.Player("p",{$target,width:"100%",height:"100%",parent:["$EMBED_HOST"],autoplay:true,muted:false});
function madPlay(){pl.play()}function madPause(){pl.pause()}
</script></body></html>"""
            }
            is VideoEmbed.YouTube -> """</head><body><div id="p"></div><script>
var pl=null;function onYouTubeIframeAPIReady(){pl=new YT.Player("p",{width:"100%",height:"100%",videoId:"${e.id}",
playerVars:{autoplay:1,playsinline:1,rel:0,origin:"https://$EMBED_HOST"},events:{onReady:function(ev){ev.target.playVideo()}}})}
function madPlay(){if(pl&&pl.playVideo)pl.playVideo()}function madPause(){if(pl&&pl.pauseVideo)pl.pauseVideo()}
</script><script src="https://www.youtube.com/iframe_api"></script></body></html>"""
        }
        return head + body
    }
}
