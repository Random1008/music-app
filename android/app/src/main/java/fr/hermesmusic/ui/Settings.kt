package fr.hermesmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.hermesmusic.core.AppGraph
import fr.hermesmusic.core.Nocturne
import fr.hermesmusic.core.kicker
import kotlinx.coroutines.launch

/* ------------------------------------------------------------------ */
/* PARAMÈTRES (spec §19, §29)                                           */
/* ------------------------------------------------------------------ */

@Composable
fun SettingsScreen(graph: AppGraph, onBack: () -> Unit) {
    val session by graph.session.collectAsStateWithLifecycle()
    val entries by graph.downloadEntries.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmingLogout by remember { mutableStateOf(false) }

    // Lire ces deux états suffit : tout l'écran se recompose quand on change.
    val dark = Nocturne.dark
    val accent = Nocturne.accentIndex

    Column(Modifier.fillMaxSize()) {
        DetailBar(label = "PARAMÈTRES", onBack = onBack)

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 30.dp),
        ) {
            item { SettingsSection("SERVEUR") }
            item {
                SettingsCard {
                    SettingsLine("Adresse", session.serverUrl.ifBlank { "—" })
                    SettingsLine("Utilisateur", session.userName.ifBlank { "—" })
                    SettingsLine("Version de l'app", AppGraph.VERSION)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Se déconnecter",
                        color = Nocturne.Accent,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Nocturne.Surface)
                            .clickable { confirmingLogout = true }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
            }

            item { SettingsSection("APPARENCE") }
            item {
                SettingsCard {
                    Text("Thème", color = Nocturne.Dim, fontSize = 12.5.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ChoicePill("Sombre", selected = dark) {
                            scope.launch { graph.setAppearance(true, accent) }
                        }
                        ChoicePill("Clair", selected = !dark) {
                            scope.launch { graph.setAppearance(false, accent) }
                        }
                    }

                    Spacer(Modifier.height(22.dp))
                    Text(
                        "Accent · ${Nocturne.accentName}",
                        color = Nocturne.Dim,
                        fontSize = 12.5.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Nocturne.ACCENTS.forEachIndexed { index, entry ->
                            val swatch = entry.second
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(swatch)
                                    .border(
                                        width = if (accent == index) 2.dp else 0.dp,
                                        color = if (accent == index) Nocturne.Ink else swatch,
                                        shape = CircleShape,
                                    )
                                    .semantics { contentDescription = "Accent ${entry.first}" }
                                    .clickable {
                                        scope.launch { graph.setAppearance(dark, index) }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (accent == index) {
                                    Text(
                                        "✓",
                                        color = Nocturne.OnAccent,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "L'accent colore les boutons, la lecture en cours et les libellés.",
                        color = Nocturne.Dim2,
                        fontSize = 11.5.sp,
                    )
                }
            }

            item { SettingsSection("HORS-LIGNE") }
            item {
                SettingsCard {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { graph.openDownloads() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (entries.isEmpty()) {
                                    "Aucun morceau téléchargé"
                                } else {
                                    "${entries.size} morceaux · ${fmtBytes(entries.sumOf { it.sizeBytes })}"
                                },
                                color = Nocturne.Ink,
                                fontSize = 14.sp,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Télécharge des albums pour écouter sans réseau.",
                                color = Nocturne.Dim2,
                                fontSize = 11.5.sp,
                            )
                        }
                        Text("›", color = Nocturne.Dim, fontSize = 22.sp)
                    }
                }
            }
        }
    }

    if (confirmingLogout) {
        ConfirmDialog(
            title = "Se déconnecter ?",
            message = "Le jeton sera effacé de l'appareil et la file de lecture vidée. " +
                "Les morceaux téléchargés restent sur le téléphone.",
            confirmLabel = "Se déconnecter",
            onDismiss = { confirmingLogout = false },
            onConfirm = {
                confirmingLogout = false
                scope.launch { graph.logout() }
            },
        )
    }
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        title,
        style = kicker(),
        color = Nocturne.Dim2,
        modifier = Modifier.padding(start = 20.dp, top = 26.dp, bottom = 10.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Nocturne.Surface2)
            .padding(16.dp),
    ) { content() }
}

