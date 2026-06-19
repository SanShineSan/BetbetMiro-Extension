package com.sad25kag.animeplay

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AnimePlayPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimePlay())
    }
}
