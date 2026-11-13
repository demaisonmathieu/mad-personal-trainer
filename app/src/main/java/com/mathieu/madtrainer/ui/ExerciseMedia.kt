package com.mathieu.madtrainer.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mathieu.madtrainer.coach.CatalogExercise
import com.mathieu.madtrainer.coach.ExerciseCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Chargement des photos d'exercices : cache mémoire + cache disque (consultables hors ligne une fois vues). */
object ExerciseImages {
    private val memory = LruCache<String, ImageBitmap>(40)

    suspend fun load(context: Context, url: String): ImageBitmap? {
        memory.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "exercises").apply { mkdirs() }
                val file = File(dir, url.substringAfter("/exercises/").replace('/', '_'))
                if (!file.exists() || file.length() == 0L) {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10_000
                        readTimeout = 15_000
                    }
                    try {
                        if (conn.responseCode !in 200..299) return@runCatching null
                        val tmp = File(dir, file.name + ".tmp")
                        conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                        tmp.renameTo(file)
                    } finally {
                        conn.disconnect()
                    }
                }
                BitmapFactory.decodeFile(file.path)?.asImageBitmap()
            }.getOrNull()?.also { memory.put(url, it) }
        }
    }
}

/** Démonstration animée : alterne les photos de début et de fin de mouvement. */
@Composable
fun ExerciseIllustration(ex: CatalogExercise?, modifier: Modifier = Modifier, maxHeight: Dp = 220.dp) {
    val context = LocalContext.current
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .aspectRatio(1.5f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (ex == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.ImageNotSupported, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Pas d'illustration", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            val frames by produceState<List<ImageBitmap>?>(null, ex.id) {
                value = ex.images.mapNotNull { ExerciseImages.load(context, it) }
            }
            var frame by remember(ex.id) { mutableIntStateOf(0) }
            val count = frames?.size ?: 0
            LaunchedEffect(ex.id, count) {
                while (count > 1) {
                    delay(1000)
                    frame = (frame + 1) % count
                }
            }
            val list = frames
            when {
                list == null -> CircularProgressIndicator(Modifier.size(32.dp))
                list.isEmpty() -> Text("Image indisponible (hors ligne ?)", style = MaterialTheme.typography.bodySmall)
                else -> Image(
                    list[frame.coerceIn(0, list.lastIndex)],
                    contentDescription = ex.n,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Ouvre YouTube (appli ou navigateur) sur une recherche de démonstration de l'exercice. */
fun openYoutubeDemo(context: Context, exerciseName: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(ExerciseCatalog.youtubeSearchUrl(exerciseName)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
fun YoutubeDemoButton(exerciseName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    OutlinedButton(onClick = { openYoutubeDemo(context, exerciseName) }, modifier = modifier) {
        Icon(Icons.Filled.PlayCircle, null)
        Text("  Vidéo de démo")
    }
}

/** Choix manuel de l'illustration d'un exercice dans la banque d'images. */
@Composable
fun ExerciseImagePicker(
    catalog: ExerciseCatalog,
    initialQuery: String,
    onPick: (CatalogExercise?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf(initialQuery) }
    var preview by remember { mutableStateOf<CatalogExercise?>(null) }
    val results = remember(query) { catalog.search(query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Illustration") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    query, { query = it },
                    label = { Text("Rechercher (français ou anglais)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                preview?.let { ExerciseIllustration(it, maxHeight = 160.dp) }
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(results, key = { it.id }) { ex ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { preview = ex }
                                .background(if (preview?.id == ex.id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                        ) {
                            Column {
                                Text(ex.n)
                                Text("${ex.m} · ${ex.e}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (results.isEmpty()) Text("Aucun résultat", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(preview) }, enabled = preview != null) { Text("Choisir") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onPick(null) }) { Text("Aucune") }
                TextButton(onClick = onDismiss) { Text("Annuler") }
            }
        },
    )
}
