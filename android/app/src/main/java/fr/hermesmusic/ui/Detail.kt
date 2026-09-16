package fr.hermesmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.hermesmusic.core.AppGraph
import fr.hermesmusic.core.Nocturne
import fr.hermesmusic.core.kicker
import fr.hermesmusic.network.JfItem
import kotlinx.coroutines.launch

/* ------------------------------------------------------------------ */
/* Éléments communs aux pages de détail                                 */
/* ------------------------------------------------------------------ */

@Composable
fun DetailBar(
    label: String,
    onBack: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable { onBack() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Retour",
                tint = Nocturne.Ink,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Text(label, style = kicker(), color = Nocturne.Dim2)
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { trailing?.invoke() }
    }
}

/** Grand bouton de lecture rond : on le vise sans regarder. */
@Composable
fun BigPlayButton(
    description: String = "Lecture",
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(58.dp)
            .clip(CircleShape)
            .background(if (enabled) Nocturne.Accent else Nocturne.Surface2)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = description,
            tint = if (enabled) Nocturne.OnAccent else Nocturne.Dim2,
            modifier = Modifier.size(30.dp),
        )
    }
}

/**
 * Rangée d'actions d'une page de détail : les actions secondaires à gauche,
 * puis lecture aléatoire et le grand bouton lecture à droite.
 */
@Composable
fun DetailActions(
    enabled: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    leading: @Composable () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.weight(1f))
        ActionIcon(
            icon = Icons.Filled.Shuffle,
            description = "Aléatoire",
            tint = Nocturne.Ink,
            enabled = enabled,
        ) { onShuffle() }
        Spacer(Modifier.width(6.dp))
        BigPlayButton(enabled = enabled) { onPlay() }
    }
}

/** Lance une liste en lecture aléatoire (mélange activé + départ au hasard). */
fun playShuffled(graph: AppGraph, tracks: List<JfItem>) {
    if (tracks.isEmpty()) return
    graph.play(tracks, tracks.indices.random())
    graph.player.setShuffle(true)
}

private fun totalMinutes(tracks: List<JfItem>): Int = tracks.sumOf { it.durationSeconds } / 60

/** Ligne de morceau numérotée (page album / artiste) : pas de pochette répétée. */
@Composable
private fun NumberedRow(
    track: JfItem,
    number: Int,
    playing: Boolean,
    downloaded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "%02d".format(number),
            color = if (playing) Nocturne.Accent else Nocturne.Dim2,
            fontSize = 11.5.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(28.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                track.Name ?: "",
                color = if (playing) Nocturne.Accent else Nocturne.Ink,
                fontSize = 14.sp,
                maxLines = 1,
            )
            Text(track.artistLine, color = Nocturne.Dim, fontSize = 11.5.sp, maxLines = 1)
        }
        if (downloaded) {
            Text("↓", color = Nocturne.Dim2, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
        }
        Text(fmtDuration(track.durationSeconds), color = Nocturne.Dim2, fontSize = 11.5.sp)
    }
}

/* ------------------------------------------------------------------ */
/* Page ALBUM                                                           */
/* ------------------------------------------------------------------ */

