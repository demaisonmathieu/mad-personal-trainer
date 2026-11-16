package com.mathieu.madtrainer.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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

private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

/** Pilotage d'une page web affichée (retour arrière, rechargement, vidéo plein écran). */
private class WebHandle {
    var view: WebView? = null
    var canGoBack by mutableStateOf(false)
    var progress by mutableIntStateOf(100)
    /** Vue « plein écran » fournie par la page (bouton plein écran du lecteur vidéo). */
    var fullScreen by mutableStateOf<View?>(null)
    var hideFullScreen: (() -> Unit)? = null
}

/** Page web intégrée : JavaScript, stockage local, cookies (connexion Twitch), lecture auto des vidéos. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebPage(url: String, desktop: Boolean, handle: WebHandle, modifier: Modifier, allowFullScreen: Boolean = true) {
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    // Les liens « ouvrir dans l'appli » (intent://, twitch://…) sont ignorés : on reste dans la page
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val scheme = request.url.scheme ?: return true
                        return scheme != "http" && scheme != "https"
                    }

                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        handle.canGoBack = view.canGoBack()
                    }

                    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                        handle.canGoBack = view.canGoBack()
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView, newProgress: Int) {
                        handle.progress = newProgress
                    }

                    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                        // Panneau de séance : trop petit pour gérer le plein écran, on le refuse
                        if (!allowFullScreen) {
                            callback.onCustomViewHidden()
                            return
                        }
                        handle.fullScreen = view
                        handle.hideFullScreen = { callback.onCustomViewHidden() }
                    }

                    override fun onHideCustomView() {
                        handle.fullScreen = null
                        handle.hideFullScreen = null
                    }
                }
                handle.view = this
            }
        },
        update = { wv ->
            // Recharge seulement si l'adresse demandée ou le mode ordinateur a changé (null = user-agent par défaut)
            val key = "$url|$desktop"
            if (wv.tag != key) {
                wv.tag = key
                wv.settings.userAgentString = if (desktop) DESKTOP_UA else null
                wv.loadUrl(url)
            }
        },
        onRelease = { wv ->
            wv.stopLoading()
            wv.destroy()
            if (handle.view === wv) handle.view = null
        },
        modifier = modifier,
    )
}

/** Vidéo en plein écran (au-dessus de tout le contenu de l'onglet). */
@Composable
private fun FullScreenOverlay(handle: WebHandle) {
    val v = handle.fullScreen ?: return
    BackHandler { handle.hideFullScreen?.invoke() }
    AndroidView(factory = { v }, modifier = Modifier.fillMaxSize())
}

/** Onglet Écran : n'importe quelle page web, par exemple un live Twitch. */
@Composable
fun WebScreen(vm: AppViewModel) {
    val settings by vm.settingsStore.settings.collectAsStateWithLifecycle()
    val handle = remember { WebHandle() }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    // Écran allumé tant que l'onglet est affiché (vidéo)
    val hostView = LocalView.current
    DisposableEffect(Unit) {
        hostView.keepScreenOn = true
        onDispose { hostView.keepScreenOn = false }
    }

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
                        placeholder = { Text("Chaîne Twitch ou adresse web") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { open(input) }),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { handle.view?.goBack() }, enabled = handle.canGoBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Page précédente")
                    }
                    IconButton(onClick = { handle.view?.reload() }, enabled = settings.webUrl.isNotBlank()) {
                        Icon(Icons.Filled.Refresh, "Recharger")
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = settings.webDesktop,
                        onClick = { vm.settingsStore.update { it.copy(webDesktop = !it.webDesktop) } },
                        label = { Text("Version ordinateur") },
                    )
                    FilterChip(
                        selected = settings.webInSession,
                        onClick = { vm.settingsStore.update { it.copy(webInSession = !it.webInSession) } },
                        label = { Text("Afficher en séance") },
                    )
                    settings.webRecent.forEach { u ->
                        AssistChip(onClick = { open(u) }, label = { Text(WebLinks.label(u), maxLines = 1) })
                    }
                }
            }
            if (handle.progress < 100 && settings.webUrl.isNotBlank()) {
                LinearProgressIndicator(progress = { handle.progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
            if (settings.webUrl.isBlank()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.LiveTv, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("Regarde un live ou une vidéo pendant ta séance.", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Tape le nom d'une chaîne Twitch, ou n'importe quelle adresse (youtube.com…). " +
                            "Active « Afficher en séance » pour la retrouver en petit dans les écrans Séance et Course.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                BackHandler(enabled = handle.canGoBack) { handle.view?.goBack() }
                WebPage(settings.webUrl, settings.webDesktop, handle, Modifier.fillMaxWidth().weight(1f))
            }
        }
        FullScreenOverlay(handle)
    }
}

/** La même page, en petit, dans les écrans Séance et Course (repliable). */
@Composable
fun WebMiniPanel(vm: AppViewModel) {
    val settings by vm.settingsStore.settings.collectAsStateWithLifecycle()
    if (!settings.webInSession || settings.webUrl.isBlank()) return
    var open by remember { mutableStateOf(true) }
    val handle = remember { WebHandle() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LiveTv, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text("  ${WebLinks.label(settings.webUrl)}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                IconButton(onClick = { open = !open }) {
                    Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (open) "Replier" else "Déplier")
                }
            }
            if (open) {
                WebPage(
                    settings.webUrl, settings.webDesktop, handle,
                    Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(8.dp)),
                    allowFullScreen = false,
                )
            }
        }
    }
}
