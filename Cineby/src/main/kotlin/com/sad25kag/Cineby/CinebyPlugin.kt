package com.sad25kag.Cineby

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class CinebyPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Cineby())
    }
}