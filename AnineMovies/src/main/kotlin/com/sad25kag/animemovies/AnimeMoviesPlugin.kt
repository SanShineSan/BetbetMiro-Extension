package com.sad25kag.animemovies

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeMoviesPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeMovies())
    }
}
