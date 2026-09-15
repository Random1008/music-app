package fr.hermesmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.hermesmusic.core.AppGraph
import fr.hermesmusic.core.Nocturne
import fr.hermesmusic.core.kicker

private data class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun Shell(graph: AppGraph) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        Tab("Accueil", Icons.Filled.Home),
        Tab("Recherche", Icons.Filled.Search),
        Tab("Bibliothèque", Icons.Filled.LibraryMusic),
        Tab("Playlists", Icons.Filled.PlaylistPlay),
    )

    Scaffold(
        containerColor = Nocturne.Bg,
        bottomBar = {
            Column {
                MiniPlayerBar()
                NavigationBar(containerColor = Nocturne.Bg, tonalElevation = 0.dp) {
                    tabs.forEachIndexed { index, item ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label, fontSize = 9.5.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Nocturne.Accent,
                                selectedTextColor = Nocturne.Accent,
                                unselectedIconColor = Nocturne.Dim2,
                                unselectedTextColor = Nocturne.Dim2,
                                indicatorColor = Nocturne.Surface2,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> HomeTab(graph)
                1 -> SearchTab(graph)
                2 -> LibraryTab(graph)
                else -> PlaylistsTab(graph)
            }
        }
    }
}

/** Mini-lecteur persistant. Rempli à l'étape suivante (service Media3). */
@Composable
private fun MiniPlayerBar() {
    Row(
        Modifier
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Nocturne.Surface2)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Nocturne.Surface),
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.fillMaxWidth()) {
            Text("Aucune lecture en cours", color = Nocturne.Dim, fontSize = 12.5.sp)
            Text("Le mini-lecteur arrive à l'étape suivante", style = kicker(), color = Nocturne.Dim2)
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
        if (action != null) {
            Text(
                action,
                style = kicker(),
                color = Nocturne.Dim2,
                modifier = Modifier.then(
                    if (onAction != null) Modifier.clickableNoRipple(onAction) else Modifier
                ),
            )
        }
    }
}

fun fmtDuration(seconds: Int): String =
    "%d:%02d".format(seconds / 60, seconds % 60)

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
        androidx.compose.material3.CircularProgressIndicator(color = Nocturne.Accent, strokeWidth = 2.dp)
    }
}
