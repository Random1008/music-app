package fr.hermesmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import fr.hermesmusic.core.AppGraph
import fr.hermesmusic.core.Nocturne
import fr.hermesmusic.core.kicker
import fr.hermesmusic.network.JfItem
import kotlinx.coroutines.launch

/** Pochette distante, taille plafonnée (jamais de pleine résolution). */
@Composable
fun Cover(graph: AppGraph, item: JfItem, size: Dp, round: Boolean = false) {
    val shape = if (round) CircleShape else RoundedCornerShape(8.dp)
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(Nocturne.Surface2),
    ) {
        val url = graph.repo.imageUrl(item, maxHeight = (size.value * 2).toInt())
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun HomeTab(graph: AppGraph) {
    var albums by remember { mutableStateOf<List<JfItem>?>(null) }
    var tracks by remember { mutableStateOf<List<JfItem>?>(null) }
    var counts by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        runCatching {
            val a = graph.repo.recentAlbums(limit = 12)
            val t = graph.repo.recentTracks(limit = 10)
            val totalAlbums = graph.repo.albums(limit = 1).TotalRecordCount
            val totalTracks = graph.repo.tracks(limit = 1).TotalRecordCount
            val totalArtists = graph.repo.artists(limit = 1).TotalRecordCount
            albums = a.Items
            tracks = t.Items
            counts = "$totalTracks morceaux · $totalAlbums albums · $totalArtists artistes"
        }.onFailure { error = it.message ?: "Erreur réseau" }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 18.dp)) {
        item {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp)) {
                Text("Bonsoir", color = Nocturne.Dim, fontSize = 12.sp)
                Text(
                    "Ta bibliothèque",
                    color = Nocturne.Ink,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (counts.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(counts, style = kicker(), color = Nocturne.Accent)
                }
            }
        }

        error?.let { item { EmptyState("Bibliothèque indisponible", it) } }
        if (albums == null && error == null) item { LoadingState() }

        albums?.takeIf { it.isNotEmpty() }?.let { list ->
            item { SectionHeader("Albums récents") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(list) { _, album ->
                        Column(
                            Modifier
                                .width(132.dp)
                                .clickable { playAlbum(graph, scope, album) },
                        ) {
                            Cover(graph, album, 132.dp)
                            Spacer(Modifier.height(9.dp))
                            Text(album.Name ?: "", color = Nocturne.Ink, fontSize = 13.sp, maxLines = 1)
                            Text(
                                album.AlbumArtist ?: album.artistLine,
                                color = Nocturne.Dim,
                                fontSize = 11.5.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        tracks?.takeIf { it.isNotEmpty() }?.let { list ->
            item { SectionHeader("Récemment ajouté") }
            itemsIndexed(list) { index, track ->
                TrackRow(graph, track) { graph.play(list, index) }
            }
        }
    }
}

/** Ouvre un album : récupère ses pistes puis lance la lecture depuis la première. */
fun playAlbum(graph: AppGraph, scope: kotlinx.coroutines.CoroutineScope, album: JfItem) {
    scope.launch {
        val tracks = runCatching { graph.repo.albumTracks(album.Id).Items }.getOrDefault(emptyList())
        if (tracks.isNotEmpty()) graph.play(tracks, 0)
    }
}

@Composable
fun TrackRow(
    graph: AppGraph,
    track: JfItem,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(graph, track, 46.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.Name ?: "", color = Nocturne.Ink, fontSize = 14.sp, maxLines = 1)
            Text(
                subtitle ?: "${track.artistLine}${track.Album?.let { " · $it" } ?: ""}",
                color = Nocturne.Dim,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
        Text(fmtDuration(track.durationSeconds), color = Nocturne.Dim2, fontSize = 11.5.sp)
    }
}

@Composable
fun SearchTab(graph: AppGraph) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<JfItem>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            results = emptyList()
            searched = false
            return@LaunchedEffect
        }
        busy = true
        kotlinx.coroutines.delay(280)
        runCatching { graph.repo.search(query.trim()).Items }
            .onSuccess { results = it }
            .onFailure { results = emptyList() }
        searched = true
        busy = false
    }

    Column(Modifier.fillMaxSize()) {
        androidx.compose.material3.OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Morceaux, albums, artistes…", color = Nocturne.Dim2, fontSize = 14.sp) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Nocturne.Surface2,
                unfocusedContainerColor = Nocturne.Surface2,
                focusedBorderColor = Nocturne.Accent,
                unfocusedBorderColor = Color(0x24E9E9ED),
                focusedTextColor = Nocturne.Ink,
                unfocusedTextColor = Nocturne.Ink,
                cursorColor = Nocturne.Accent,
            ),
        )

        when {
            busy -> LoadingState()
            query.trim().length < 2 -> EmptyState(
                "Cherche dans ta musique",
                "Titres, albums et artistes de ta bibliothèque Jellyfin.",
            )

            results.isEmpty() && searched -> EmptyState("Aucun résultat", "Rien ne correspond à « $query ».")
            else -> {
                val songs = results.filter { it.Type == "Audio" }
                val albums = results.filter { it.Type == "MusicAlbum" }
                val artists = results.filter { it.Type == "MusicArtist" }
                val scope = rememberCoroutineScope()
                LazyColumn(Modifier.fillMaxSize()) {
                    if (songs.isNotEmpty()) {
                        item { SectionHeader("${songs.size} morceaux") }
                        itemsIndexed(songs) { index, t -> TrackRow(graph, t) { graph.play(songs, index) } }
                    }
                    if (albums.isNotEmpty()) {
                        item { SectionHeader("${albums.size} albums") }
                        itemsIndexed(albums) { _, a ->
                            TrackRow(graph, a, subtitle = a.AlbumArtist ?: a.artistLine) {
                                playAlbum(graph, scope, a)
                            }
                        }
                    }
                    if (artists.isNotEmpty()) {
                        item { SectionHeader("${artists.size} artistes") }
                        itemsIndexed(artists) { _, a -> TrackRow(graph, a, subtitle = "Artiste") }
                    }
                }
            }
        }
    }
}

