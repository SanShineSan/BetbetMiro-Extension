package com.surgefilm21

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class SurgeFilm21Plugin : BasePlugin() {
    override fun load() {
        registerMainAPI(SurgeFilm21Provider())
    }
}
