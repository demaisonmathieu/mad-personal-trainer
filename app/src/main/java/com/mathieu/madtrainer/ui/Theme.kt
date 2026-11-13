package com.mathieu.madtrainer.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val PowerColor = Color(0xFFFF7A1A)
val CadenceColor = Color(0xFF2E9BFF)
val HrColor = Color(0xFFE5395B)
val ResistanceColor = Color(0xFF8E6CF0)

@Composable
fun MadTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFFFF9A4D), secondary = Color(0xFF6FB8FF))
        else -> lightColorScheme(primary = Color(0xFFD65A00), secondary = Color(0xFF0B6FC7))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
