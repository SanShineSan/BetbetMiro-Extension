package com.sad25kag.savefilm21

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class SaveFilm21Plugin : BasePlugin() {
    override fun load() {
        registerMainAPI(SaveFilm21())
    }
}
