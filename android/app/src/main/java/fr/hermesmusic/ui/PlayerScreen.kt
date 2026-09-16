package fr.hermesmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import fr.hermesmusic.core.AppGraph
import fr.hermesmusic.core.Nocturne
import fr.hermesmusic.core.kicker

/** Libellé de durée à partir de millisecondes. */
fun fmtMs(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/** Lecteur plein écran. Le lecteur réel vit dans le service : ici on ne fait qu'afficher et piloter. */
@Composable
fun PlayerScreen(graph: AppGraph, onClose: () -> Unit) {
    val s by graph.player.state.collectAsStateWithLifecycle()
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var shuffle by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableStateOf(Player.REPEAT_MODE_OFF) }

    val duration = s.durationMs.coerceAtLeast(1)
    val progress = if (dragging) dragValue else (s.positionMs.toFloat() / duration).coerceIn(0f, 1f)

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color(0xFF1B1D2E),
                    0.62f to Nocturne.Bg,
                )
            ),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Réduire",
                        tint = Nocturne.Dim,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text("LECTURE", style = kicker(), color = Nocturne.Dim2)
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(48.dp))
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 34.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(0.86f)
                        .aspectRatio(1f)
                        .clip(CircleShape)
                        .background(Nocturne.Surface2),
                    contentAlignment = Alignment.Center,
                ) {
                    if (s.artworkUrl != null) {
                        AsyncImage(
                            model = s.artworkUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                Spacer(Modifier.height(30.dp))
                Text(
                    s.title.ifBlank { "—" },
                    color = Nocturne.Ink,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    s.artist,
                    color = Nocturne.Dim,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )

                Spacer(Modifier.height(26.dp))
                Slider(
                    value = progress,
                    onValueChange = { dragging = true; dragValue = it },
                    onValueChangeFinished = {
                        graph.player.seekTo((dragValue * duration).toLong())
                        dragging = false
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Nocturne.Ink,
                        activeTrackColor = Nocturne.Accent,
                        inactiveTrackColor = Color(0x24E9E9ED),
                    ),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(fmtMs(if (dragging) (dragValue * duration).toLong() else s.positionMs), color = Nocturne.Dim2, fontSize = 11.sp)
                    Text(fmtMs(s.durationMs), color = Nocturne.Dim2, fontSize = 11.sp)
                }

                if (s.isBuffering) {
                    Spacer(Modifier.height(10.dp))
                    Text("Mise en mémoire tampon…", style = kicker(), color = Nocturne.Accent)
                }

                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    PlayerButton(
                        icon = Icons.Filled.Shuffle,
                        description = "Aléatoire",
                        active = shuffle,
                        small = true,
                        onClick = {
                            shuffle = !shuffle
                            graph.player.setShuffle(shuffle)
                        },
                    )
                    PlayerButton(Icons.Filled.SkipPrevious, "Précédent") { graph.player.previous() }
                    PlayPauseButton(s.isPlaying) { graph.player.togglePlayPause() }
                    PlayerButton(Icons.Filled.SkipNext, "Suivant") { graph.player.next() }
                    PlayerButton(
                        icon = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        description = "Répéter",
                        active = repeatMode != Player.REPEAT_MODE_OFF,
                        small = true,
                        onClick = {
                            repeatMode = when (repeatMode) {
                                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                else -> Player.REPEAT_MODE_OFF
                            }
                            graph.player.setRepeatMode(repeatMode)
                        },
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 26.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    if (s.queueSize > 1) "${s.queueSize} morceaux dans la file" else "1 morceau dans la file",
                    style = kicker(),
                    color = Nocturne.Dim2,
                )
            }
        }
    }
}

@Composable
private fun PlayPauseButton(isPlaying: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(66.dp)
            .clip(CircleShape)
            .background(Nocturne.Accent)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Lecture",
            tint = Color(0xFF0E0F18),
            modifier = Modifier.size(30.dp),
        )
    }
}

@Composable
private fun PlayerButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    active: Boolean = false,
    small: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(if (small) 46.dp else 54.dp)
            .clip(CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (active) Nocturne.Accent else Nocturne.Ink,
            modifier = Modifier.size(if (small) 20.dp else 28.dp),
        )
    }
}
