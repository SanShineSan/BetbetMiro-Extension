package com.sad25kag.dramaindo

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DramaIndoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DramaIndo())
    }
}
