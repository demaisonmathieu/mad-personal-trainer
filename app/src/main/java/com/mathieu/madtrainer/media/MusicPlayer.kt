package com.mathieu.madtrainer.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Service vide exigé par Android pour lire les sessions média des autres applis
 * (Spotify, Deezer…) : il n'est actif que si l'utilisateur accorde l'« accès aux notifications ».
 */
class MediaListener : NotificationListenerService()

data class MusicState(
    /** Accès aux notifications accordé (indispensable pour voir la musique des autres applis). */
    val access: Boolean = false,
    /** Une appli de musique a une session ouverte. */
    val active: Boolean = false,
    val title: String? = null,
    val artist: String? = null,
    val appName: String? = null,
    val packageName: String? = null,
    val art: Bitmap? = null,
    val playing: Boolean = false,
    /** null = l'appli ne dit pas si l'aléatoire est actif. */
    val shuffle: Boolean? = null,
)

/** Pilotage de la musique en cours (Spotify ou autre lecteur) via les sessions média d'Android. */
object MusicPlayer {
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(MusicState())
    val state: StateFlow<MusicState> = _state

    private var manager: MediaSessionManager? = null
    private var listening = false
    private var controller: MediaControllerCompat? = null
    private var controllerToken: MediaSession.Token? = null

    private val component by lazy { ComponentName(app, MediaListener::class.java) }

    private val callback = object : MediaControllerCompat.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadataCompat?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) = publish()
        override fun onShuffleModeChanged(shuffleMode: Int) = publish()
        override fun onSessionDestroyed() = refresh()
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list) }

    fun init(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
    }

    fun hasAccess(): Boolean = ::app.isInitialized && app.packageName in NotificationManagerCompat.getEnabledListenerPackages(app)

    /** Réglage Android « Accès aux notifications ». */
    fun accessIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** À appeler à l'affichage du widget (et au retour des réglages). */
    fun connect() {
        if (!::app.isInitialized) return
        if (!hasAccess()) {
            _state.value = MusicState(access = false)
            return
        }
        val m = manager ?: app.getSystemService(MediaSessionManager::class.java).also { manager = it } ?: return
        runCatching {
            if (!listening) {
                m.addOnActiveSessionsChangedListener(sessionsListener, component, main)
                listening = true
            }
            pick(m.getActiveSessions(component))
        }.onFailure { _state.value = MusicState(access = false) }
    }

    private fun refresh() {
        val m = manager ?: return
        runCatching { pick(m.getActiveSessions(component)) }
    }

    /** Choisit la session à afficher : celle qui joue, sinon Spotify, sinon la première. */
    private fun pick(list: List<MediaController>?) {
        val sessions = list.orEmpty()
        val chosen = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull { it.packageName == SpotifyLinks.PACKAGE }
            ?: sessions.firstOrNull()
        val token = chosen?.sessionToken
        if (token == null || controllerToken != token) {
            controller?.unregisterCallback(callback)
            controller = chosen?.let { MediaControllerCompat(app, MediaSessionCompat.Token.fromToken(it.sessionToken)) }
            controller?.registerCallback(callback, main)
            controllerToken = token
        }
        publish()
    }

    private fun publish() {
        val c = controller
        if (c == null) {
            _state.value = MusicState(access = true, active = false)
            return
        }
        val md = c.metadata
        val art = md?.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadataCompat.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON)
        val shuffle = when (c.shuffleMode) {
            PlaybackStateCompat.SHUFFLE_MODE_ALL, PlaybackStateCompat.SHUFFLE_MODE_GROUP -> true
            PlaybackStateCompat.SHUFFLE_MODE_NONE -> false
            else -> null
        }
        _state.value = MusicState(
            access = true,
            active = true,
            title = md?.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE),
            artist = md?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST),
            appName = appName(c.packageName),
            packageName = c.packageName,
            art = art,
            playing = c.playbackState?.state == PlaybackStateCompat.STATE_PLAYING,
            shuffle = shuffle,
        )
    }

    private fun appName(pkg: String?): String = when {
        pkg == null -> "?"
        pkg == SpotifyLinks.PACKAGE -> "Spotify"
        "deezer" in pkg -> "Deezer"
        pkg == "com.google.android.apps.youtube.music" -> "YouTube Music"
        "soundcloud" in pkg -> "SoundCloud"
        "amazon" in pkg && "mp3" in pkg -> "Amazon Music"
        else -> pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    // ------------------------------------------------------------------ commandes

    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackStateCompat.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() { controller?.transportControls?.skipToNext() }

    fun previous() { controller?.transportControls?.skipToPrevious() }

    fun toggleShuffle() {
        val c = controller ?: return
        val on = c.shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL
        c.transportControls.setShuffleMode(if (on) PlaybackStateCompat.SHUFFLE_MODE_NONE else PlaybackStateCompat.SHUFFLE_MODE_ALL)
    }

    /**
     * Lance une playlist Spotify (aléatoire si demandé).
     * Si Spotify tourne déjà, la lecture démarre sans quitter l'app ; sinon on ouvre Spotify sur la playlist
     * et on retente le lancement dès que sa session apparaît.
     */
    fun playSpotify(uri: String, shuffle: Boolean) {
        if (!::app.isInitialized) return
        if (startOnSpotifySession(uri, shuffle)) return
        val open = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(SpotifyLinks.PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { app.startActivity(open) }.isSuccess
        if (!ok) {
            SpotifyLinks.webUrl(uri)?.let { url ->
                runCatching { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            return
        }
        // Spotify met quelques secondes à ouvrir sa session
        listOf(2_500L, 5_000L, 8_000L).forEach { delay ->
            main.postDelayed({
                refresh()
                if (_state.value.packageName == SpotifyLinks.PACKAGE && !_state.value.playing) startOnSpotifySession(uri, shuffle)
            }, delay)
        }
    }

    private fun startOnSpotifySession(uri: String, shuffle: Boolean): Boolean {
        val m = manager ?: return false
        val spotify = runCatching { m.getActiveSessions(component) }.getOrNull()
            ?.firstOrNull { it.packageName == SpotifyLinks.PACKAGE } ?: return false
        val c = MediaControllerCompat(app, MediaSessionCompat.Token.fromToken(spotify.sessionToken))
        if (shuffle) c.transportControls.setShuffleMode(PlaybackStateCompat.SHUFFLE_MODE_ALL)
        c.transportControls.playFromUri(Uri.parse(uri), null)
        refresh()
        return true
    }

    /** Ouvre l'appli Spotify (sans playlist choisie). */
    fun openSpotify() {
        if (!::app.isInitialized) return
        val intent = app.packageManager.getLaunchIntentForPackage(SpotifyLinks.PACKAGE)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(intent) }
    }
}
