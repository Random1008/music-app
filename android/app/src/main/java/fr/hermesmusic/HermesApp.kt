package fr.hermesmusic

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import fr.hermesmusic.core.AppGraph

class HermesApp : Application(), SingletonImageLoader.Factory {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }

    /**
     * Coil partage le MÊME client OkHttp que le reste de l'app : les pochettes
     * profitent donc de l'en-tête d'authentification Jellyfin, du cache disque
     * et des mêmes délais.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { graph.okHttp }))
            }
            .build()
}
