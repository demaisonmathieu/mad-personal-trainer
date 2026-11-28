package com.mathieu.madtrainer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mathieu.madtrainer.AppViewModel
import com.mathieu.madtrainer.coach.Sex
import com.mathieu.madtrainer.data.ActivityLevel
import com.mathieu.madtrainer.data.BodyStats
import com.mathieu.madtrainer.data.CalorieGoal
import com.mathieu.madtrainer.data.Food
import com.mathieu.madtrainer.data.FoodCategory
import com.mathieu.madtrainer.data.FoodEntry
import com.mathieu.madtrainer.data.MealPlan
import com.mathieu.madtrainer.data.MealPlanStore
import com.mathieu.madtrainer.data.MealSlot
import com.mathieu.madtrainer.data.NutritionTargets
import com.mathieu.madtrainer.data.Nutrients
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.roundToInt

private val ProteinColor = Color(0xFF4FA3FF)
private val CarbsColor = Color(0xFFFFB020)
private val FatColor = Color(0xFFB9A6F2)
private val SlotColors = listOf(Color(0xFFFFB27A), Color(0xFF3DDC97), Color(0xFF4FC3F7), Color(0xFFFF6B81))
private val dayFmt = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRANCE)

/** Objectifs caloriques (Mifflin-St Jeor), planificateur de repas et tableau de bord du jour. */
@Composable
fun MealsScreen(vm: AppViewModel) {
    val state by MealPlanStore.state.collectAsStateWithLifecycle()
    val profile by vm.repo.profile.collectAsStateWithLifecycle()
    val body by vm.repo.body.collectAsStateWithLifecycle()
    val today = LocalDate.now().toEpochDay()
    var day by rememberSaveable { mutableLongStateOf(today) }
    var addTo by remember { mutableStateOf<MealSlot?>(null) }
    var pendingTemplate by remember { mutableStateOf<MealPlan.Template?>(null) }

    val lastWeight = BodyStats.latest(body.measures)?.weightKg
    val targets = MealPlanStore.targetsFor(state, profile, lastWeight)
    val goal = MealPlan.macroGrams(targets)
    val foods = remember(state.customFoods) { MealPlan.foodsById(state.customFoods) }
    val entries = MealPlanStore.entries(state, day)
    val eaten = MealPlan.total(entries, foods)
    val bySlot = MealPlan.bySlot(entries, foods)

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader("Repas", eyebrow = "Objectifs, menus et suivi du jour")
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { day -= 1 }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Jour précédent") }
                Text(
                    when (day) {
                        today -> "Aujourd'hui"
                        today - 1 -> "Hier"
                        today + 1 -> "Demain"
                        else -> LocalDate.ofEpochDay(day).format(dayFmt).replaceFirstChar { it.uppercase() }
                    },
                    fontFamily = Display,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { day += 1 }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Jour suivant") }
            }
        }
        item { DashboardCard(goal, eaten, bySlot) }
        item { TargetsCard(targets) { MealPlanStore.setTargets(it) } }
        item {
            Column {
                Eyebrow("Journées types", modifier = Modifier.padding(top = 4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    items(MealPlan.templates) { tpl ->
                        NightCard(Modifier.width(200.dp), onClick = { pendingTemplate = tpl }) {
                            Text(tpl.name, fontWeight = FontWeight.SemiBold)
                            Text(tpl.detail, style = MaterialTheme.typography.labelSmall, color = Night.Muted)
                        }
                    }
                }
            }
        }
        MealSlot.entries.forEachIndexed { i, slot ->
            item(key = "slot-$slot") {
                MealCard(
                    slot = slot,
                    color = SlotColors[i],
                    entries = entries.filter { it.slot == slot },
                    foods = foods,
                    total = bySlot[slot] ?: Nutrients(),
                    onAdd = { addTo = slot },
                    onChange = { MealPlanStore.replace(day, it) },
                    onRemove = { MealPlanStore.remove(day, it) },
                )
            }
        }
        if (entries.isNotEmpty()) {
            item {
                TextButton(onClick = { MealPlanStore.clear(day) }) { Text("Vider la journée", color = Night.Bad) }
            }
        }
        item {
            Text(
                "Valeurs moyennes pour 100 g (tables de composition arrondies). Tout est enregistré automatiquement sur le téléphone.",
                style = MaterialTheme.typography.labelSmall,
                color = Night.Muted,
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    addTo?.let { slot ->
        FoodPicker(
            slot = slot,
            customFoods = state.customFoods,
            onPick = { MealPlanStore.add(day, it, slot); addTo = null },
            onCreate = { MealPlanStore.addCustomFood(it) },
            onDeleteCustom = { MealPlanStore.removeCustomFood(it) },
            onDismiss = { addTo = null },
        )
    }
    pendingTemplate?.let { tpl ->
        AlertDialog(
            onDismissRequest = { pendingTemplate = null },
            title = { Text("Journée « ${tpl.name} »") },
            text = {
                Text(
                    "Remplace les repas de ce jour par une journée type, quantités ajustées à ${goal.kcal.roundToInt()} kcal. " +
                        "Tu pourras ensuite modifier chaque aliment.",
                )
            },
            confirmButton = {
                TextButton(onClick = { MealPlanStore.applyTemplate(day, tpl, goal.kcal.roundToInt()); pendingTemplate = null }) { Text("Appliquer") }
            },
            dismissButton = { TextButton(onClick = { pendingTemplate = null }) { Text("Annuler") } },
        )
    }
}

// --- Tableau de bord ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardCard(goal: Nutrients, eaten: Nutrients, bySlot: Map<MealSlot, Nutrients>) {
    var showRemaining by rememberSaveable { mutableStateOf(true) }
    var byMeal by rememberSaveable { mutableStateOf(false) }
    NightCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CalorieGauge(goal.kcal, eaten.kcal, showRemaining, Modifier.size(150.dp).clickable { showRemaining = !showRemaining })
            Column(Modifier.padding(start = 16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MacroBar("Protéines", eaten.protein, goal.protein, ProteinColor)
                MacroBar("Glucides", eaten.carbs, goal.carbs, CarbsColor)
                MacroBar("Lipides", eaten.fat, goal.fat, FatColor)
            }
        }
        HorizontalDivider(color = Night.Line)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(false, true).forEachIndexed { i, meal ->
                SegmentedButton(
                    selected = byMeal == meal,
                    onClick = { byMeal = meal },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                    icon = {},
                ) { Text(if (meal) "Par repas" else "Par macro") }
            }
        }
        val slices = if (byMeal) {
            MealSlot.entries.mapIndexed { i, s -> Slice(s.label, bySlot[s]?.kcal ?: 0.0, SlotColors[i], "${(bySlot[s]?.kcal ?: 0.0).roundToInt()} kcal") }
        } else {
            listOf(
                Slice("Protéines", eaten.protein * 4, ProteinColor, "${eaten.protein.roundToInt()} g"),
                Slice("Glucides", eaten.carbs * 4, CarbsColor, "${eaten.carbs.roundToInt()} g"),
                Slice("Lipides", eaten.fat * 9, FatColor, "${eaten.fat.roundToInt()} g"),
            )
        }
        DonutChart(slices)
    }
}

