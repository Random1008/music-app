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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import fr.hermesmusic.core.AppGraph
import fr.hermesmusic.core.Nocturne
import fr.hermesmusic.core.kicker
import fr.hermesmusic.data.DownloadEntry
import fr.hermesmusic.network.JfItem
import kotlinx.coroutines.launch
import java.time.LocalTime

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

/* ------------------------------------------------------------------ */
/* ACCUEIL                                                              */
/* ------------------------------------------------------------------ */

/** Salutation selon l'heure : on ouvre l'application à toute heure. */
private fun greetingFor(hour: Int): String = when (hour) {
    in 5..11 -> "Bonjour"
    in 12..17 -> "Bon après-midi"
    else -> "Bonsoir"
}

@Composable
fun HomeTab(graph: AppGraph, onOpenSearch: () -> Unit) {
    var albums by remember { mutableStateOf<List<JfItem>?>(null) }
    var added by remember { mutableStateOf<List<JfItem>>(emptyList()) }
    var resume by remember { mutableStateOf<List<JfItem>>(emptyList()) }
    var played by remember { mutableStateOf<List<JfItem>>(emptyList()) }
    var favorites by remember { mutableStateOf<List<JfItem>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<JfItem>>(emptyList()) }
    var counts by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching {
            val recentAlbums = graph.repo.recentAlbums(limit = 12)
            added = graph.repo.recentTracks(limit = 6).Items
            resume = graph.repo.resumeAudio(limit = 5).Items
            played = graph.repo.recentlyPlayed(limit = 8).Items
            favorites = graph.repo.favorites().Items.take(12)
            playlists = graph.repo.playlists().Items.take(12)
            val totalAlbums = graph.repo.albums(limit = 1).TotalRecordCount
            val totalTracks = graph.repo.tracks(limit = 1).TotalRecordCount
            val totalArtists = graph.repo.artists(limit = 1).TotalRecordCount
            albums = recentAlbums.Items
            counts = "$totalTracks morceaux · $totalAlbums albums · $totalArtists artistes"
        }.onFailure { error = it.message ?: "Erreur réseau" }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 22.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        greetingFor(LocalTime.now().hour),
                        color = Nocturne.Ink,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    if (counts.isNotEmpty()) {
                        Spacer(Modifier.height(5.dp))
                        Text(counts, style = kicker(), color = Nocturne.Accent)
                    }
                }
                ActionIcon(
                    icon = Icons.Filled.Settings,
                    description = "Paramètres",
                    tint = Nocturne.Ink,
                ) { graph.openSettings() }
            }
        }

        error?.let { item { EmptyState("Bibliothèque indisponible", it) } }
        if (albums == null && error == null) item { LoadingState() }

        // --- Reprendre la lecture (là où on s'est arrêté) ---
        resume.firstOrNull()?.let { first ->
            item { ResumeCard(graph, first) { graph.play(resume, 0) } }
        }

        // --- Grille compacte : les six derniers morceaux écoutés ---
        if (played.isNotEmpty()) {
            item { SectionHeader("Récemment écouté") }
            item { RecentGrid(graph, played.take(6)) { index -> graph.play(played, index) } }
        }

        // --- Albums récents ---
        albums?.takeIf { it.isNotEmpty() }?.let { list ->
            item { SectionHeader("Albums récents") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(list, key = { _, it -> it.Id }) { _, album ->
                        Column(
                            Modifier
                                .width(132.dp)
                                .clickable { graph.openAlbum(album.Id, album.Name ?: "") },
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

        // --- Playlists ---
        if (playlists.isNotEmpty()) {
            item { SectionHeader("Tes playlists") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(playlists, key = { _, it -> it.Id }) { _, pl ->
                        Column(
                            Modifier
                                .width(120.dp)
                                .clickable { graph.openPlaylist(pl.Id, pl.Name ?: "") },
                        ) {
                            Cover(graph, pl, 120.dp)
                            Spacer(Modifier.height(8.dp))
                            Text(pl.Name ?: "", color = Nocturne.Ink, fontSize = 12.5.sp, maxLines = 1)
                            Text(
                                "${pl.ChildCount ?: 0} morceaux",
                                color = Nocturne.Dim,
                                fontSize = 11.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        // --- Favoris ---
        if (favorites.isNotEmpty()) {
            item { SectionHeader("Favoris") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(favorites, key = { _, it -> "f" + it.Id }) { index, t ->
                        Column(
                            Modifier
                                .width(104.dp)
                                .clickable { graph.play(favorites, index) },
                        ) {
                            Cover(graph, t, 104.dp)
                            Spacer(Modifier.height(7.dp))
                            Text(t.Name ?: "", color = Nocturne.Ink, fontSize = 12.sp, maxLines = 1)
                            Text(t.artistLine, color = Nocturne.Dim, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
            }
        }

        // --- Récemment ajouté ---
        if (added.isNotEmpty()) {
            item { SectionHeader("Récemment ajouté") }
            itemsIndexed(added, key = { _, it -> "n" + it.Id }) { index, t ->
                TrackRow(graph, t) { graph.play(added, index) }
            }
        }
    }
}

/**
 * Grille compacte « récemment écouté » : six vignettes sur deux colonnes, la
 * pochette à gauche et le titre à droite sur un fond discret.
 */
@Composable
private fun RecentGrid(graph: AppGraph, items: List<JfItem>, onPlay: (Int) -> Unit) {
    Column(
        Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.chunked(2).forEachIndexed { rowIndex, rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems.forEachIndexed { columnIndex, item ->
                    val index = rowIndex * 2 + columnIndex
                    Row(
                        Modifier
                            .weight(1f)
                            .height(54.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Nocturne.Surface2)
                            .clickable { onPlay(index) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Cover(graph, item, 54.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            item.Name ?: "",
                            color = Nocturne.Ink,
                            fontSize = 12.5.sp,
                            maxLines = 2,
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                        )
                    }
                }
                // Rangée impaire : on garde la largeur pour aligner les colonnes.
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Carte « reprendre » avec la pochette et la progression. */
@Composable
private fun ResumeCard(graph: AppGraph, item: JfItem, onPlay: () -> Unit) {
    val pct = item.UserData?.PlayedPercentage?.toInt()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Nocturne.Surface2)
            .clickable { onPlay() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(graph, item, 58.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.Name ?: "", color = Nocturne.Ink, fontSize = 14.sp, maxLines = 1)
            Text(item.artistLine, color = Nocturne.Dim, fontSize = 12.sp, maxLines = 1)
            if (pct != null && pct > 0) {
                Spacer(Modifier.height(4.dp))
                Text("Repris à $pct %", style = kicker(), color = Nocturne.Accent)
            }
        }
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Nocturne.Accent)
                .clickable { onPlay() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Reprendre la lecture",
                tint = Nocturne.OnAccent,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* Ligne de morceau réutilisable                                        */
/* ------------------------------------------------------------------ */

@Composable
fun TrackRow(
    graph: AppGraph,
    track: JfItem,
    subtitle: String? = null,
    round: Boolean = false,
    playing: Boolean = false,
    downloaded: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(graph, track, 46.dp, round = round)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.Name ?: "",
                color = if (playing) Nocturne.Accent else Nocturne.Ink,
                fontSize = 14.sp,
                maxLines = 1,
            )
            Text(
                subtitle ?: "${track.artistLine}${track.Album?.let { " · $it" } ?: ""}",
                color = Nocturne.Dim,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
        if (downloaded) {
            Text("↓", color = Nocturne.Dim2, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
        }
        // Une durée n'a de sens que pour un morceau (pas pour un album/artiste/playlist).
        if (track.Type == "Audio") {
            Text(fmtDuration(track.durationSeconds), color = Nocturne.Dim2, fontSize = 11.5.sp)
        }
    }
}

/* ------------------------------------------------------------------ */
/* RECHERCHE                                                            */
/* ------------------------------------------------------------------ */

@Composable
fun SearchTab(graph: AppGraph) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<JfItem>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var filter by remember { mutableIntStateOf(0) }
    var topArtists by remember { mutableStateOf<List<JfItem>>(emptyList()) }

    LaunchedEffect(Unit) {
        topArtists = runCatching { graph.repo.artists(limit = 8).Items }.getOrDefault(emptyList())
    }

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
            placeholder = {
                Text("Morceaux, albums, artistes…", color = Nocturne.Dim2, fontSize = 14.sp)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Nocturne.Surface2,
                unfocusedContainerColor = Nocturne.Surface2,
                focusedBorderColor = Nocturne.Accent,
                unfocusedBorderColor = Nocturne.Hairline,
                focusedTextColor = Nocturne.Ink,
                unfocusedTextColor = Nocturne.Ink,
                cursorColor = Nocturne.Accent,
            ),
        )

        val songs = results.filter { it.Type == "Audio" }
        val albums = results.filter { it.Type == "MusicAlbum" }
        val artists = results.filter { it.Type == "MusicArtist" }

        if (results.isNotEmpty()) {
            val labels = listOf(
                "Tout" to results.size,
                "Morceaux" to songs.size,
                "Albums" to albums.size,
                "Artistes" to artists.size,
            )
            LazyRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(labels) { index, entry ->
                    Text(
                        "${entry.first} ${entry.second}",
                        color = if (filter == index) Nocturne.OnAccent else Nocturne.Dim,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (filter == index) Nocturne.Accent else Nocturne.Surface2)
                            .clickable { filter = index }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                }
            }
        }

        when {
            busy -> LoadingState()

            // Champ vide : on propose à parcourir, comme une page « tout afficher ».
            query.trim().length < 2 -> {
                if (topArtists.isEmpty()) {
                    EmptyState(
                        "Cherche dans ta musique",
                        "Titres, albums et artistes de ta bibliothèque Jellyfin.",
                    )
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 20.dp),
                    ) {
                        item { SectionHeader("Parcourir") }
                        itemsIndexed(topArtists.chunked(2)) { _, rowArtists ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                rowArtists.forEach { artist ->
                                    Column(
                                        Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable {
                                                graph.openArtist(artist.Id, artist.Name ?: "")
                                            }
                                            .padding(vertical = 6.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Cover(graph, artist, 108.dp, round = true)
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            artist.Name ?: "",
                                            color = Nocturne.Ink,
                                            fontSize = 12.5.sp,
                                            maxLines = 1,
                                        )
                                    }
                                }
                                if (rowArtists.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            results.isEmpty() && searched -> EmptyState(
                "Aucun résultat",
                "Rien ne correspond à « $query ».",
            )

            else -> {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 18.dp)) {
                    if (songs.isNotEmpty() && (filter == 0 || filter == 1)) {
                        item { SectionHeader("${songs.size} morceaux") }
                        itemsIndexed(songs, key = { _, it -> it.Id }) { index, t ->
                            TrackRow(graph, t) { graph.play(songs, index) }
                        }
                    }
                    if (albums.isNotEmpty() && (filter == 0 || filter == 2)) {
                        item { SectionHeader("${albums.size} albums") }
                        itemsIndexed(albums, key = { _, it -> it.Id }) { _, a ->
                            TrackRow(graph, a, subtitle = a.AlbumArtist ?: a.artistLine) {
                                graph.openAlbum(a.Id, a.Name ?: "")
                            }
                        }
                    }
                    if (artists.isNotEmpty() && (filter == 0 || filter == 3)) {
                        item { SectionHeader("${artists.size} artistes") }
                        itemsIndexed(artists, key = { _, it -> it.Id }) { _, a ->
                            TrackRow(graph, a, subtitle = "Artiste", round = true) {
                                graph.openArtist(a.Id, a.Name ?: "")
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* BIBLIOTHÈQUE — albums, artistes, morceaux, playlists, favoris,       */
/* téléchargements                                                      */
/* ------------------------------------------------------------------ */

private val LIBRARY_FILTERS = listOf(
    "Albums",
    "Artistes",
    "Morceaux",
    "Playlists",
    "Favoris",
    "Téléchargés",
)

private const val FILTER_DOWNLOADS = 5

@Composable
fun LibraryTab(graph: AppGraph) {
    var mode by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<JfItem>?>(null) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    val downloads by graph.downloadEntries.collectAsStateWithLifecycle()
    val downloadedIds = remember(downloads) { downloads.map { it.itemId }.toSet() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(mode, reloadKey) {
        if (mode == FILTER_DOWNLOADS) {
            // Source locale : rien à demander au serveur.
            items = emptyList()
            return@LaunchedEffect
        }
        items = null
        items = runCatching {
            when (mode) {
                0 -> graph.repo.albums(limit = 60).Items
                1 -> graph.repo.artists(limit = 60).Items
                2 -> graph.repo.tracks(limit = 60).Items
                3 -> graph.repo.playlists().Items
                else -> graph.repo.favorites().Items
            }
        }.onFailure { error = it.message ?: "Erreur réseau" }
            .getOrDefault(emptyList())
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Ta bibliothèque",
                        color = Nocturne.Ink,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Nocturne.Accent)
                            .clickable { creating = true }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = Nocturne.OnAccent,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Playlist",
                            color = Nocturne.OnAccent,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            item {
                LazyRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(LIBRARY_FILTERS) { index, label ->
                        Text(
                            label,
                            color = if (mode == index) Nocturne.OnAccent else Nocturne.Dim,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (mode == index) Nocturne.Accent else Nocturne.Surface2)
                                .clickable { mode = index }
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                        )
                    }
                }
            }

            error?.let { item { EmptyState("Bibliothèque indisponible", it) } }

            if (mode == FILTER_DOWNLOADS) {
                if (downloads.isEmpty()) {
                    item {
                        EmptyState(
                            "Rien en local",
                            "Depuis un album ou une page artiste, touche l'icône de " +
                                "téléchargement pour garder les morceaux sur le téléphone.",
                        )
                    }
                } else {
                    item {
                        Text(
                            "${downloads.size} morceaux · ${fmtBytes(downloads.sumOf { it.sizeBytes })}",
                            style = kicker(),
                            color = Nocturne.Accent,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                    itemsIndexed(downloads, key = { _, it -> it.itemId }) { index, entry ->
                        DeviceRow(entry) { graph.playDownloads(downloads, index) }
                    }
                }
            } else {
                when {
                    items == null -> item { LoadingState() }
                    items!!.isEmpty() -> item {
                        EmptyState(
                            when (mode) {
                                3 -> "Aucune playlist"
                                4 -> "Aucun favori"
                                else -> "Rien à afficher"
                            },
                            when (mode) {
                                3 -> "Touche « Playlist » en haut pour en créer une."
                                4 -> "Touche le cœur sur un album ou dans le lecteur."
                                else -> "Cette section est vide."
                            },
                        )
                    }

                    else -> {
                        val list = items!!
                        itemsIndexed(list, key = { _, it -> it.Id }) { index, item ->
                            when (mode) {
                                0 -> TrackRow(
                                    graph,
                                    item,
                                    subtitle = item.AlbumArtist ?: item.artistLine,
                                ) { graph.openAlbum(item.Id, item.Name ?: "") }

                                1 -> TrackRow(graph, item, subtitle = "Artiste", round = true) {
                                    graph.openArtist(item.Id, item.Name ?: "")
                                }

                                3 -> TrackRow(
                                    graph,
                                    item,
                                    subtitle = "${item.ChildCount ?: 0} morceaux",
                                ) { graph.openPlaylist(item.Id, item.Name ?: "") }

                                else -> TrackRow(
                                    graph,
                                    item,
                                    downloaded = item.Id in downloadedIds,
                                ) { graph.play(list, index) }
                            }
                        }
                    }
                }
            }
        }

        if (creating) {
            NameDialog(
                title = "Nouvelle playlist",
                confirmLabel = "Créer",
                onDismiss = { creating = false },
                onConfirm = { name ->
                    creating = false
                    scope.launch {
                        graph.createPlaylist(name)
                            .onSuccess {
                                mode = 3
                                reloadKey++
                            }
                            .onFailure { error = it.message ?: "Création impossible" }
                    }
                },
            )
        }
    }
}

/** Ligne d'un morceau présent sur l'appareil. */
@Composable
private fun DeviceRow(entry: DownloadEntry, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Nocturne.Surface2),
            contentAlignment = Alignment.Center,
        ) {
            Text("↓", color = Nocturne.Accent, fontSize = 16.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, color = Nocturne.Ink, fontSize = 14.sp, maxLines = 1)
            Text(
                "${entry.artist} · ${fmtBytes(entry.sizeBytes)}",
                color = Nocturne.Dim,
                fontSize = 11.5.sp,
                maxLines = 1,
            )
        }
    }
}
