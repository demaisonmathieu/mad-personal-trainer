package com.mathieu.madtrainer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mathieu.madtrainer.AppViewModel
import com.mathieu.madtrainer.media.MusicPlayer
import com.mathieu.madtrainer.media.SpotifyLinks

/** Musique pendant la séance : titre en cours, commandes, playlist Spotify lancée au démarrage. */
@Composable
fun MusicWidget(vm: AppViewModel, modifier: Modifier = Modifier) {
    val music by MusicPlayer.state.collectAsStateWithLifecycle()
    val settings by vm.settingsStore.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showSettings by rememberSaveable { mutableStateOf(false) }

    // Revérifie l'accès au retour des réglages Android
    LifecycleResumeEffect(Unit) {
        MusicPlayer.connect()
        onPauseOrDispose { }
    }

    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(music.art)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    when {
                        !music.access -> {
                            Text("Musique", fontWeight = FontWeight.SemiBold)
                            Text("Autorise l'accès aux notifications pour piloter Spotify ici.", style = MaterialTheme.typography.bodySmall)
                        }
                        !music.active -> {
                            Text("Aucune musique en cours", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (settings.musicUri.isNotBlank()) "Lance ta playlist ou ouvre Spotify." else "Choisis une playlist (réglages ⚙) ou ouvre Spotify.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        else -> {
                            Text(music.title ?: "—", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(music.artist, music.appName).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                IconButton(onClick = { showSettings = !showSettings }) {
                    Icon(Icons.Filled.Tune, "Réglages musique", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            when {
                !music.access -> Button(onClick = { runCatching { context.startActivity(MusicPlayer.accessIntent()) } }) {
                    Text("Autoriser l'accès")
                }
                !music.active -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (settings.musicUri.isNotBlank()) {
                        Button(onClick = { MusicPlayer.playSpotify(settings.musicUri, settings.musicShuffle) }) {
                            Icon(Icons.AutoMirrored.Filled.QueueMusic, null, Modifier.size(18.dp))
                            Text(" Ma playlist")
                        }
                    }
                    OutlinedButton(onClick = { MusicPlayer.openSpotify() }) { Text("Ouvrir Spotify") }
                }
                else -> Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { MusicPlayer.toggleShuffle() }) {
                        Icon(
                            Icons.Filled.Shuffle,
                            "Aléatoire",
                            tint = if (music.shuffle == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { MusicPlayer.previous() }) { Icon(Icons.Filled.SkipPrevious, "Précédent") }
                    IconButton(onClick = { MusicPlayer.playPause() }) {
                        Icon(
                            if (music.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (music.playing) "Pause" else "Lecture",
                            Modifier.size(36.dp),
                        )
                    }
                    IconButton(onClick = { MusicPlayer.next() }) { Icon(Icons.Filled.SkipNext, "Suivant") }
                    IconButton(
                        onClick = { MusicPlayer.playSpotify(settings.musicUri, settings.musicShuffle) },
                        enabled = settings.musicUri.isNotBlank(),
                    ) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Ma playlist") }
                }
            }

            if (showSettings) MusicSettings(vm)
        }
    }
}

@Composable
private fun Cover(art: android.graphics.Bitmap?) {
    val shape = RoundedCornerShape(8.dp)
    val image = remember(art) { art?.asImageBitmap() }
    if (image != null) {
        Image(image, null, Modifier.size(52.dp).clip(shape), contentScale = ContentScale.Crop)
    } else {
        Box(
            Modifier.size(52.dp).clip(shape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
    }
}

@Composable
private fun MusicSettings(vm: AppViewModel) {
    val settings by vm.settingsStore.settings.collectAsStateWithLifecycle()
    var link by rememberSaveable { mutableStateOf(settings.musicUri) }
    val normalized = SpotifyLinks.normalize(link)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = link,
            onValueChange = { v ->
                link = v
                val uri = SpotifyLinks.normalize(v)
                if (uri != null || v.isBlank()) vm.settingsStore.update { it.copy(musicUri = uri ?: "") }
            },
            label = { Text("Playlist Spotify (lien « Partager »)") },
            singleLine = true,
            isError = link.isNotBlank() && normalized == null,
            supportingText = {
                Text(
                    when {
                        link.isBlank() -> "Dans Spotify : playlist → ⋯ → Partager → Copier le lien"
                        normalized == null -> "Lien non reconnu"
                        else -> "OK : $normalized"
                    },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Lancer au démarrage de la séance", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = settings.musicAutoStart,
                onCheckedChange = { v -> vm.settingsStore.update { it.copy(musicAutoStart = v) } },
                enabled = settings.musicUri.isNotBlank(),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Lecture aléatoire", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = settings.musicShuffle, onCheckedChange = { v -> vm.settingsStore.update { it.copy(musicShuffle = v) } })
        }
        Text(
            "Les annonces du coach vocal baissent la musique le temps de parler.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
