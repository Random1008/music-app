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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.hermesmusic.core.AppGraph
import fr.hermesmusic.core.Nocturne
import fr.hermesmusic.core.kicker
import kotlinx.coroutines.launch

/**
 * Le serveur ne répond pas. Plutôt qu'un écran vide et muet : une explication,
 * un bouton « Réessayer », la déconnexion — et surtout ce qui reste écoutable
 * (les morceaux téléchargés), parce que c'est exactement là que ça compte.
 */
@Composable
fun ServerUnreachableScreen(graph: AppGraph) {
    val scope = rememberCoroutineScope()
    val session by graph.session.collectAsStateWithLifecycle()
    val entries by graph.downloadEntries.collectAsStateWithLifecycle()
    val progress by graph.downloadProgress.collectAsStateWithLifecycle()
    var checking by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Nocturne.Bg),
        contentPadding = PaddingValues(bottom = 30.dp),
    ) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 30.dp, vertical = 46.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Filled.CloudOff,
                    contentDescription = null,
                    tint = Nocturne.Dim2,
                    modifier = Modifier.size(42.dp),
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    "Serveur injoignable",
                    color = Nocturne.Ink,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Impossible de joindre « ${session.serverUrl} ». Vérifie que le serveur " +
                        "est allumé et que le téléphone a du réseau (ou le VPN), puis réessaie.",
                    color = Nocturne.Dim,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.Center) {
                    Text(
                        if (checking) "Vérification…" else "Réessayer",
                        color = Nocturne.OnAccent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Nocturne.Accent)
                            .clickable(enabled = !checking) {
                                checking = true
                                scope.launch {
                                    graph.pingServer()
                                    checking = false
                                }
                            }
                            .padding(horizontal = 26.dp, vertical = 15.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Se déconnecter",
                        color = Nocturne.Ink,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Nocturne.Surface2)
                            .clickable { scope.launch { graph.logout() } }
                            .padding(horizontal = 22.dp, vertical = 15.dp),
                    )
                }

                if (checking) {
                    Spacer(Modifier.height(16.dp))
                    CircularProgressIndicator(color = Nocturne.Accent, strokeWidth = 2.dp)
                }
            }
        }

        if (entries.isNotEmpty()) {
            item {
                SectionHeader("Écoutable hors-ligne · ${entries.size}")
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .clickable { graph.playDownloads(entries, 0) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Nocturne.Accent,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Tout lire", color = Nocturne.Accent, fontSize = 13.sp)
                }
            }
            itemsIndexed(entries, key = { _, it -> it.itemId }) { index, entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { graph.playDownloads(entries, index) }
                        .padding(horizontal = 20.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Nocturne.Surface2),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = Nocturne.Ink,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.title, color = Nocturne.Ink, fontSize = 14.sp, maxLines = 1)
                        Text(entry.artist, color = Nocturne.Dim, fontSize = 11.5.sp, maxLines = 1)
                    }
                }
            }
        } else if (!progress.running) {
            item {
                Text(
                    "Aucun morceau téléchargé sur cet appareil : il n'y a rien à écouter " +
                        "sans le serveur.",
                    style = kicker(),
                    color = Nocturne.Dim2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 30.dp),
                )
            }
        }
    }
}
