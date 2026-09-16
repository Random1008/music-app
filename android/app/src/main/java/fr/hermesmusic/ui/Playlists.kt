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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.hermesmusic.core.AppGraph
import fr.hermesmusic.core.Nocturne
import fr.hermesmusic.core.kicker
import fr.hermesmusic.network.JfItem
import kotlinx.coroutines.launch

/* ------------------------------------------------------------------ */
/* Dialogues réutilisables                                             */
/* ------------------------------------------------------------------ */

@Composable
fun NameDialog(
    title: String,
    initial: String = "",
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Nocturne.Surface,
        titleContentColor = Nocturne.Ink,
        textContentColor = Nocturne.Ink,
        title = { Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                placeholder = { Text("Nom de la playlist", color = Nocturne.Dim2, fontSize = 14.sp) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Nocturne.Surface2,
                    unfocusedContainerColor = Nocturne.Surface2,
                    focusedBorderColor = Nocturne.Accent,
                    unfocusedBorderColor = Nocturne.Hairline,
                    focusedTextColor = Nocturne.Ink,
                    unfocusedTextColor = Nocturne.Ink,
                    cursorColor = Nocturne.Accent,
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value) },
                enabled = value.isNotBlank(),
                colors = ButtonDefaults.textButtonColors(contentColor = Nocturne.Accent),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = Nocturne.Dim),
            ) { Text("Annuler") }
        },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Nocturne.Surface,
        titleContentColor = Nocturne.Ink,
        textContentColor = Nocturne.Dim,
        title = { Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium) },
        text = { Text(message, fontSize = 13.5.sp) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = Nocturne.Accent),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = Nocturne.Dim),
            ) { Text("Annuler") }
        },
    )
}

/* ------------------------------------------------------------------ */
/* Éléments partagés                                                   */
/* ------------------------------------------------------------------ */

/** Bouton icône. 48dp par défaut (cible tactile recommandée), 44dp en liste dense. */
@Composable
fun ActionIcon(
    icon: ImageVector,
    description: String,
    tint: Color = Nocturne.Dim,
    boxSize: Dp = 48.dp,
    iconSize: Dp = 20.dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(boxSize)
            .clip(RoundedCornerShape(50))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (enabled) tint else Nocturne.Dim2,
            modifier = Modifier.size(iconSize),
        )
    }
}

/* ------------------------------------------------------------------ */
/* Page PLAYLIST (spec §16)                                            */
/* ------------------------------------------------------------------ */

@Composable
fun PlaylistDetail(graph: AppGraph, playlistId: String, title: String, onBack: () -> Unit) {
    var name by remember(playlistId) { mutableStateOf(title) }
    var items by remember(playlistId) { mutableStateOf<List<JfItem>?>(null) }
    var error by remember(playlistId) { mutableStateOf<String?>(null) }
    var renaming by remember(playlistId) { mutableStateOf(false) }
    var confirmingDelete by remember(playlistId) { mutableStateOf(false) }
    val player by graph.player.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        runCatching { graph.repo.playlistItems(playlistId).Items }
            .onSuccess { items = it; error = null }
            .onFailure { error = it.message ?: "Erreur réseau" }
    }

    LaunchedEffect(playlistId) { reload() }

    val list = items ?: emptyList()
    val minutes = list.sumOf { it.durationSeconds } / 60

    Box(
        Modifier
            .fillMaxSize()
            .background(
                rememberArtworkBrush(
                    graph,
                    list.firstOrNull()?.let { graph.repo.imageUrl(it, 400) },
                )
            ),
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Column(Modifier.fillMaxWidth()) {
                    DetailBar(label = "PLAYLIST", onBack = onBack)

                    if (list.isNotEmpty()) {
                        Box(
                            Modifier.fillMaxWidth().padding(top = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            // Sans pochette propre, une playlist prend celle de
                            // son premier morceau (le fond en dérive aussi).
                            Cover(graph, list.first(), 170.dp)
                        }
                    }

                    Spacer(Modifier.height(18.dp))
                    Text(
                        name,
                        color = Nocturne.Ink,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        buildString {
                            append("${list.size} morceaux")
                            if (minutes > 0) append(" · $minutes min")
                        },
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
                                    Icons.Filled.Edit,
                                    "Renommer la playlist",
                                ) { renaming = true }
                                ActionIcon(
                                    Icons.Filled.Delete,
                                    "Supprimer la playlist",
                                ) { confirmingDelete = true }
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            error?.let { item { EmptyState("Playlist indisponible", it) } }
            if (items == null) item { LoadingState() }

            if (items != null && list.isEmpty()) {
                item {
                    EmptyState(
                        "Playlist vide",
                        "Ajoute des morceaux depuis un album ou depuis le lecteur " +
                            "(icône « ajouter à une playlist »).",
                    )
                }
            }

            itemsIndexed(list, key = { _, it -> it.PlaylistItemId ?: it.Id }) { index, track ->
                PlaylistRow(
                    track = track,
                    number = index + 1,
                    playing = player.itemId == track.Id,
                    canMoveUp = index > 0,
                    canMoveDown = index < list.lastIndex,
                    onPlay = { graph.play(list, index) },
                    onMoveUp = {
                        val entry = track.PlaylistItemId ?: return@PlaylistRow
                        scope.launch {
                            graph.movePlaylistItem(playlistId, entry, index - 1)
                                .onFailure { error = it.message }
                            reload()
                        }
                    },
                    onMoveDown = {
                        val entry = track.PlaylistItemId ?: return@PlaylistRow
                        scope.launch {
                            graph.movePlaylistItem(playlistId, entry, index + 1)
                                .onFailure { error = it.message }
                            reload()
                        }
                    },
                    onRemove = {
                        val entry = track.PlaylistItemId
                        if (entry == null) {
                            error = "Ce morceau ne peut pas être retiré (entrée inconnue)."
                        } else {
                            scope.launch {
                                graph.removeFromPlaylist(playlistId, entry)
                                    .onFailure { error = it.message }
                                reload()
                            }
                        }
                    },
                )
            }
        }

        if (renaming) {
            NameDialog(
                title = "Renommer la playlist",
                initial = name,
                confirmLabel = "Renommer",
                onDismiss = { renaming = false },
                onConfirm = { newName ->
                    renaming = false
                    scope.launch {
                        graph.renamePlaylist(playlistId, newName)
                            .onSuccess { name = newName.trim() }
                            .onFailure { error = it.message ?: "Renommage impossible" }
                    }
                },
            )
        }

        if (confirmingDelete) {
            ConfirmDialog(
                title = "Supprimer la playlist ?",
                message = "« $name » sera supprimée du serveur. Les morceaux eux-mêmes ne sont pas supprimés.",
                confirmLabel = "Supprimer",
                onDismiss = { confirmingDelete = false },
                onConfirm = {
                    confirmingDelete = false
                    scope.launch {
                        graph.deletePlaylist(playlistId)
                            .onSuccess { onBack() }
                            .onFailure { error = it.message ?: "Suppression impossible" }
                    }
                },
            )
        }
    }
}