@Composable
fun AlbumDetail(graph: AppGraph, albumId: String, onBack: () -> Unit) {
    var album by remember(albumId) { mutableStateOf<JfItem?>(null) }
    var tracks by remember(albumId) { mutableStateOf<List<JfItem>?>(null) }
    var favorite by remember(albumId) { mutableStateOf(false) }
    var addingToPlaylist by remember(albumId) { mutableStateOf(false) }
    val player by graph.player.state.collectAsStateWithLifecycle()
    val downloads by graph.downloadEntries.collectAsStateWithLifecycle()
    val downloadProgress by graph.downloadProgress.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(albumId) {
        runCatching { graph.repo.item(albumId) }.onSuccess {
            album = it
            favorite = it.isFavorite
        }
        tracks = runCatching { graph.repo.albumTracks(albumId).Items }.getOrDefault(emptyList())
    }

    val list = tracks ?: emptyList()
    val downloadedIds = downloads.map { it.itemId }.toSet()
    val downloadedCount = list.count { it.Id in downloadedIds }
    val allDownloaded = list.isNotEmpty() && downloadedCount == list.size
    val meta = buildString {
        album?.ProductionYear?.let { append("$it · ") }
        append("${list.size} morceaux")
        val minutes = totalMinutes(list)
        if (minutes > 0) append(" · $minutes min")
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(rememberArtworkBrush(graph, album?.let { graph.repo.imageUrl(it, 400) })),
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Column(Modifier.fillMaxWidth()) {
                    DetailBar(label = "ALBUM", onBack = onBack)

                    Box(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        album?.let { Cover(graph, it, 200.dp) }
                    }

                    Spacer(Modifier.height(20.dp))
                    Text(
                        album?.Name ?: "…",
                        color = Nocturne.Ink,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        album?.AlbumArtist ?: album?.artistLine ?: "",
                        color = Nocturne.Dim,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        meta,
                        style = kicker(),
                        color = Nocturne.Dim2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    DetailActions(
                        enabled = list.isNotEmpty(),
                        onPlay = { graph.play(list, 0) },
                        onShuffle = { playShuffled(graph, list) },
                        leading = {
                            Row {
                                ActionIcon(
                                    icon = if (favorite) {
                                        Icons.Filled.Favorite
                                    } else {
                                        Icons.Filled.FavoriteBorder
                                    },
                                    description = if (favorite) {
                                        "Retirer des favoris"
                                    } else {
                                        "Ajouter aux favoris"
                                    },
                                    tint = if (favorite) Nocturne.Accent else Nocturne.Dim,
                                ) {
                                    val target = !favorite
                                    favorite = target
                                    scope.launch {
                                        runCatching { graph.repo.setFavorite(albumId, target) }
                                    }
                                }
                                ActionIcon(
                                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                                    description = "Ajouter à une playlist",
                                    enabled = list.isNotEmpty(),
                                ) { addingToPlaylist = true }
                                ActionIcon(
                                    icon = Icons.Filled.FileDownload,
                                    description = if (allDownloaded) {
                                        "Album déjà téléchargé"
                                    } else {
                                        "Télécharger l'album"
                                    },
                                    tint = if (allDownloaded) Nocturne.Accent else Nocturne.Dim,
                                    enabled = list.isNotEmpty() && !allDownloaded,
                                ) { graph.download(list) }
                            }
                        },
                    )

                    when {
                        downloadProgress.running -> Text(
                            "Téléchargement ${downloadProgress.done}/${downloadProgress.total} · " +
                                downloadProgress.currentTitle,
                            style = kicker(),
                            color = Nocturne.Accent,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        )

                        allDownloaded -> Text(
                            "Album disponible hors-ligne",
                            style = kicker(),
                            color = Nocturne.Dim2,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        downloadedCount > 0 -> Text(
                            "$downloadedCount/${list.size} morceaux hors-ligne",
                            style = kicker(),
                            color = Nocturne.Dim2,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                }
            }

            if (tracks == null) item { LoadingState() }

            itemsIndexed(list, key = { _, it -> it.Id }) { index, track ->
                NumberedRow(
                    track = track,
                    number = index + 1,
                    playing = player.itemId == track.Id,
                    downloaded = track.Id in downloadedIds,
                ) { graph.play(list, index) }
            }
        }

        if (addingToPlaylist) {
            AddToPlaylistOverlay(
                graph = graph,
                itemIds = list.map { it.Id },
                itemLabel = "Album : ${album?.Name ?: ""} · ${list.size} morceaux",
                onClose = { addingToPlaylist = false },
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* Page ARTISTE                                                         */
/* ------------------------------------------------------------------ */

@Composable
fun ArtistDetail(graph: AppGraph, artistId: String, onBack: () -> Unit) {
    var artist by remember(artistId) { mutableStateOf<JfItem?>(null) }
    var albums by remember(artistId) { mutableStateOf<List<JfItem>?>(null) }
    var tracks by remember(artistId) { mutableStateOf<List<JfItem>?>(null) }
    var addingToPlaylist by remember(artistId) { mutableStateOf(false) }
    val player by graph.player.state.collectAsStateWithLifecycle()
    val downloads by graph.downloadEntries.collectAsStateWithLifecycle()

    LaunchedEffect(artistId) {
        artist = runCatching { graph.repo.item(artistId) }.getOrNull()
        albums = runCatching { graph.repo.artistAlbums(artistId).Items }.getOrDefault(emptyList())
        tracks = runCatching { graph.repo.artistTracks(artistId).Items }.getOrDefault(emptyList())
    }

    val albumList = albums ?: emptyList()
    val trackList = tracks ?: emptyList()
    val downloadedIds = downloads.map { it.itemId }.toSet()
    // Beaucoup d'artistes n'ont pas de photo : on emprunte la pochette du
    // premier album plutôt que d'afficher un rond vide.
    val imageItem = artist?.takeIf { it.hasCover } ?: albumList.firstOrNull { it.hasCover }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                rememberArtworkBrush(graph, imageItem?.let { graph.repo.imageUrl(it, 400) })
            ),
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Column(Modifier.fillMaxWidth()) {
                    DetailBar(label = "ARTISTE", onBack = onBack)

                    Box(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (imageItem != null) {
                            Cover(graph, imageItem, 152.dp, round = true)
                        } else {
                            Box(
                                Modifier
                                    .size(152.dp)
                                    .clip(CircleShape)
                                    .background(Nocturne.Surface2),
                            )
                        }
                    }

                    Spacer(Modifier.height(18.dp))
                    Text(
                        artist?.Name ?: "…",
                        color = Nocturne.Ink,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "${albumList.size} albums · ${trackList.size} titres",
                        style = kicker(),
                        color = Nocturne.Dim2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    DetailActions(
                        enabled = trackList.isNotEmpty(),
                        onPlay = { graph.play(trackList, 0) },
                        onShuffle = { playShuffled(graph, trackList) },
                        leading = {
                            Row {
                                ActionIcon(
                                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                                    description = "Ajouter à une playlist",
                                    enabled = trackList.isNotEmpty(),
                                ) { addingToPlaylist = true }
                                ActionIcon(
                                    icon = Icons.Filled.FileDownload,
                                    description = "Télécharger les titres",
                                    enabled = trackList.isNotEmpty(),
                                ) { graph.download(trackList) }
                            }
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }

            if (albums == null && tracks == null) item { LoadingState() }

            if (albumList.isNotEmpty()) {
                item { SectionHeader("Albums") }
                itemsIndexed(albumList, key = { _, it -> "a" + it.Id }) { _, a ->
                    TrackRow(graph, a, subtitle = albumSubtitle(a)) {
                        graph.openAlbum(a.Id, a.Name ?: "")
                    }
                }
            }

            if (trackList.isNotEmpty()) {
                item { SectionHeader("Titres") }
                itemsIndexed(trackList, key = { _, it -> "t" + it.Id }) { index, t ->
                    TrackRow(
                        graph,
                        t,
                        playing = player.itemId == t.Id,
                        downloaded = t.Id in downloadedIds,
                    ) { graph.play(trackList, index) }
                }
            }

            if (albums != null && tracks != null && albumList.isEmpty() && trackList.isEmpty()) {
                item { EmptyState("Rien à afficher", "Aucun album ni titre pour cet artiste.") }
            }
        }

        if (addingToPlaylist) {
            AddToPlaylistOverlay(
                graph = graph,
                itemIds = trackList.map { it.Id },
                itemLabel = "Artiste : ${artist?.Name ?: ""} · ${trackList.size} titres",
                onClose = { addingToPlaylist = false },
            )
        }
    }
}

/** « 2026 · 12 morceaux », avec repli sur le nom d'artiste. */
private fun albumSubtitle(a: JfItem): String {
    val parts = mutableListOf<String>()
    a.ProductionYear?.let { parts += "$it" }
    a.ChildCount?.let { if (it > 0) parts += "$it morceaux" }
    return parts.joinToString(" · ").ifBlank { a.AlbumArtist ?: a.artistLine }
}