/** Jauge circulaire : calories restantes (ou consommées), arc rouge au-delà de l'objectif. */
@Composable
private fun CalorieGauge(goal: Double, eaten: Double, remaining: Boolean, modifier: Modifier) {
    val ratio = if (goal > 0) (eaten / goal).toFloat() else 0f
    val over = eaten > goal
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val stroke = 14.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(Night.Line, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (ratio > 0f) {
                drawArc(
                    if (over) Night.Bad else Night.Good,
                    135f, 270f * ratio.coerceAtMost(1f), false, Offset(inset, inset), arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val value = if (remaining) goal - eaten else eaten
            Text(
                "${kotlin.math.abs(value).roundToInt()}",
                fontFamily = Display,
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
                style = NumberStyle,
                color = if (remaining && value < 0) Night.Bad else Night.Text,
            )
            Text(
                when {
                    !remaining -> "kcal mangées"
                    value < 0 -> "kcal en trop"
                    else -> "kcal restantes"
                },
                style = MaterialTheme.typography.labelSmall,
                color = Night.Muted,
            )
            Text("sur ${goal.roundToInt()}", style = MaterialTheme.typography.labelSmall, color = Night.Muted)
        }
    }
}

@Composable
private fun MacroBar(label: String, eaten: Double, goal: Double, color: Color) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.weight(1f))
            Text("${eaten.roundToInt()} / ${goal.roundToInt()} g", style = MaterialTheme.typography.labelMedium.merge(NumberStyle))
        }
        LinearProgressIndicator(
            progress = { if (goal > 0) (eaten / goal).toFloat().coerceIn(0f, 1f) else 0f },
            color = if (eaten > goal * 1.1) Night.Bad else color,
            trackColor = Night.Line,
            modifier = Modifier.fillMaxWidth().height(6.dp).padding(top = 2.dp),
        )
    }
}

