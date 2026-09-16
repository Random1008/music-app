package fr.hermesmusic

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.hermesmusic.core.HermesTheme
import fr.hermesmusic.core.Nocturne
import fr.hermesmusic.ui.LoginScreen
import fr.hermesmusic.ui.Shell

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as HermesApp).graph
        // On se connecte au service de lecture dès le démarrage : comme ça, si
        // une musique joue déjà, l'interface la retrouve telle quelle.
        graph.start()

        setContent {
            HermesTheme {
                val ready by graph.ready.collectAsStateWithLifecycle()
                val session by graph.session.collectAsStateWithLifecycle()

                // Notification de lecture : requise à partir d'Android 13.
                val context = LocalContext.current
                val askNotifications = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { }
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val granted = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!granted) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    graph.hydrate()
                }

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
