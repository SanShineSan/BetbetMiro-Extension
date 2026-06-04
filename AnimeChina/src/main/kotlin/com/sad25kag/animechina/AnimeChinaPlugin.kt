package com.sad25kag.animechina

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeChinaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeChina())
    }
}
