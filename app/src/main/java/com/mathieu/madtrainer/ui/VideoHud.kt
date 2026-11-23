package com.mathieu.madtrainer.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreTime
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mathieu.madtrainer.AppViewModel
import com.mathieu.madtrainer.data.SessionExtension
import com.mathieu.madtrainer.data.formatDistance
import com.mathieu.madtrainer.data.formatDuration
import com.mathieu.madtrainer.data.formatPace
import com.mathieu.madtrainer.run.RunTracker
import kotlin.math.roundToInt

/** Une valeur du bandeau : libellé, chiffre, unité, couleur. */
private data class HudValue(val label: String, val value: String, val unit: String = "", val color: Color = Night.Text)

/** Ce qu'affiche le bandeau, pour l'elliptique comme pour la course. */
private data class HudData(
    val elapsed: Int,
    val paused: Boolean,
    /** « Étape 2/6 · Effort » ou nom du programme */
    val stepLabel: String?,
    /** Temps (ou distance) restant de l'étape */
    val stepRemaining: String?,
    val target: String?,
    val hr: Int?,
    val main: HudValue,
    val second: HudValue,
    val canExtend: Boolean,
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Plein écran pendant une séance ou une course : la vidéo, et par-dessus le temps, l'étape, le cardio et l'effort. */
@Composable
fun VideoHudScreen(vm: AppViewModel) {
    val settings by vm.settingsStore.settings.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val metrics by vm.bike.metrics.collectAsStateWithLifecycle()
    val run by RunTracker.state.collectAsStateWithLifecycle()
    val profile by vm.repo.profile.collectAsStateWithLifecycle()
    val zones = profile.hrZones
    val running = run.running || session.running

    // Séance terminée (ou jamais lancée) : on revient à l'écran normal
    LaunchedEffect(running) { if (!running) VideoHost.hud = false }
    BackHandler { VideoHost.hud = false }

    val activity = LocalContext.current.findActivity()
    val view = LocalView.current
    DisposableEffect(settings.videoLandscape) {
        activity?.requestedOrientation = if (settings.videoLandscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        val ctrl = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        ctrl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctrl?.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ctrl?.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
        }
    }

    val data = if (run.running) {
        val step = run.step
        val w = run.workout
        val remaining = step?.let { s ->
            s.distanceM?.let { formatDistance((it - run.stepDistance).coerceAtLeast(0.0)) }
                ?: formatDuration((s.durationSec - run.stepElapsed).coerceAtLeast(0))
        }
        HudData(
            elapsed = run.elapsed,
            paused = run.paused,
            stepLabel = w?.let { "Étape ${run.stepIndex + 1}/${it.steps.size} · ${step?.name.orEmpty()}" },
            stepRemaining = remaining,
            target = step?.let { stepTargetText(it) },
            hr = run.hr,
            main = HudValue("Allure", formatPace(run.currentPace), "/km", PowerColor),
            second = HudValue("Distance", "%.2f".format(run.distanceM / 1000).replace('.', ','), "km"),
            canExtend = w != null,
        )
    } else {
        val w = session.workout
        val p = session.position
        val step = if (w != null && p != null) w.steps.getOrNull(p.index) else null
        val free = step != null && SessionExtension.isFreeExtension(step)
        HudData(
            elapsed = session.elapsed,
            paused = session.paused,
            stepLabel = if (w != null && p != null) "Étape ${p.index + 1}/${w.steps.size} · ${step?.name.orEmpty()}" else "Séance libre",
            stepRemaining = p?.let { if (free) "+" + formatDuration(it.stepElapsed) else formatDuration(it.stepRemaining) },
            target = step?.takeUnless { free }?.let { stepTargetText(it) },
            hr = metrics.heartRate,
            main = HudValue("Puissance", metrics.powerW?.toString() ?: "--", "W", PowerColor),
            second = HudValue(
                "Résistance",
                metrics.resistanceRaw?.let { vm.bike.rawToLevel(it).roundToInt().toString() } ?: "--",
                metrics.cadence?.let { "· ${it.roundToInt()} spm" } ?: "",
                ResistanceColor,
            ),
            canExtend = w != null,
        )
    }
    val hr = data.hr?.takeIf { it > 0 }
    val hrValue = HudValue("Cardio", hr?.toString() ?: "--", hr?.let { "Z${zones.zoneOf(it)}" } ?: "bpm", hr?.let { zoneColor(zones.zoneOf(it)) } ?: Night.Muted)

    fun togglePause() = if (run.running) RunTracker.togglePause() else vm.togglePause()
    fun extend() = if (run.running) RunTracker.extendStep() else vm.extendStep()

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (landscape) {
            // Paysage : vidéo sur tout l'écran, bandeau semi-transparent en bas
            VideoSurface(vm, Modifier.fillMaxSize(), allowPageFullScreen = false)
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.62f))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                StepLine(data, 16.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    HudCell(HudValue("Temps", formatDuration(data.elapsed)), 34.sp)
                    data.stepRemaining?.let { HudCell(HudValue("Reste étape", it, color = Night.AccentText), 34.sp) }
                    HudCell(hrValue, 34.sp)
                    HudCell(data.main, 34.sp)
                    HudCell(data.second, 34.sp)
                }
            }
            Controls(data, Modifier.align(Alignment.TopEnd), ::togglePause, ::extend, vm)
        } else {
            // Portrait : vidéo en haut, gros chiffres dessous
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Controls(data, Modifier.align(Alignment.End), ::togglePause, ::extend, vm)
                VideoSurface(vm, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)), allowPageFullScreen = false)
                StepLine(data, 20.sp)
                Row {
                    HudCell(HudValue("Temps", formatDuration(data.elapsed)), 44.sp)
                    data.stepRemaining?.let { HudCell(HudValue("Reste étape", it, color = Night.AccentText), 44.sp) }
                }
                Row {
                    HudCell(hrValue, 56.sp)
                    HudCell(data.main, 56.sp)
                }
                Row { HudCell(data.second, 44.sp) }
            }
        }
    }
}

