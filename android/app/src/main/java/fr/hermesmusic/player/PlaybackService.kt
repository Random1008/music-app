package fr.hermesmusic.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import fr.hermesmusic.MainActivity
import fr.hermesmusic.HermesApp

/**
 * Service de lecture : il POSSÈDE le lecteur, pas l'interface.
 *
 * Conséquence voulue : la musique continue quand l'app passe en arrière-plan,
 * l'écran se verrouille, ou l'utilisateur change d'écran ; et les commandes du
 * casque/Bluetooth et de la notification passent par la MediaSession.
 *
 * Le lecteur utilise le client OkHttp partagé de l'application, donc l'en-tête
 * d'authentification Jellyfin est ajouté automatiquement aux requêtes audio.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val graph = (application as HermesApp).graph

        // Source de données : réseau (Jellyfin) via le client partagé.
        val httpFactory = OkHttpDataSource.Factory(graph.okHttp)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)

        // Tampons généreux : le serveur peut être distant (VPN / Internet).
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30_000,   // minBufferMs
                180_000,  // maxBufferMs
                2_500,    // bufferForPlaybackMs
                5_000,    // bufferForPlaybackAfterRebufferMs
            )
            .build()

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // Met en pause quand on débranche le casque (évite de faire du bruit).
            .setHandleAudioBecomingNoisy(true)
            .build()

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /** L'utilisateur a balayé l'app hors des tâches récentes : on s'arrête proprement. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
