package com.sad25kag.duniafilm21

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class DuniaFilm21Plugin : BasePlugin() {
    override fun load() {
        registerMainAPI(DuniaFilm21Provider())
    }
}
