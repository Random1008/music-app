package fr.hermesmusic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.hermesmusic.core.HermesTheme
import fr.hermesmusic.core.Nocturne
import fr.hermesmusic.ui.LoginScreen
import fr.hermesmusic.ui.Shell

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as HermesApp).graph

        setContent {
            HermesTheme {
                val ready by graph.ready.collectAsStateWithLifecycle()
                val session by graph.session.collectAsStateWithLifecycle()

                LaunchedEffect(Unit) { graph.hydrate() }

                Surface(color = Nocturne.Bg, modifier = Modifier.fillMaxSize()) {
                    when {
                        !ready -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                            CircularProgressIndicator(color = Nocturne.Accent)
                        }

                        !session.isConnected -> LoginScreen(graph)
                        else -> Shell(graph)
                    }
                }
            }
        }
    }
}
