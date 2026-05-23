package com.sad25kag.animeindo

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AnimeIndoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeIndo())
    }
}
