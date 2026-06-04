package com.sad25kag.nimeindo

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NimeIndoPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(NimeIndo())
    }
}
