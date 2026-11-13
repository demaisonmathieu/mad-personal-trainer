package com.mathieu.madtrainer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mathieu.madtrainer.data.StepMode
import com.mathieu.madtrainer.data.Workout
import com.mathieu.madtrainer.data.WorkoutStep

@Composable
fun MetricTile(
    label: String,
    value: String,
    unit: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    big: Boolean = false,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                Text(
                    "  $label",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    fontSize = if (big) 44.sp else 28.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    " $unit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
    }
}

/** Courbe simple ; les valeurs nulles créent une coupure. */
@Composable
fun LineChart(values: List<Float?>, color: Color, modifier: Modifier = Modifier, maxHint: Float? = null) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val nonNull = values.filterNotNull()
        if (values.size < 2 || nonNull.isEmpty()) return@Canvas
        val max = maxOf(nonNull.max(), maxHint ?: 0f, 1f) * 1.1f
        for (i in 1..3) {
            val y = size.height * i / 4f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        val stepX = size.width / (values.size - 1)
        val path = Path()
        var started = false
        values.forEachIndexed { i, v ->
            if (v == null) {
                started = false
            } else {
                val x = i * stepX
                val y = size.height - (v / max) * size.height
                if (!started) path.moveTo(x, y) else path.lineTo(x, y)
                started = true
            }
        }
        drawPath(path, color, style = Stroke(width = 4f))
    }
}

/** Profil d'une séance : une barre par étape, largeur = durée, hauteur = intensité. */
@Composable
fun WorkoutProfile(
    workout: Workout,
    modifier: Modifier = Modifier,
    currentIndex: Int? = null,
    progressSec: Int? = null,
) {
    val steps = workout.steps
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val cursor = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val total = workout.totalSec.toFloat()
        if (total <= 0f) return@Canvas
        val maxRes = steps.filter { it.mode == StepMode.RESISTANCE }.maxOfOrNull { it.target }?.toFloat() ?: 1f
        val maxPow = steps.filter { it.mode == StepMode.POWER }.maxOfOrNull { it.target }?.toFloat() ?: 1f
        // Allure : plus le nombre de s/km est petit, plus c'est intense
        val minPace = steps.filter { it.mode == StepMode.PACE }.minOfOrNull { it.target }?.toFloat() ?: 300f
        var x = 0f
        steps.forEachIndexed { i, s ->
            val w = size.width * s.durationSec / total
            val ratio = intensity(s, maxRes, maxPow, minPace)
            val h = size.height * (0.15f + 0.85f * ratio)
            drawRect(
                color = if (currentIndex == null || i == currentIndex) active else inactive,
                topLeft = Offset(x, size.height - h),
                size = Size((w - 2f).coerceAtLeast(1f), h),
            )
            x += w
        }
        if (progressSec != null) {
            val cx = size.width * progressSec / total
            drawLine(cursor, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 3f)
        }
    }
}

private fun intensity(s: WorkoutStep, maxRes: Float, maxPow: Float, minPace: Float): Float = when (s.mode) {
    StepMode.FREE -> 0.2f
    StepMode.RESISTANCE -> (s.target.toFloat() / maxRes).coerceIn(0f, 1f)
    StepMode.POWER -> (s.target.toFloat() / maxPow).coerceIn(0f, 1f)
    StepMode.PACE -> (minPace / s.target.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
    StepMode.HR_ZONE -> (s.target.toFloat() / 5f).coerceIn(0f, 1f)
}

fun stepTargetText(s: WorkoutStep): String = when (s.mode) {
    StepMode.FREE -> "Libre"
    StepMode.RESISTANCE -> "Résistance %s".format(fmtNum(s.target))
    StepMode.POWER -> "${s.target.toInt()} W"
    StepMode.PACE -> "Allure ${com.mathieu.madtrainer.data.formatPace(s.target.toInt())}/km"
    StepMode.HR_ZONE -> "Zone ${s.target.toInt()}"
}

fun fmtNum(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v)

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(vertical = 8.dp),
    )
}

@Composable
fun StatRow(items: List<Pair<String, String>>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        items.forEach { (label, value) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun ChartCard(title: String, values: List<Float?>, color: Color) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = color)
            LineChart(values, color, Modifier.fillMaxWidth().height(120.dp).padding(top = 8.dp))
        }
    }
}
