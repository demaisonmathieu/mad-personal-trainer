package com.mathieu.madtrainer.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import com.mathieu.madtrainer.data.Elevation
import com.mathieu.madtrainer.data.HrZones
import com.mathieu.madtrainer.data.Sample
import com.mathieu.madtrainer.data.SessionRecord
import com.mathieu.madtrainer.data.formatDistance
import com.mathieu.madtrainer.data.formatDuration
import com.mathieu.madtrainer.data.formatPace
import com.mathieu.madtrainer.data.kmSplits
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.io.File
import kotlin.math.roundToInt

/** Parcours GPS sur fond de carte OpenStreetMap (déplaçable et zoomable). */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun RouteMapCard(samples: List<Sample>) {
    val pts = remember(samples) { samples.mapNotNull { s -> if (s.lat != null && s.lon != null) GeoPoint(s.lat, s.lon) else null } }
    if (pts.size < 2) return
    val routeColor = MaterialTheme.colorScheme.primary.toArgb()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Parcours", style = MaterialTheme.typography.labelLarge)
            AndroidView(
                factory = { ctx ->
                    Configuration.getInstance().apply {
                        // Politique des tuiles OSM : identifiant d'appli obligatoire ; cache dans le dossier de l'appli
                        userAgentValue = ctx.packageName
                        osmdroidBasePath = File(ctx.cacheDir, "osmdroid")
                        osmdroidTileCache = File(ctx.cacheDir, "osmdroid/tiles")
                    }
                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                        isTilesScaledToDpi = true
                        // La carte garde les gestes : sinon la page défile au lieu de déplacer la carte
                        setOnTouchListener { v, _ ->
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            false
                        }
                        val box = BoundingBox.fromGeoPointsSafe(pts)
                        val dotRadius = (box.diagonalLengthInMeters * 0.012).coerceIn(6.0, 60.0)
                        overlays += Polyline(this).apply {
                            setPoints(pts)
                            outlinePaint.color = routeColor
                            outlinePaint.strokeWidth = 10f
                            outlinePaint.isAntiAlias = true
                            infoWindow = null
                        }
                        overlays += dot(this, pts.first(), dotRadius, 0xFF2E9E4F.toInt())
                        overlays += dot(this, pts.last(), dotRadius, 0xFFE5395B.toInt())
                        addOnFirstLayoutListener { _, _, _, _, _ -> zoomToBoundingBox(box.increaseByScale(1.15f), false) }
                    }
                },
                onRelease = { it.onDetach() },
                modifier = Modifier.fillMaxWidth().aspectRatio(1.1f).padding(top = 8.dp).clip(RoundedCornerShape(8.dp)),
            )
            Text(
                "© contributeurs OpenStreetMap",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
    }
}

private fun dot(map: MapView, p: GeoPoint, radiusM: Double, color: Int) = Polygon(map).apply {
    points = Polygon.pointsAsCircle(p, radiusM)
    fillPaint.color = color
    outlinePaint.color = 0xFFFFFFFF.toInt()
    outlinePaint.strokeWidth = 4f
    infoWindow = null
}

/** Temps de chaque kilomètre, temps cumulé au passage et allure (dernier morceau incomplet compris). */
@Composable
fun KmSplitsCard(rec: SessionRecord, zones: HrZones? = null) {
    val splits = remember(rec.id, rec.samples.size) { kmSplits(rec.samples) }
    if (splits.isEmpty()) return
    // Variation d'altitude de chaque morceau (altitude lissée au début et à la fin)
    val climbs = remember(rec.id, rec.samples.size) {
        val sm = Elevation.smoothed(rec.samples)
        if (sm.count { it != null } < 60) null
        else splits.map { sp ->
            val range = rec.samples.indices.filter { rec.samples[it].t in (sp.cumulativeSec - sp.sec)..sp.cumulativeSec }
            val a = range.firstNotNullOfOrNull { sm[it] }
            val b = range.reversed().firstNotNullOfOrNull { sm[it] }
            if (a != null && b != null) (b - a).roundToInt() else null
        }
    }
    val best = splits.filter { it.distanceM == 1000 }.minOfOrNull { it.sec }
    val fastest = splits.minOf { it.paceSecKm }.coerceAtLeast(1)
    // Zone cardio moyenne de chaque morceau (couleur de la barre)
    val splitZones = remember(rec.id, rec.samples.size, zones) {
        splits.map { sp ->
            val hrs = rec.samples.filter { it.t in (sp.cumulativeSec - sp.sec)..sp.cumulativeSec }.mapNotNull { it.hr }.filter { it > 40 }
            if (zones == null || hrs.isEmpty()) null else zones.zoneOf(hrs.average().roundToInt())
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Temps au kilomètre", style = MaterialTheme.typography.labelLarge)
            Row {
                Text("Km", Modifier.weight(0.8f), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1.2f))
                Text("Temps", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                Text("Allure /km", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                if (climbs != null) Text("Dén.", Modifier.weight(0.8f), style = MaterialTheme.typography.labelSmall)
                Text("Cumul", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End)
            }
            splits.forEachIndexed { i, s ->
                val bold = if (s.distanceM == 1000 && s.sec == best) FontWeight.Bold else FontWeight.Normal
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (s.distanceM == 1000) "${s.km}" else formatDistance(s.distanceM.toDouble()), Modifier.weight(0.8f), fontWeight = bold)
                    // Barre : longueur = vitesse relative au km le plus rapide, couleur = zone cardio moyenne
                    Box(Modifier.weight(1.2f).padding(end = 8.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Night.Line)) {
                        Box(
                            Modifier.fillMaxWidth((fastest.toFloat() / s.paceSecKm.coerceAtLeast(1)).coerceIn(0.1f, 1f)).height(8.dp)
                                .clip(RoundedCornerShape(4.dp)).background(splitZones[i]?.let { zoneColor(it) } ?: Night.Accent),
                        )
                    }
                    Text(formatDuration(s.sec), Modifier.weight(1f), fontWeight = bold)
                    Text(formatPace(s.paceSecKm), Modifier.weight(1f), fontWeight = bold)
                    if (climbs != null) Text(climbs[i]?.let { (if (it > 0) "+" else "") + "$it m" } ?: "--", Modifier.weight(0.8f), fontWeight = bold)
                    Text(formatDuration(s.cumulativeSec), Modifier.weight(1f), fontWeight = bold, textAlign = TextAlign.End)
                }
            }
        }
    }
}

/** Profil d'altitude et dénivelé positif / négatif. */
@Composable
fun ElevationCard(rec: SessionRecord) {
    val stats = remember(rec.id, rec.samples.size) { Elevation.stats(rec.samples) } ?: return
    val profile = remember(rec.id, rec.samples.size) {
        val sm = Elevation.smoothed(rec.samples)
        val min = sm.filterNotNull().minOrNull() ?: 0.0
        val stride = (sm.size / 400).coerceAtLeast(1)
        // Profil relatif au point le plus bas (le graphique part de zéro)
        sm.filterIndexed { i, _ -> i % stride == 0 }.map { v -> v?.let { (it - min + stats.rangeM * 0.15 + 1).toFloat() } }
    }
    ChartCard("Altitude : D+ ${stats.gainM} m · D− ${stats.lossM} m · écart ${stats.rangeM} m", profile, ElevationColor)
}

private val ElevationColor = androidx.compose.ui.graphics.Color(0xFF8D6E63)