@Composable
private fun SettingsLine(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, color = Nocturne.Dim, fontSize = 12.5.sp, modifier = Modifier.width(110.dp))
        Text(value, color = Nocturne.Ink, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ChoicePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Nocturne.OnAccent else Nocturne.Ink,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) Nocturne.Accent else Nocturne.Surface)
            .clickable { onClick() }
            .padding(horizontal = 22.dp, vertical = 13.dp),
    )
}

/* ------------------------------------------------------------------ */
/* HORS-LIGNE (spec §18)                                                */
/* ------------------------------------------------------------------ */

@Composable
fun DownloadsScreen(graph: AppGraph, onBack: () -> Unit) {
    val entries by graph.downloadEntries.collectAsStateWithLifecycle()
    val progress by graph.downloadProgress.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmingClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        DetailBar(label = "HORS-LIGNE", onBack = onBack)

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    Text(
                        "${entries.size} morceaux sur l'appareil",
                        color = Nocturne.Ink,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Espace occupé : ${fmtBytes(entries.sumOf { it.sizeBytes })}",
                        style = kicker(),
                        color = Nocturne.Accent,
                    )

                    if (progress.running) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Téléchargement ${progress.done + 1}/${progress.total} · ${progress.currentTitle}",
                            style = kicker(),
                            color = Nocturne.Accent,
                            maxLines = 1,
                        )
                    } else if (progress.total > 0) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Dernier téléchargement : ${progress.done} terminé(s)" +
                                if (progress.failed > 0) ", ${progress.failed} en échec" else "",
                            style = kicker(),
                            color = if (progress.failed > 0) Nocturne.Accent else Nocturne.Dim2,
                            maxLines = 2,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Masquer ce message",
                            style = kicker(),
                            color = Nocturne.Dim,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .clickable { graph.clearDownloadProgress() }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }

                    if (entries.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(Nocturne.Accent)
                                    .clickable { graph.playDownloads(entries, 0) }
                                    .padding(horizontal = 18.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = Nocturne.OnAccent,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Tout lire",
                                    color = Nocturne.OnAccent,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            ActionIcon(
                                Icons.Filled.DeleteSweep,
                                "Tout supprimer",
                                tint = Nocturne.Ink,
                            ) { confirmingClear = true }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }

            if (entries.isEmpty()) {
                item {
                    EmptyState(
                        "Rien en local",
                        "Depuis un album ou une page artiste, touche l'icône de " +
                            "téléchargement pour garder les morceaux sur le téléphone.",
                    )
                }
            }

            itemsIndexed(entries, key = { _, it -> it.itemId }) { index, entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier
                            .weight(1f)
                            .clickable { graph.playDownloads(entries, index) }
                            .padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.title,
                                color = Nocturne.Ink,
                                fontSize = 14.sp,
                                maxLines = 1,
                            )
                            Text(
                                "${entry.artist} · ${fmtBytes(entry.sizeBytes)}",
                                color = Nocturne.Dim,
                                fontSize = 11.5.sp,
                                maxLines = 1,
                            )
                        }
                    }
                    ActionIcon(
                        Icons.Filled.Close,
                        "Supprimer le téléchargement",
                        boxSize = 44.dp,
                        iconSize = 18.dp,
                    ) { scope.launch { graph.removeDownload(entry.itemId) } }
                }
            }

            if (entries.isNotEmpty()) {
                item {
                    Text(
                        "Les fichiers vivent dans le stockage privé de l'application : " +
                            "les désinstaller efface tout.",
                        color = Nocturne.Dim2,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                }
            }
        }
    }

    if (confirmingClear) {
        ConfirmDialog(
            title = "Supprimer tous les téléchargements ?",
            message = "${entries.size} morceaux (${fmtBytes(entries.sumOf { it.sizeBytes })}) " +
                "seront effacés de l'appareil. La bibliothèque du serveur n'est pas touchée.",
            confirmLabel = "Supprimer",
            onDismiss = { confirmingClear = false },
            onConfirm = {
                confirmingClear = false
                scope.launch { graph.clearDownloads() }
            },
        )
    }
}