@Composable
private fun PlaylistRow(
    track: JfItem,
    number: Int,
    playing: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onPlay: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onPlay() }
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "%02d".format(number),
            color = if (playing) Nocturne.Accent else Nocturne.Dim2,
            fontSize = 11.5.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(26.dp),
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
        ActionIcon(
            Icons.Filled.KeyboardArrowUp,
            "Monter",
            boxSize = 44.dp,
            iconSize = 18.dp,
            enabled = canMoveUp,
            onClick = onMoveUp,
        )
        ActionIcon(
            Icons.Filled.KeyboardArrowDown,
            "Descendre",
            boxSize = 44.dp,
            iconSize = 18.dp,
            enabled = canMoveDown,
            onClick = onMoveDown,
        )
        ActionIcon(
            Icons.Filled.Close,
            "Retirer de la playlist",
            boxSize = 44.dp,
            iconSize = 18.dp,
            onClick = onRemove,
        )
    }
}

/* ------------------------------------------------------------------ */
/* Ajout à une playlist (depuis un album ou le lecteur)                */
/* ------------------------------------------------------------------ */

@Composable
fun AddToPlaylistOverlay(
    graph: AppGraph,
    itemIds: List<String>,
    itemLabel: String,
    onClose: () -> Unit,
) {
    var playlists by remember { mutableStateOf<List<JfItem>?>(null) }
    var creating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reloadPlaylists() {
        playlists = runCatching { graph.repo.playlists().Items }.getOrDefault(emptyList())
    }

    LaunchedEffect(Unit) { reloadPlaylists() }

    Box(Modifier.fillMaxSize().background(Nocturne.Bg)) {
        Column(Modifier.fillMaxSize()) {
            DetailBar(label = "AJOUTER À UNE PLAYLIST", onBack = onClose)

            Text(
                itemLabel,
                color = Nocturne.Dim,
                fontSize = 12.5.sp,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            message?.let {
                Text(
                    it,
                    color = Nocturne.Accent,
                    fontSize = 12.5.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }

            if (itemIds.isEmpty()) {
                EmptyState("Rien à ajouter", "Ouvre un album pour choisir des morceaux.")
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { creating = true }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        tint = Nocturne.Accent,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Nouvelle playlist", color = Nocturne.Accent, fontSize = 14.sp)
                }

                val list = playlists
                when {
                    list == null -> LoadingState()
                    list.isEmpty() -> EmptyState(
                        "Aucune playlist",
                        "Crée-en une avec « Nouvelle playlist » ci-dessus.",
                    )

                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(list, key = { _, it -> it.Id }) { _, pl ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            graph.addToPlaylist(pl.Id, itemIds)
                                                .onSuccess {
                                                    message = "Ajouté à « ${pl.Name ?: "playlist"} »"
                                                    reloadPlaylists()
                                                }
                                                .onFailure { message = it.message ?: "Échec de l'ajout" }
                                        }
                                    }
                                    .padding(horizontal = 20.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    pl.Name ?: "",
                                    color = Nocturne.Ink,
                                    fontSize = 14.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "${pl.ChildCount ?: 0} morceaux",
                                    color = Nocturne.Dim2,
                                    fontSize = 11.5.sp,
                                )
                            }
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
            onConfirm = { newName ->
                creating = false
                scope.launch {
                    graph.createPlaylistWith(newName, itemIds)
                        .onSuccess { created ->
                            message = "Ajouté à « ${newName.trim()} » (playlist créée)"
                            playlists = runCatching { graph.repo.playlists().Items }
                                .getOrDefault(emptyList())
                            if (created.isBlank()) message = "Playlist créée."
                        }
                        .onFailure { message = it.message ?: "Création impossible" }
                }
            },
        )
    }
}
