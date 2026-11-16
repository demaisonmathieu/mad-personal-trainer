package com.mathieu.madtrainer.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mathieu.madtrainer.R

/**
 * Thème « Night Track » : sombre en permanence (lisible en plein effort), un seul accent orange réservé aux actions,
 * une couleur fixe par zone cardio, Space Grotesk pour les titres et les chiffres.
 */
object Night {
    val Bg = Color(0xFF0D0F12)
    val Surface = Color(0xFF16191E)
    val Surface2 = Color(0xFF1E2229)
    val Line = Color(0xFF2A2F37)
    val Text = Color(0xFFF2F4F7)
    val Muted = Color(0xFFA3ACB9)
    val Soft = Color(0xFFC9CFD8)
    val Accent = Color(0xFFFF7A1A)
    val AccentText = Color(0xFFFFB27A)
    val AccentContainer = Color(0xFF2A1A0E)
    val Water = Color(0xFF4FC3F7)
    val Good = Color(0xFF3DDC97)
    val Bad = Color(0xFFFF6B81)

    /** Zones 1 à 5 : gris, bleu, vert, jaune, rouge. */
    val Zones = listOf(Color(0xFF6B7A8F), Color(0xFF4FA3FF), Color(0xFF3DDC97), Color(0xFFFFB020), Color(0xFFFF4D6D))
}

fun zoneColor(zone: Int): Color = Night.Zones[(zone - 1).coerceIn(0, 4)]

val PowerColor = Color(0xFFFF8A3D)
val CadenceColor = Color(0xFF4FA3FF)
val HrColor = Color(0xFFFF4D6D)
val ResistanceColor = Color(0xFFB9A6F2)

@OptIn(ExperimentalTextApi::class)
private fun grotesk(w: Int) = Font(R.font.space_grotesk, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

/** Titres et grands chiffres. */
val Display = FontFamily(grotesk(500), grotesk(700))

private val scheme = darkColorScheme(
    primary = Night.Accent,
    onPrimary = Night.Bg,
    primaryContainer = Night.AccentContainer,
    onPrimaryContainer = Night.AccentText,
    secondary = Color(0xFF4FA3FF),
    onSecondary = Night.Bg,
    secondaryContainer = Color(0xFF13243A),
    onSecondaryContainer = Color(0xFFBFDBFF),
    tertiary = Night.Good,
    onTertiary = Night.Bg,
    tertiaryContainer = Color(0xFF13241C),
    onTertiaryContainer = Color(0xFF7DEBB8),
    error = Night.Bad,
    onError = Night.Bg,
    errorContainer = Color(0xFF3A1218),
    onErrorContainer = Color(0xFFFFC2CC),
    background = Night.Bg,
    onBackground = Night.Text,
    surface = Night.Bg,
    onSurface = Night.Text,
    surfaceVariant = Night.Surface2,
    onSurfaceVariant = Night.Muted,
    surfaceTint = Night.Accent,
    outline = Color(0xFF3A414B),
    outlineVariant = Night.Line,
    inverseSurface = Night.Text,
    inverseOnSurface = Night.Bg,
    surfaceContainerLowest = Night.Bg,
    surfaceContainerLow = Color(0xFF111418),
    surfaceContainer = Color(0xFF13161A),
    surfaceContainerHigh = Night.Surface2,
    surfaceContainerHighest = Night.Surface,
    surfaceBright = Night.Surface2,
    surfaceDim = Night.Bg,
)

private val base = Typography()

private fun TextStyle.display(weight: FontWeight = FontWeight.Bold) = copy(fontFamily = Display, fontWeight = weight)

private val typography = base.copy(
    displayLarge = base.displayLarge.display(),
    displayMedium = base.displayMedium.display(),
    displaySmall = base.displaySmall.display(),
    headlineLarge = base.headlineLarge.display(),
    headlineMedium = base.headlineMedium.display(),
    headlineSmall = base.headlineSmall.display(),
    titleLarge = base.titleLarge.display(),
    titleMedium = base.titleMedium.display(FontWeight.Medium),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun MadTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
}
