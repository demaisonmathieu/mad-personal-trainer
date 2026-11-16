package com.mathieu.madtrainer.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mathieu.madtrainer.AppViewModel
import com.mathieu.madtrainer.coach.Meal
import com.mathieu.madtrainer.coach.NutritionPlan
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Restaurant
import com.mathieu.madtrainer.data.BodyMeasure
import com.mathieu.madtrainer.data.BodyStats
import com.mathieu.madtrainer.data.BodyStats.fmt
import com.mathieu.madtrainer.data.BodyStats.signed
import com.mathieu.madtrainer.health.HealthAvailability
import com.mathieu.madtrainer.health.HealthConnectSource
import com.mathieu.madtrainer.health.sourceLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val weighFmt = SimpleDateFormat("EEE d MMM yyyy · HH:mm", Locale.FRANCE)
private val syncFmt = SimpleDateFormat("d MMM · HH:mm", Locale.FRANCE)
private val periods = listOf(30, 90, 365)

/** Suivi du poids et de la composition corporelle (balance Starfit via Health Connect, FC de repos Zepp). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BodyScreen(vm: AppViewModel) {
    val ui by vm.healthUi.collectAsStateWithLifecycle()
    val cache by vm.repo.body.collectAsStateWithLifecycle()
    val profile by vm.repo.profile.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var period by rememberSaveable { mutableIntStateOf(90) }
    val globalPlan by vm.repo.globalPlan.collectAsStateWithLifecycle()
    val workouts by vm.repo.workouts.collectAsStateWithLifecycle()
    val nutrition = remember(cache, profile, globalPlan, workouts) { vm.nutritionPlan() }

    val permissionLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        vm.refreshHealth()
    }
    LaunchedEffect(Unit) { vm.refreshHealth() }

    val now = System.currentTimeMillis()
    val last = BodyStats.latest(cache.measures)
    val restingHr = BodyStats.recentRestingHr(cache.restingHr, now)
    val sources = (cache.measures.map { it.source } + cache.restingHr.map { it.source }).distinct().map { sourceLabel(it) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Corps", Modifier.weight(1f))
                if (ui.syncing) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp).width(20.dp).height(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { vm.refreshHealth() }) { Icon(Icons.Filled.Refresh, "Actualiser") }
                }
            }
        }

        // Liaison Health Connect
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Health Connect", fontWeight = FontWeight.SemiBold)
                    when {
                        ui.availability == null -> Text("Vérification…", style = MaterialTheme.typography.bodySmall)
                        ui.availability != HealthAvailability.AVAILABLE -> {
                            Text(
                                if (ui.availability == HealthAvailability.NEEDS_UPDATE) "Health Connect doit être mis à jour."
                                else "Health Connect n'est pas disponible sur ce téléphone.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(onClick = { runCatching { context.startActivity(vm.health.storeIntent()) } }) {
                                Text(if (ui.availability == HealthAvailability.NEEDS_UPDATE) "Mettre à jour" else "Installer")
                            }
                        }
                        !ui.connected -> {
                            Text(
                                "Autorise MAD Trainer à lire le poids, la composition corporelle (balance Starfit) et la FC de repos (Zepp).",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(onClick = { permissionLauncher.launch(HealthConnectSource.permissions) }) { Text("Autoriser l'accès") }
                        }
                        else -> {
                            Text(
                                "Connecté · ${ui.granted.size} type(s) de données autorisé(s)" +
                                    (if (cache.lastSync > 0) " · synchro ${syncFmt.format(Date(cache.lastSync))}" else ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (sources.isNotEmpty()) {
                                Text("Sources : ${sources.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (ui.granted.size < HealthConnectSource.permissions.size) {
                                    OutlinedButton(onClick = { permissionLauncher.launch(HealthConnectSource.permissions) }) {
                                        Text("Autoriser le reste")
                                    }
                                }
                                OutlinedButton(onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    }
                                }) { Text("Réglages") }
                            }
                        }
                    }
                    ui.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    ui.error?.let { Text("Erreur : $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        if (last == null) {
            item {
                Text(
                    if (ui.connected) "Aucune pesée trouvée sur les 12 derniers mois. Pèse-toi avec la balance : Starfit l'enverra à Health Connect, puis actualise."
                    else "Aucune pesée importée pour l'instant.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { HydrationCard() }
            item { NutritionCard(nutrition) }
        } else {
            // Dernière pesée
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Dernière pesée : ${weighFmt.format(Date(last.time))} · ${sourceLabel(last.source)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val heightM = profile.heightCm / 100.0
                    val tiles = buildList {
                        add(Tile("Poids", fmt(last.weightKg!!), "kg", Icons.Filled.MonitorWeight, Color(0xFF4FC3F7)))
                        last.bodyFatPct?.let { add(Tile("Masse grasse", fmt(it), "%", Icons.Filled.Accessibility, Color(0xFFFFB74D))) }
                        last.leanMassKg?.let { add(Tile("Masse maigre", fmt(it), "kg", Icons.Filled.FitnessCenter, Color(0xFF81C784))) }
                        last.waterPct?.let { add(Tile("Eau", fmt(it), "%", Icons.Filled.WaterDrop, Color(0xFF64B5F6))) }
                        last.boneMassKg?.let { add(Tile("Masse osseuse", fmt(it), "kg", Icons.Filled.Straighten, Color(0xFFBDBDBD))) }
                        last.bmrKcal?.let { add(Tile("Métabolisme", "$it", "kcal/j", Icons.Filled.LocalFireDepartment, Color(0xFFE57373))) }
                        if (heightM > 0) add(Tile("IMC", fmt(last.weightKg!! / (heightM * heightM)), "", Icons.Filled.Straighten, Color(0xFFBA68C8)))
                        restingHr?.let { add(Tile("FC de repos", "$it", "bpm", Icons.Filled.Favorite, Color(0xFFF06292))) }
                    }
                    tiles.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { t -> MetricTile(t.label, t.value, t.unit, t.icon, t.color, Modifier.weight(1f)) }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            // Période des courbes
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    periods.forEachIndexed { i, d ->
                        SegmentedButton(
                            selected = period == d,
                            onClick = { period = d },
                            shape = SegmentedButtonDefaults.itemShape(i, periods.size),
                            icon = {},
                        ) { Text(if (d == 365) "1 an" else "$d j") }
                    }
                }
            }

            item {
                TrendCard("Poids", "kg", cache.measures, period, now, Color(0xFF4FC3F7)) { it.weightKg }
            }
            if (cache.measures.any { it.bodyFatPct != null }) {
                item { TrendCard("Masse grasse", "point(s)", cache.measures, period, now, Color(0xFFFFB74D)) { it.bodyFatPct } }
            }
            if (cache.measures.any { it.leanMassKg != null }) {
                item { TrendCard("Masse maigre", "kg", cache.measures, period, now, Color(0xFF81C784)) { it.leanMassKg } }
            }

            item { HydrationCard() }
            item { NutritionCard(nutrition) }

            // Historique des pesées
            item { SectionTitle("Pesées") }
            items(cache.measures.filter { it.weightKg != null }.sortedByDescending { it.time }.take(30)) { m ->
                Column {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(weighFmt.format(Date(m.time)), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                listOfNotNull(
                                    m.bodyFatPct?.let { "MG ${fmt(it)} %" },
                                    m.leanMassKg?.let { "MM ${fmt(it)} kg" },
                                    m.waterPct?.let { "eau ${fmt(it)} %" },
                                    sourceLabel(m.source),
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("${fmt(m.weightKg!!)} kg", fontWeight = FontWeight.Bold)
                    }
                    HorizontalDivider()
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

private data class Tile(val label: String, val value: String, val unit: String, val icon: ImageVector, val color: Color)

@Composable
private fun TrendCard(
    title: String,
    deltaUnit: String,
    measures: List<BodyMeasure>,
    days: Int,
    now: Long,
    color: Color,
    value: (BodyMeasure) -> Double?,
) {
    val series = BodyStats.dailySeries(measures, days, now, value)
    val trend = BodyStats.trend(measures, days, now, value)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = color, modifier = Modifier.weight(1f))
                Text(
                    trend?.let {
                        "${signed(it.delta)} $deltaUnit" + if (deltaUnit == "kg") " (${signed(it.perWeek)} kg/sem.)" else ""
                    } ?: "pas assez de pesées",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            ScaledChart(series, color, Modifier.fillMaxWidth().height(130.dp).padding(top = 8.dp))
        }
    }
}

/** Courbe mise à l'échelle entre le minimum et le maximum de la période ; relie les pesées entre les jours vides. */
@Composable
private fun ScaledChart(values: List<Float?>, color: Color, modifier: Modifier = Modifier) {
    val pts = values.withIndex().filter { it.value != null }.map { it.index to it.value!! }
    if (pts.isEmpty()) {
        Text("Aucune pesée sur la période", style = MaterialTheme.typography.bodySmall, modifier = modifier)
    } else {
        val min = pts.minOf { it.second }
        val max = pts.maxOf { it.second }
        val pad = maxOf((max - min) * 0.15f, 0.5f)
        val lo = min - pad
        val hi = max + pad
        val grid = MaterialTheme.colorScheme.outlineVariant
        Row(modifier) {
            Column(Modifier.fillMaxSize().weight(1f)) {
                Canvas(Modifier.fillMaxWidth().weight(1f)) {
                    for (i in 1..3) {
                        val y = size.height * i / 4f
                        drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    }
                    val stepX = if (values.size > 1) size.width / (values.size - 1) else 0f
                    fun pos(i: Int, v: Float) = Offset(i * stepX, size.height - (v - lo) / (hi - lo) * size.height)
                    val path = Path()
                    pts.forEachIndexed { k, (i, v) ->
                        val o = pos(i, v)
                        if (k == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                    }
                    drawPath(path, color, style = Stroke(width = 4f))
                    pts.forEach { (i, v) -> drawCircle(color, radius = 5f, center = pos(i, v)) }
                }
            }
            Column(Modifier.padding(start = 6.dp).fillMaxSize().weight(0.18f), verticalArrangement = Arrangement.SpaceBetween) {
                Text(fmt(max.toDouble()), style = MaterialTheme.typography.labelSmall)
                Text(fmt(min.toDouble()), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Plan nutritionnel calculé sans IA : besoins, macros, repas types (jour de séance / de repos). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NutritionCard(n: NutritionPlan) {
    var training by rememberSaveable { mutableStateOf(true) }
    var showTips by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Restaurant, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text("  Nutrition (calcul sans IA)", fontWeight = FontWeight.SemiBold)
            }
            Text(
                "Métabolisme de base ${n.bmr} kcal (${n.bmrSource}) · vie quotidienne ${n.dailyBase} kcal · " +
                    "séance ≈ ${n.sessionKcal} kcal × ${n.sessionsPerWeek}/sem." +
                    (if (n.adjustment < 0) " · déficit ${-n.adjustment} kcal/j" else " · maintien"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            n.weeksToTarget?.let {
                Text("Poids visé atteint en environ $it semaines à ce rythme.", style = MaterialTheme.typography.bodySmall)
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(true, false).forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = training == t,
                        onClick = { training = t },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                        icon = {},
                    ) { Text(if (t) "Jour de séance" else "Jour de repos") }
                }
            }
            StatRow(
                listOf(
                    "kcal" to "${if (training) n.trainingDayKcal else n.restDayKcal}",
                    "protéines" to "${n.proteinG} g",
                    "glucides" to "${if (training) n.carbsTrainingG else n.carbsRestG} g",
                    "lipides" to "${n.fatG} g",
                ),
            )
            Text("Eau : ${fmt(n.waterL)} L par jour (+0,5 L par heure de séance)", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            (if (training) n.trainingMeals else n.restMeals).forEach { MealRow(it) }
            Text(
                "Portions indicatives, à ajuster selon ta faim et l'évolution du poids sur 2 à 3 semaines.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { showTips = !showTips }) { Text(if (showTips) "Masquer les conseils" else "Conseils") }
            if (showTips) n.tips.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun MealRow(m: Meal) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${m.kcal} kcal", style = MaterialTheme.typography.labelMedium)
        }
        Text("P ${m.proteinG} g · G ${m.carbsG} g · L ${m.fatG} g", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(m.example, style = MaterialTheme.typography.bodySmall)
    }
}