private data class Slice(val label: String, val value: Double, val color: Color, val detail: String)

/** Anneau interactif : toucher une part (ou sa légende) l'isole et affiche sa valeur au centre. */
@Composable
private fun DonutChart(slices: List<Slice>) {
    val total = slices.sumOf { it.value }
    var selected by remember(slices.map { it.label }) { mutableStateOf<Int?>(null) }
    val current by rememberUpdatedState(slices)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(130.dp), contentAlignment = Alignment.Center) {
            Canvas(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures { p ->
                        val sum = current.sumOf { it.value }
                        if (sum <= 0) return@detectTapGestures
                        val dx = p.x - size.width / 2f
                        val dy = p.y - size.height / 2f
                        // Angle depuis le haut, sens horaire
                        val deg = ((Math.toDegrees(atan2(dy, dx).toDouble()) + 90 + 360) % 360)
                        var acc = 0.0
                        val hit = current.indexOfFirst { s -> acc += s.value / sum * 360; deg < acc }
                        selected = if (hit == selected) null else hit.takeIf { it >= 0 }
                    }
                },
            ) {
                val stroke = 22.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                if (total <= 0) {
                    drawArc(Night.Line, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                } else {
                    var start = -90f
                    slices.forEachIndexed { i, s ->
                        val sweep = (s.value / total * 360).toFloat()
                        val dim = selected != null && selected != i
                        drawArc(
                            s.color.copy(alpha = if (dim) 0.25f else 1f),
                            start, sweep, false, Offset(inset, inset), arcSize,
                            style = Stroke(if (selected == i) stroke * 1.25f else stroke),
                        )
                        start += sweep
                    }
                }
            }
            val sel = selected?.let { slices.getOrNull(it) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (sel != null && total > 0) {
                    Text("${(sel.value / total * 100).roundToInt()} %", fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(sel.label, style = MaterialTheme.typography.labelSmall, color = Night.Muted)
                } else {
                    Text(if (total > 0) "Touche" else "Vide", style = MaterialTheme.typography.labelSmall, color = Night.Muted)
                }
            }
        }
        Column(Modifier.padding(start = 16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            slices.forEachIndexed { i, s ->
                Row(
                    Modifier.fillMaxWidth().clickable { selected = if (selected == i) null else i },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp)) { Canvas(Modifier.fillMaxSize()) { drawCircle(s.color) } }
                    Text(
                        "  ${s.label}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (selected == i) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        s.detail + if (total > 0) " · ${(s.value / total * 100).roundToInt()} %" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = Night.Muted,
                    )
                }
            }
        }
    }
}

