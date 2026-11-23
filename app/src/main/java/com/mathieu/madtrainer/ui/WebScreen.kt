package com.mathieu.madtrainer.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mathieu.madtrainer.AppViewModel
import com.mathieu.madtrainer.data.WebLinks
import com.mathieu.madtrainer.run.RunTracker

private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

/**
 * Lecteur unique de l'appli : la même page web sert à l'onglet Écran, au panneau des séances et au plein écran avec chiffres.
 * Elle n'est jamais rechargée en changeant d'écran (la vidéo continue) ; elle est mise en pause quand plus rien ne l'affiche.
 * Le contexte est un [MutableContextWrapper] rattaché à l'activité courante (rotation, plein écran du lecteur).
 */
object VideoHost {
    private var web: WebView? = null
    private var wrapper: MutableContextWrapper? = null
    private var loadedKey: String? = null
    private var attached = 0
    private val main = Handler(Looper.getMainLooper())
    private val pauseIfHidden = Runnable { if (attached == 0) pause() }

    var canGoBack by mutableStateOf(false)
    var progress by mutableIntStateOf(100)

    /** Vue plein écran demandée par la page (bouton plein écran du lecteur), affichée par l'onglet Écran. */
    var pageFullScreen by mutableStateOf<View?>(null)
    private var hidePageFullScreen: (() -> Unit)? = null

    /** Le panneau de séance est trop petit pour le plein écran du lecteur : il propose le plein écran avec chiffres. */
    var allowPageFullScreen = true

    /** Plein écran vidéo + chiffres pendant une séance ou une course. */
    var hud by mutableStateOf(false)

    @SuppressLint("SetJavaScriptEnabled")
    fun webView(context: Context): WebView {
        wrapper?.baseContext = context
        web?.let { return it }
        val w = MutableContextWrapper(context)
        wrapper = w
        return WebView(w).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            setBackgroundColor(android.graphics.Color.BLACK)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                // Les liens « ouvrir dans l'appli » (intent://, twitch://…) sont ignorés : on reste dans la page
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme ?: return true
                    return scheme != "http" && scheme != "https"
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    VideoHost.canGoBack = view.canGoBack()
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    VideoHost.canGoBack = view.canGoBack()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    VideoHost.progress = newProgress
                }

                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    if (!allowPageFullScreen) {
                        callback.onCustomViewHidden()
                        return
                    }
                    VideoHost.pageFullScreen = view
                    hidePageFullScreen = { callback.onCustomViewHidden() }
                }

                override fun onHideCustomView() {
                    VideoHost.pageFullScreen = null
                    hidePageFullScreen = null
                }
            }
            web = this
        }
    }

    /** Charge l'adresse si elle (ou le mode) a changé : lecteur seul pour Twitch / YouTube, sinon la page complète. */
    fun load(url: String, desktop: Boolean, videoOnly: Boolean) {
        val w = web ?: return
        if (url.isBlank()) return
        val embed = if (videoOnly) WebLinks.embedOf(url) else null
        val key = "$url|$desktop|${embed != null}"
        if (key == loadedKey) return
        loadedKey = key
        w.settings.userAgentString = if (desktop && embed == null) DESKTOP_UA else null
        if (embed != null) {
            w.loadDataWithBaseURL("https://${WebLinks.EMBED_HOST}/", WebLinks.playerHtml(embed), "text/html", "utf-8", null)
        } else {
            w.loadUrl(url)
        }
    }

    fun reload() {
        loadedKey = null
        web?.reload()
    }

    fun goBack() = web?.goBack()

    fun exitPageFullScreen() = hidePageFullScreen?.invoke()

    fun play() = web?.evaluateJavascript(
        "if(window.madPlay){madPlay()}else{document.querySelectorAll('video').forEach(function(v){v.play()})}", null,
    )

    fun pause() = web?.evaluateJavascript(
        "if(window.madPause){madPause()}else{document.querySelectorAll('video,audio').forEach(function(v){v.pause()})}", null,
    )

    /** Un écran affiche le lecteur : reprise de la lecture. */
    fun attach() {
        attached++
        main.removeCallbacks(pauseIfHidden)
        play()
    }

    /** Un écran n'affiche plus le lecteur : pause si aucun autre ne le reprend (le délai couvre un changement d'écran). */
    fun detach() {
        attached = (attached - 1).coerceAtLeast(0)
        if (attached == 0) main.postDelayed(pauseIfHidden, 600)
    }

    /** L'activité disparaît : rotation (on garde la page), ou fermeture de l'appli (on libère tout). */
    fun onActivityDestroyed(appContext: Context, finishing: Boolean) {
        if (finishing) {
            web?.destroy()
            web = null
            wrapper = null
            loadedKey = null
            pageFullScreen = null
            hud = false
        } else {
            wrapper?.baseContext = appContext
        }
    }

    internal fun current(): WebView? = web
}

/** Affiche le lecteur partagé à cet endroit (il est retiré de l'écran précédent sans être rechargé). */
@Composable
fun VideoSurface(vm: AppViewModel, modifier: Modifier, allowPageFullScreen: Boolean = true) {
    val settings by vm.settingsStore.settings.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        VideoHost.attach()
        onDispose { VideoHost.detach() }
    }
    AndroidView(
        factory = { ctx ->
            FrameLayout(ctx).also { frame ->
                val w = VideoHost.webView(ctx)
                (w.parent as? ViewGroup)?.removeView(w)
                frame.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        },
        update = {
            VideoHost.allowPageFullScreen = allowPageFullScreen
            VideoHost.load(settings.webUrl, settings.webDesktop, settings.webVideoOnly)
        },
        onRelease = { frame ->
            VideoHost.current()?.let { if (it.parent === frame) frame.removeView(it) }
        },
        modifier = modifier,
    )
}