@Composable
private fun StepLine(d: HudData, size: TextUnit) {
    val text = listOfNotNull(
        if (d.paused) "EN PAUSE" else null,
        d.stepLabel,
        d.target,
    ).joinToString(" · ")
    if (text.isNotBlank()) {
        Text(text, color = if (d.paused) Night.Bad else Night.Soft, fontSize = size, maxLines = 1, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RowScope.HudCell(v: HudValue, size: TextUnit) {
    Column(Modifier.weight(1f)) {
        Text(v.label.uppercase(), color = Night.Muted, fontSize = 11.sp, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(v.value, color = v.color, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = size, style = NumberStyle, maxLines = 1)
            if (v.unit.isNotBlank()) Text(" ${v.unit}", color = Night.Soft, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

@Composable
private fun Controls(d: HudData, modifier: Modifier, onPause: () -> Unit, onExtend: () -> Unit, vm: AppViewModel) {
    val tint = Color.White
    Row(
        modifier.padding(6.dp).clip(RoundedCornerShape(24.dp)).background(Color.Black.copy(alpha = 0.55f)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IconButton(onClick = onPause) {
            Icon(if (d.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (d.paused) "Reprendre" else "Pause", tint = tint)
        }
        if (d.canExtend) IconButton(onClick = onExtend) { Icon(Icons.Filled.MoreTime, "+5 min", tint = tint) }
        IconButton(onClick = { vm.settingsStore.update { it.copy(videoLandscape = !it.videoLandscape) } }) {
            Icon(Icons.Filled.ScreenRotation, "Changer d'orientation", tint = tint)
        }
        IconButton(onClick = { VideoHost.hud = false }) { Icon(Icons.Filled.Close, "Quitter le plein écran", Modifier.size(24.dp), tint = tint) }
    }
}