// --- Calculateur ---

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TargetsCard(t: NutritionTargets, onChange: (NutritionTargets) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val bmr = MealPlan.bmr(t)
    val tdee = MealPlan.tdee(t)
    val target = MealPlan.targetKcal(t)
    NightCard(onClick = if (open) null else ({ open = true })) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Eyebrow("Objectif · ${t.goal.label}")
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$target", fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 32.sp, style = NumberStyle)
                    Text(" kcal / jour", color = Night.Muted, modifier = Modifier.padding(bottom = 6.dp))
                }
                Text(
                    "Métabolisme $bmr kcal · dépense totale $tdee kcal · P ${t.proteinPct} % · G ${t.carbsPct} % · L ${t.fatPct} %",
                    style = MaterialTheme.typography.labelSmall,
                    color = Night.Muted,
                )
            }
            TextButton(onClick = { open = !open }) { Text(if (open) "Fermer" else "Régler") }
        }
        if (open) {
            HorizontalDivider(color = Night.Line)
            Eyebrow("Profil")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val sexes = listOf(Sex.MALE, Sex.FEMALE)
                sexes.forEachIndexed { i, s ->
                    SegmentedButton(
                        selected = t.sex == s,
                        onClick = { onChange(t.copy(sex = s)) },
                        shape = SegmentedButtonDefaults.itemShape(i, sexes.size),
                        icon = {},
                    ) { Text(s.label) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Âge", t.age.toString(), Modifier.weight(1f)) { v -> v.toIntOrNull()?.takeIf { it in 14..100 }?.let { onChange(t.copy(age = it)) } }
                NumberField("Poids (kg)", BodyStats.fmt(t.weightKg), Modifier.weight(1f), decimal = true) { v ->
                    v.replace(',', '.').toDoubleOrNull()?.takeIf { it in 30.0..300.0 }?.let { onChange(t.copy(weightKg = it)) }
                }
                NumberField("Taille (cm)", t.heightCm.toString(), Modifier.weight(1f)) { v -> v.toIntOrNull()?.takeIf { it in 120..230 }?.let { onChange(t.copy(heightCm = it)) } }
            }
            Eyebrow("Niveau d'activité")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActivityLevel.entries.forEach { a ->
                    FilterChip(selected = t.activity == a, onClick = { onChange(t.copy(activity = a)) }, label = { Text(a.label) })
                }
            }
            Text("${t.activity.detail} · × ${t.activity.factor}", style = MaterialTheme.typography.labelSmall, color = Night.Muted)
            Eyebrow("Objectif")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CalorieGoal.entries.forEach { g ->
                    FilterChip(
                        selected = t.goal == g,
                        onClick = { onChange(if (g == CalorieGoal.CUSTOM && t.goal != g) t.copy(goal = g, customKcal = target) else t.copy(goal = g)) },
                        label = {
                            Text(
                                when (g) {
                                    CalorieGoal.CUSTOM -> g.label
                                    CalorieGoal.MAINTAIN -> g.label
                                    else -> "${g.label} (${if (g.delta > 0) "+" else ""}${g.delta})"
                                },
                            )
                        },
                    )
                }
            }
            if (t.goal == CalorieGoal.CUSTOM) {
                NumberField("Cible (kcal / jour)", t.customKcal.toString(), Modifier.fillMaxWidth()) { v ->
                    v.toIntOrNull()?.takeIf { it in 800..6000 }?.let { onChange(t.copy(customKcal = it)) }
                }
            }
            Eyebrow("Répartition des macros (total 100 %)")
            val current = Triple(t.proteinPct, t.carbsPct, t.fatPct)
            val grams = MealPlan.macroGrams(t)
            MacroSlider("Protéines", t.proteinPct, grams.protein, ProteinColor) { v ->
                val (p, c, f) = MealPlan.rebalance(current, 0, v); onChange(t.copy(proteinPct = p, carbsPct = c, fatPct = f))
            }
            MacroSlider("Glucides", t.carbsPct, grams.carbs, CarbsColor) { v ->
                val (p, c, f) = MealPlan.rebalance(current, 1, v); onChange(t.copy(proteinPct = p, carbsPct = c, fatPct = f))
            }
            MacroSlider("Lipides", t.fatPct, grams.fat, FatColor) { v ->
                val (p, c, f) = MealPlan.rebalance(current, 2, v); onChange(t.copy(proteinPct = p, carbsPct = c, fatPct = f))
            }
            Text(
                "Repères : protéines 1,6 à 2,2 g/kg (${(t.weightKg * 1.6).roundToInt()}–${(t.weightKg * 2.2).roundToInt()} g), lipides au moins 20 %.",
                style = MaterialTheme.typography.labelSmall,
                color = Night.Muted,
            )
        }
    }
}

