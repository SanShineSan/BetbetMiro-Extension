package com.sad25kag.filmanime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FilmAnimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FilmAnime())
    }
}