/** Vidéo en plein écran demandée par la page (au-dessus de tout le contenu de l'onglet). */
@Composable
private fun PageFullScreenOverlay() {
    val v = VideoHost.pageFullScreen ?: return
    BackHandler { VideoHost.exitPageFullScreen() }
    AndroidView(
        factory = { ctx -> FrameLayout(ctx).also { f -> (v.parent as? ViewGroup)?.removeView(v); f.addView(v) } },
        modifier = Modifier.fillMaxSize(),
    )
}

/** Onglet Écran : un live Twitch, une vidéo YouTube ou n'importe quelle page web. */
@Composable
fun WebScreen(vm: AppViewModel) {
    val settings by vm.settingsStore.settings.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    // Écran allumé tant que l'onglet est affiché (vidéo)
    val hostView = LocalView.current
    DisposableEffect(Unit) {
        hostView.keepScreenOn = true
        onDispose { hostView.keepScreenOn = false }
    }
    val embed = WebLinks.embedOf(settings.webUrl)

    fun open(raw: String) {
        val url = WebLinks.normalize(raw)
        if (url == null) {
            error = "Adresse non reconnue : un nom de chaîne Twitch (ex. zerator) ou une adresse web."
            return
        }
        error = null
        input = ""
        vm.settingsStore.update { it.copy(webUrl = url, webRecent = WebLinks.pushRecent(it.webRecent, url)) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("Chaîne Twitch, lien YouTube ou adresse web") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { open(input) }),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { VideoHost.goBack() }, enabled = VideoHost.canGoBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Page précédente")
                    }
                    IconButton(onClick = { VideoHost.reload() }, enabled = settings.webUrl.isNotBlank()) {
                        Icon(Icons.Filled.Refresh, "Recharger")
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = settings.webVideoOnly,
                        onClick = { vm.settingsStore.update { it.copy(webVideoOnly = !it.webVideoOnly) } },
                        label = { Text("Vidéo seule") },
                    )
                    FilterChip(
                        selected = settings.webInSession,
                        onClick = { vm.settingsStore.update { it.copy(webInSession = !it.webInSession) } },
                        label = { Text("Afficher en séance") },
                    )
                    FilterChip(
                        selected = settings.webDesktop,
                        onClick = { vm.settingsStore.update { it.copy(webDesktop = !it.webDesktop) } },
                        label = { Text("Version ordinateur") },
                    )
                    settings.webRecent.forEach { u ->
                        AssistChip(onClick = { open(u) }, label = { Text(WebLinks.label(u), maxLines = 1) })
                    }
                }
                if (settings.webUrl.isNotBlank() && settings.webVideoOnly && embed == null) {
                    Text(
                        "Pas de vidéo reconnue dans cette adresse : la page complète est affichée. " +
                            "Pour la vidéo seule : une chaîne Twitch, une rediffusion Twitch ou un lien de vidéo YouTube.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Night.Muted,
                    )
                }
            }
            if (VideoHost.progress < 100 && settings.webUrl.isNotBlank()) {
                LinearProgressIndicator(progress = { VideoHost.progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
            if (settings.webUrl.isBlank()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.LiveTv, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("Regarde un live ou une vidéo pendant ta séance.", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Tape le nom d'une chaîne Twitch, ou colle un lien YouTube ou n'importe quelle adresse. " +
                            "Active « Afficher en séance » pour la retrouver dans les écrans Séance et Course, " +
                            "avec un plein écran qui garde tes chiffres affichés.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                BackHandler(enabled = VideoHost.canGoBack && embed == null) { VideoHost.goBack() }
                val shape = Modifier.fillMaxWidth()
                if (settings.webVideoOnly && embed != null) {
                    VideoSurface(vm, shape.aspectRatio(16f / 9f))
                } else {
                    VideoSurface(vm, shape.weight(1f))
                }
            }
        }
        PageFullScreenOverlay()
    }
}

/** Le lecteur dans les écrans Séance et Course : fixé en haut (ne défile pas), repliable, avec plein écran + chiffres. */
@Composable
fun WebMiniPanel(vm: AppViewModel, modifier: Modifier = Modifier) {
    val settings by vm.settingsStore.settings.collectAsStateWithLifecycle()
    if (!settings.webInSession || settings.webUrl.isBlank()) return
    val session by vm.session.collectAsStateWithLifecycle()
    val run by RunTracker.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(true) }
    val video = settings.webVideoOnly && WebLinks.embedOf(settings.webUrl) != null
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LiveTv, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text("  ${WebLinks.label(settings.webUrl)}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                if (session.running || run.running) {
                    IconButton(onClick = { VideoHost.hud = true }) { Icon(Icons.Filled.Fullscreen, "Plein écran avec mes chiffres") }
                }
                IconButton(onClick = { open = !open }) {
                    Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (open) "Replier" else "Déplier")
                }
            }
            if (open) {
                VideoSurface(
                    vm,
                    Modifier.fillMaxWidth().aspectRatio(if (video) 16f / 9f else 16f / 10f).clip(RoundedCornerShape(8.dp)),
                    allowPageFullScreen = false,
                )
            }
        }
    }
}