@Composable
private fun MacroSlider(label: String, pct: Int, grams: Double, color: Color, onChange: (Int) -> Unit) {
    Column {
        Row {
            Text(label, color = color, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text("$pct % · ${grams.roundToInt()} g", style = MaterialTheme.typography.labelLarge.merge(NumberStyle))
        }
        Slider(
            value = pct.toFloat(),
            onValueChange = { v -> v.roundToInt().let { if (it != pct) onChange(it) } },
            valueRange = 5f..70f,
            colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color, inactiveTrackColor = Night.Line),
        )
    }
}

/** Champ numérique : garde la saisie en cours et ne transmet que les valeurs valides. */
@Composable
private fun NumberField(label: String, value: String, modifier: Modifier, decimal: Boolean = false, onValue: (String) -> Unit) {
    // Resynchronisé seulement si la valeur numérique change (taper « 80. » ne doit pas effacer le point)
    var text by remember(value.replace(',', '.').toDoubleOrNull()) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onValue(it) },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}

// --- Repas ---

@Composable
private fun MealCard(
    slot: MealSlot,
    color: Color,
    entries: List<FoodEntry>,
    foods: Map<String, Food>,
    total: Nutrients,
    onAdd: () -> Unit,
    onChange: (FoodEntry) -> Unit,
    onRemove: (Long) -> Unit,
) {
    NightCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp)) { Canvas(Modifier.fillMaxSize()) { drawCircle(color) } }
            Column(Modifier.padding(start = 8.dp).weight(1f)) {
                Text(slot.label, fontWeight = FontWeight.SemiBold)
                Text(
                    "${total.kcal.roundToInt()} kcal · P ${total.protein.roundToInt()} · G ${total.carbs.roundToInt()} · L ${total.fat.roundToInt()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Night.Muted,
                )
            }
            IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, "Ajouter un aliment", tint = Night.Accent) }
        }
        entries.forEach { e ->
            val f = foods[e.foodId]
            if (f != null) EntryRow(e, f, onChange, onRemove)
        }
    }
}

@Composable
private fun EntryRow(e: FoodEntry, f: Food, onChange: (FoodEntry) -> Unit, onRemove: (Long) -> Unit) {
    val n = MealPlan.nutrients(e, f)
    val step = if (e.inUnits) 0.5 else if (f.category == FoodCategory.FAT) 5.0 else 10.0
    Column {
        HorizontalDivider(color = Night.Line)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text(
                    "${n.kcal.roundToInt()} kcal · P ${BodyStats.fmt(n.protein)} · G ${BodyStats.fmt(n.carbs)} · L ${BodyStats.fmt(n.fat)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Night.Muted,
                )
            }
            IconButton(onClick = { onRemove(e.id) }) { Icon(Icons.Filled.Delete, "Retirer", tint = Night.Muted) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (e.quantity - step > 0) onChange(e.copy(quantity = e.quantity - step)) }) { Icon(Icons.Filled.Remove, "Moins") }
            QuantityField(e, Modifier.width(80.dp), onChange)
            IconButton(onClick = { onChange(e.copy(quantity = e.quantity + step)) }) { Icon(Icons.Filled.Add, "Plus") }
            if (f.unitGrams > 0) {
                TextButton(onClick = { onChange(MealPlan.toggleUnits(e, f)) }) {
                    Text(if (e.inUnits) "${f.unitName} (${f.unitGrams.roundToInt()} g)" else "grammes", maxLines = 1)
                }
            } else {
                Text("  g", color = Night.Muted)
            }
        }
    }
}

