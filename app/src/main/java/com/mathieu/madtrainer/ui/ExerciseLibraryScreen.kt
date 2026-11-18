package com.mathieu.madtrainer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mathieu.madtrainer.coach.CatalogExercise
import com.mathieu.madtrainer.coach.ExerciseCatalog
import com.mathieu.madtrainer.coach.ExerciseFilter
import com.mathieu.madtrainer.coach.ExerciseLabels
import com.mathieu.madtrainer.coach.ExerciseLibrary
import com.mathieu.madtrainer.coach.MuscleGroup
import com.mathieu.madtrainer.coach.StrengthProfile

/**
 * Banque d'exercices plein écran : recherche (français ou anglais), filtres par muscle, catégorie, matériel,
 * niveau, type de mouvement ; fiche détaillée avec alternatives et exercices complémentaires.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseLibraryScreen(
    catalog: ExerciseCatalog,
    profile: StrengthProfile,
    addedIds: Set<String>,
    onAdd: (CatalogExercise) -> Unit,
    onClose: () -> Unit,
) {
    val myEquipment = remember(profile.equipment) { ExerciseCatalog.allowedEquipment(profile.equipment) }
    var filter by remember { mutableStateOf(ExerciseFilter(equipment = myEquipment, categories = setOf("strength"))) }
    var showMore by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<CatalogExercise?>(null) }
    val results = remember(filter) { ExerciseLibrary.filter(catalog, filter) }

    detail?.let { ex ->
        ExerciseDetail(
            ex = ex,
            catalog = catalog,
            allowed = filter.equipment,
            added = ex.id in addedIds,
            onAdd = { onAdd(ex) },
            onOpen = { detail = it },
            onBack = { detail = null },
        )
        return
    }
    BackHandler { onClose() }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        ScreenHeader("Banque d'exercices", eyebrow = "${results.size} résultat(s)" + if (addedIds.isNotEmpty()) " · ${addedIds.size} dans la séance" else "") {
            Button(onClick = onClose, colors = ButtonDefaults.buttonColors(containerColor = Night.Accent, contentColor = Night.Bg)) { Text("Terminé") }
        }
        OutlinedTextField(
            filter.query, { filter = filter.copy(query = it) },
            placeholder = { Text("Rechercher : squat, gainage, dumbbell…") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (filter.query.isNotEmpty()) IconButton(onClick = { filter = filter.copy(query = "") }) { Icon(Icons.Filled.Close, "Effacer") }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ChipRow {
            MuscleGroup.entries.forEach { g ->
                FilterChip(g in filter.groups, { filter = filter.copy(groups = filter.groups.toggle(g)) }, label = { Text(g.label) })
            }
        }
        ChipRow {
            FilterChip(
                filter.equipment == myEquipment,
                { filter = filter.copy(equipment = if (filter.equipment == myEquipment) null else myEquipment) },
                label = { Text("Mon matériel") },
            )
            listOf("strength", "stretching", "plyometrics", "cardio").forEach { c ->
                FilterChip(c in filter.categories, { filter = filter.copy(categories = filter.categories.toggle(c)) }, label = { Text(ExerciseLabels.category(c)) })
            }
            FilterChip(
                showMore, { showMore = !showMore },
                label = { Text("Plus de filtres" + extraCount(filter).let { if (it > 0) " ($it)" else "" }) },
                leadingIcon = { Icon(Icons.Filled.Tune, null) },
            )
        }
        if (showMore) MoreFilters(filter, onChange = { filter = it })

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(results, key = { it.id }) { ex ->
                ExerciseRow(ex, added = ex.id in addedIds, onOpen = { detail = ex }, onAdd = { onAdd(ex) })
            }
            if (results.isEmpty()) {
                item {
                    Column(Modifier.padding(vertical = 24.dp)) {
                        Text("Aucun exercice ne correspond.", fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { filter = ExerciseFilter(query = filter.query) }) { Text("Retirer tous les filtres") }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun extraCount(f: ExerciseFilter) =
    listOf(f.levels, f.forces, f.mechanics).count { it.isNotEmpty() } + (if (f.includeSecondary) 1 else 0)

private fun <T> Set<T>.toggle(v: T): Set<T> = if (v in this) this - v else this + v

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MoreFilters(filter: ExerciseFilter, onChange: (ExerciseFilter) -> Unit) {
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Eyebrow("Matériel")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ExerciseLabels.EQUIPMENT.forEach { (key, label) ->
                val selected = filter.equipment?.contains(key) ?: false
                FilterChip(selected, {
                    val current = filter.equipment ?: emptySet()
                    val next = current.toggle(key)
                    onChange(filter.copy(equipment = next.ifEmpty { null }))
                }, label = { Text(label) })
            }
        }
        Eyebrow("Niveau")
        ChipRow {
            ExerciseLabels.LEVELS.forEach { (k, label) ->
                FilterChip(k in filter.levels, { onChange(filter.copy(levels = filter.levels.toggle(k))) }, label = { Text(label) })
            }
        }
        Eyebrow("Mouvement")
        ChipRow {
            ExerciseLabels.MECHANICS.forEach { (k, label) ->
                FilterChip(k in filter.mechanics, { onChange(filter.copy(mechanics = filter.mechanics.toggle(k))) }, label = { Text(label) })
            }
            ExerciseLabels.FORCES.forEach { (k, label) ->
                FilterChip(k in filter.forces, { onChange(filter.copy(forces = filter.forces.toggle(k))) }, label = { Text(label) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Compter aussi les muscles secondaires", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Switch(filter.includeSecondary, { onChange(filter.copy(includeSecondary = it)) })
        }
        HorizontalDivider(color = Night.Line)
    }
}

@Composable
private fun ExerciseRow(ex: CatalogExercise, added: Boolean, onOpen: () -> Unit, onAdd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(ex.label, style = MaterialTheme.typography.titleSmall)
            Text(
                listOf(ExerciseLabels.muscle(ex.m), ExerciseLabels.equipment(ex.e), ExerciseLabels.level(ex.l)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Night.Muted,
            )
        }
        IconButton(onClick = onAdd) {
            if (added) Icon(Icons.Filled.Check, "Déjà dans la séance (ajouter encore)", tint = Night.Good)
            else Icon(Icons.Filled.Add, "Ajouter", tint = Night.Accent)
        }
    }
}

/** Fiche d'un exercice : démonstration, muscles, consignes, alternatives et associations. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExerciseDetail(
    ex: CatalogExercise,
    catalog: ExerciseCatalog,
    allowed: Set<String>?,
    added: Boolean,
    onAdd: () -> Unit,
    onOpen: (CatalogExercise) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }
    val alternatives = remember(ex.id, allowed) { ExerciseLibrary.alternatives(catalog, ex, allowed) }
    val complements = remember(ex.id, allowed) { ExerciseLibrary.complements(catalog, ex, allowed) }
    var showText by remember(ex.id) { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
            Column(Modifier.weight(1f)) {
                Text(ex.label, style = MaterialTheme.typography.titleLarge)
                if (ex.fr != null) Text(ex.n, style = MaterialTheme.typography.bodySmall, color = Night.Muted)
            }
        }
        ExerciseIllustration(ex)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(ExerciseLabels.category(ex.c))
            Pill(ExerciseLabels.level(ex.l))
            Pill(ExerciseLabels.equipment(ex.e))
            ex.k?.let { Pill(ExerciseLabels.MECHANICS[it] ?: it) }
            ex.f?.let { Pill(ExerciseLabels.FORCES[it] ?: it) }
        }
        Text(
            "Muscle principal : " + ExerciseLabels.muscle(ex.m) +
                if (ex.s.isNotEmpty()) "\nSecondaires : " + ex.s.joinToString { ExerciseLabels.muscle(it) } else "",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onAdd, colors = ButtonDefaults.buttonColors(containerColor = Night.Accent, contentColor = Night.Bg)) {
                Icon(if (added) Icons.Filled.Check else Icons.Filled.Add, null)
                Text(if (added) "  Ajouter encore" else "  Ajouter à la séance")
            }
            YoutubeDemoButton(ex.label)
        }
        if (ex.d.isNotBlank()) {
            TextButton(onClick = { showText = !showText }) { Text(if (showText) "Masquer les consignes" else "Consignes") }
            if (showText) Text(ex.d, style = MaterialTheme.typography.bodySmall, color = Night.Soft)
        }
        if (complements.isNotEmpty()) {
            Eyebrow("Se marie bien avec (muscles opposés)")
            complements.forEach { ExerciseLink(it, onOpen) }
        }
        if (alternatives.isNotEmpty()) {
            Eyebrow("Peut le remplacer")
            alternatives.forEach { ExerciseLink(it, onOpen) }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ExerciseLink(ex: CatalogExercise, onOpen: (CatalogExercise) -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onOpen(ex) }.padding(vertical = 4.dp)) {
        Text(ex.label, color = Night.AccentText)
        Text(
            "${ExerciseLabels.muscle(ex.m)} · ${ExerciseLabels.equipment(ex.e)} · ${ExerciseLabels.level(ex.l)}",
            style = MaterialTheme.typography.bodySmall,
            color = Night.Muted,
        )
    }
}
