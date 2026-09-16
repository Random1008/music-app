package fr.hermesmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import fr.hermesmusic.core.AppGraph
import fr.hermesmusic.core.Detail
import fr.hermesmusic.core.Nocturne
import fr.hermesmusic.core.kicker

private data class Tab(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

/**
 * Coque de l'application : trois onglets et un mini-lecteur permanent.
 *
 * Trois onglets plutôt que quatre : les playlists et les favoris vivent
 * maintenant dans la Bibliothèque, à côté des albums — c'est là qu'on les
 * cherche, et ça laisse la barre du bas respirer.
 */
@Composable
fun Shell(graph: AppGraph) {
    var tab by remember { mutableIntStateOf(0) }
    var showPlayer by remember { mutableStateOf(false) }
    val player by graph.player.state.collectAsStateWithLifecycle()
    val detail by graph.detail.collectAsStateWithLifecycle()

    val tabs = listOf(
        Tab("Accueil", Icons.Filled.Home),
        Tab("Recherche", Icons.Filled.Search),
        Tab("Bibliothèque", Icons.Filled.LibraryMusic),
    )

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Nocturne.Bg,
            bottomBar = {
                Column {
                    if (player.hasItem) {
                        MiniPlayer(graph) { showPlayer = true }
                    }
                    NavigationBar(containerColor = Nocturne.Bg, tonalElevation = 0.dp) {
                        tabs.forEachIndexed { index, item ->
                            NavigationBarItem(
                                selected = tab == index,
                                onClick = { tab = index },
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = {
                                    Text(
                                        item.label,
                                        fontSize = 10.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Nocturne.Ink,
                                    selectedTextColor = Nocturne.Ink,
                                    unselectedIconColor = Nocturne.Dim2,
                                    unselectedTextColor = Nocturne.Dim2,
                                    indicatorColor = Nocturne.Accent,
                                ),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (tab) {
                    0 -> HomeTab(graph) { tab = 1 }
                    1 -> SearchTab(graph)
                    else -> LibraryTab(graph)
                }
            }
        }

        // Écran empilé (album, artiste, playlist, paramètres, hors-ligne).
        when (val d = detail) {
            is Detail.Album -> AlbumDetail(graph, d.id) { graph.closeDetail() }
            is Detail.Artist -> ArtistDetail(graph, d.id) { graph.closeDetail() }
            is Detail.Playlist -> PlaylistDetail(graph, d.id, d.title) { graph.closeDetail() }
            is Detail.Settings -> SettingsScreen(graph) { graph.closeDetail() }
            is Detail.Downloads -> DownloadsScreen(graph) { graph.closeDetail() }
            null -> Unit
        }

        // Lecteur plein écran par-dessus tout le reste (il ne remplace pas les
        // écrans : le lecteur continue de tourner de toute façon).
        if (showPlayer && player.hasItem) {
            PlayerScreen(graph) { showPlayer = false }
        }
    }
}

/** Mini-lecteur persistant : jaquette, titre, artiste, lecture/pause, suivant. */
@Composable
private fun MiniPlayer(graph: AppGraph, onOpen: () -> Unit) {
    val s by graph.player.state.collectAsStateWithLifecycle()
    val progress = if (s.durationMs > 0) {
        (s.positionMs.toFloat() / s.durationMs).coerceIn(0f, 1f)
    } else 0f

    Column(
        Modifier
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Nocturne.Surface2)
            .clickable { onOpen() },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Nocturne.Surface),
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
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (s.fromDevice) "↓ ${s.title}" else s.title,
                    color = Nocturne.Ink,
                    fontSize = 13.sp,
                    maxLines = 1,
                )
                Text(s.artist, color = Nocturne.Dim, fontSize = 11.5.sp, maxLines = 1)
            }
            ActionIcon(
                icon = if (s.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                description = if (s.isPlaying) "Pause" else "Lecture",
                tint = Nocturne.Ink,
                boxSize = 44.dp,
                iconSize = 22.dp,
            ) { graph.player.togglePlayPause() }
            ActionIcon(
                icon = Icons.Filled.SkipNext,
                description = "Suivant",
                tint = Nocturne.Ink,
                boxSize = 44.dp,
                iconSize = 22.dp,
            ) { graph.player.next() }
        }
        // Ligne de progression (2 px, comme dans la maquette)
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(Nocturne.Surface),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .height(2.dp)
                    .background(Nocturne.Accent),
            )
        }
    }
}

/* ---------------- éléments réutilisables ---------------- */

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, color = Nocturne.Ink, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        if (action != null && onAction != null) {
            Text(
                action,
                style = kicker(),
                color = Nocturne.Dim2,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun EmptyState(title: String, detail: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 42.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Nocturne.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text(detail, color = Nocturne.Dim, fontSize = 12.5.sp)
    }
}

@Composable
fun LoadingState() {
    Box(Modifier.fillMaxWidth().height(120.dp), Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator(
            color = Nocturne.Accent,
            strokeWidth = 2.dp,
        )
    }
}