@Composable
private fun QuantityField(e: FoodEntry, modifier: Modifier, onChange: (FoodEntry) -> Unit) {
    val shown = if (e.quantity % 1.0 == 0.0) e.quantity.roundToInt().toString() else BodyStats.fmt(e.quantity)
    var text by remember(e.id, e.quantity) { mutableStateOf(shown) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v
            v.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it < 5000 }?.let { onChange(e.copy(quantity = it)) }
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.merge(NumberStyle).copy(textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

// --- Choix d'un aliment ---

@Composable
private fun FoodPicker(
    slot: MealSlot,
    customFoods: List<Food>,
    onPick: (Food) -> Unit,
    onCreate: (Food) -> Unit,
    onDeleteCustom: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<FoodCategory?>(null) }
    var creating by remember { mutableStateOf(false) }
    val all = customFoods + MealPlan.catalog
    val shown = all.filter { f ->
        (category == null || f.category == category) && (query.isBlank() || f.name.contains(query.trim(), ignoreCase = true))
    }
    if (creating) {
        CustomFoodDialog(onSave = { onCreate(it); creating = false }, onDismiss = { creating = false })
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajouter · ${slot.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Rechercher") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { FilterChip(selected = category == null, onClick = { category = null }, label = { Text("Tout") }) }
                    items(FoodCategory.entries) { c ->
                        FilterChip(selected = category == c, onClick = { category = if (category == c) null else c }, label = { Text(c.label) })
                    }
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                    items(shown, key = { it.id }) { f ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(f) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(f.name + if (f.custom) " ★" else "", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${f.kcal.roundToInt()} kcal · P ${BodyStats.fmt(f.protein)} · G ${BodyStats.fmt(f.carbs)} · L ${BodyStats.fmt(f.fat)} /100 g" +
                                        (f.unitName?.let { " · 1 $it = ${f.unitGrams.roundToInt()} g" } ?: ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Night.Muted,
                                )
                            }
                            if (f.custom) {
                                IconButton(onClick = { onDeleteCustom(f.id) }) { Icon(Icons.Filled.Close, "Supprimer l'aliment", tint = Night.Muted) }
                            }
                        }
                    }
                    if (shown.isEmpty()) {
                        item { Text("Aucun aliment trouvé.", style = MaterialTheme.typography.bodySmall, color = Night.Muted) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { creating = true }) { Text("Créer un aliment") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomFoodDialog(onSave: (Food) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    var unitName by remember { mutableStateOf("") }
    var unitGrams by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(FoodCategory.OTHER) }
    fun num(s: String) = s.replace(',', '.').toDoubleOrNull()
    val p = num(protein) ?: 0.0
    val c = num(carbs) ?: 0.0
    val l = num(fat) ?: 0.0
    // Calories déduites des macros si non saisies
    val k = num(kcal) ?: (p * 4 + c * 4 + l * 9)
    val valid = name.isNotBlank() && k > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouvel aliment") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(max = 420.dp)) {
                item { OutlinedTextField(name, { name = it }, label = { Text("Nom") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item { Text("Valeurs pour 100 g", style = MaterialTheme.typography.labelSmall, color = Night.Muted) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DialogNumber("Protéines", protein, Modifier.weight(1f)) { protein = it }
                        DialogNumber("Glucides", carbs, Modifier.weight(1f)) { carbs = it }
                        DialogNumber("Lipides", fat, Modifier.weight(1f)) { fat = it }
                    }
                }
                item { DialogNumber("kcal (sinon calculées : ${k.roundToInt()})", kcal, Modifier.fillMaxWidth()) { kcal = it } }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(unitName, { unitName = it }, label = { Text("Unité (option)") }, singleLine = true, modifier = Modifier.weight(1f))
                        DialogNumber("g par unité", unitGrams, Modifier.weight(1f)) { unitGrams = it }
                    }
                }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FoodCategory.entries.forEach { cat ->
                            FilterChip(selected = category == cat, onClick = { category = cat }, label = { Text(cat.label) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val ug = num(unitGrams)?.takeIf { it > 0 && unitName.isNotBlank() } ?: 0.0
                    onSave(
                        Food(
                            id = "custom_${System.currentTimeMillis()}",
                            name = name.trim(),
                            category = category,
                            kcal = k,
                            protein = p,
                            carbs = c,
                            fat = l,
                            unitName = unitName.trim().takeIf { ug > 0 },
                            unitGrams = ug,
                            custom = true,
                        ),
                    )
                },
            ) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun DialogNumber(label: String, value: String, modifier: Modifier, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}