@Composable
fun LibraryTab(graph: AppGraph) {
    var mode by remember { mutableStateOf(0) }
    var items by remember { mutableStateOf<List<JfItem>?>(null) }
    val scope = rememberCoroutineScope()
    val labels = listOf("Albums", "Artistes", "Morceaux")

    LaunchedEffect(mode) {
        items = null
        items = runCatching {
            when (mode) {
                0 -> graph.repo.albums(limit = 60).Items
                1 -> graph.repo.artists(limit = 60).Items
                else -> graph.repo.tracks(limit = 60).Items
            }
        }.getOrDefault(emptyList())
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            labels.forEachIndexed { index, label ->
                Text(
                    label,
                    color = if (mode == index) Color(0xFF0E0F18) else Nocturne.Dim,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (mode == index) Nocturne.Accent else Nocturne.Surface2)
                        .clickable { mode = index }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }

        when {
            items == null -> LoadingState()
            items!!.isEmpty() -> EmptyState("Rien à afficher", "Cette section est vide.")
            else -> {
                val list = items!!
                LazyColumn(Modifier.fillMaxSize()) {
                    if (mode == 1) {
                        itemsIndexed(list, key = { _, it -> it.Id }) { _, artist ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Cover(graph, artist, 46.dp, round = true)
                                Spacer(Modifier.width(12.dp))
                                Text(artist.Name ?: "", color = Nocturne.Ink, fontSize = 14.sp)
                            }
                        }
                    } else {
                        itemsIndexed(list, key = { _, it -> it.Id }) { index, item ->
                            TrackRow(
                                graph,
                                item,
                                subtitle = if (mode == 0) (item.AlbumArtist ?: item.artistLine) else null,
                            ) {
                                if (mode == 0) playAlbum(graph, scope, item) else graph.play(list, index)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistsTab(graph: AppGraph) {
    var playlists by remember { mutableStateOf<List<JfItem>?>(null) }

    LaunchedEffect(Unit) {
        playlists = runCatching {
            graph.repo.playlists().Items
        }.getOrDefault(emptyList())
    }

    when {
        playlists == null -> LoadingState()
        playlists!!.isEmpty() -> EmptyState(
            "Aucune playlist",
            "Aucune playlist n'existe encore dans Jellyfin. Crée-en une depuis Jellyfin, elle apparaîtra ici.",
        )
        else -> {
            val list = playlists!!
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(list, key = { _, it -> it.Id }) { _, pl ->
                    TrackRow(graph, pl, subtitle = "${pl.ChildCount ?: 0} morceaux")
                }
            }
        }
    }
}
