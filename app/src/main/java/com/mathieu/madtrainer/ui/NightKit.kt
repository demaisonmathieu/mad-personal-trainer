package com.mathieu.madtrainer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mathieu.madtrainer.data.Sport

/** En-tête d'écran : petit libellé en capitales au-dessus d'un titre, action éventuelle à droite. */
@Composable
fun ScreenHeader(title: String, eyebrow: String? = null, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            eyebrow?.let { Eyebrow(it) }
            Text(title, style = MaterialTheme.typography.headlineSmall)
        }
        trailing?.invoke()
    }
}

/** Libellé de section en capitales espacées. */
@Composable
fun Eyebrow(text: String, color: Color = Night.Muted, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, letterSpacing = 1.sp, color = color, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

/**
 * Carte Night Track : surface sombre, filet discret ; [highlight] l'entoure d'orange (élément actif / du jour).
 * [onClick] la rend cliquable.
 */
@Composable
fun NightCard(
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = if (highlight) Night.AccentContainer else Night.Surface)
    val border = BorderStroke(1.dp, if (highlight) Night.Accent else Night.Line)
    val inner: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
    if (onClick != null) Card(onClick = onClick, modifier = modifier.fillMaxWidth(), colors = colors, border = border, content = inner)
    else Card(modifier = modifier.fillMaxWidth(), colors = colors, border = border, content = inner)
}

/** Tuile d'icône colorée (sport, catégorie). */
@Composable
fun IconTile(icon: ImageVector, tint: Color, background: Color, size: Int = 44, contentDescription: String? = null) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(background),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription, tint = tint) }
}

@Composable
fun SportBadge(sport: Sport?) = when (sport) {
    Sport.RUNNING -> IconTile(sportIcon(sport), Night.Accent, Night.AccentContainer, contentDescription = sport.label)
    Sport.ELLIPTICAL -> IconTile(sportIcon(sport), CadenceColor, Color(0xFF13243A), contentDescription = sport.label)
    null -> IconTile(Icons.Filled.SelfImprovement, Night.Good, Color(0xFF13241C), contentDescription = "Renfo")
}

/** Bouton « lancer » rond orange. */
@Composable
fun PlayButton(onClick: () -> Unit, label: String = "Lancer", size: Int = 48) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(size.dp),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Night.Accent, contentColor = Night.Bg),
    ) { Icon(Icons.Filled.PlayArrow, label) }
}

/** Petite pastille de texte (statut, étiquette). */
@Composable
fun Pill(text: String, color: Color = Night.Soft, background: Color = Night.Surface2) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(background).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
