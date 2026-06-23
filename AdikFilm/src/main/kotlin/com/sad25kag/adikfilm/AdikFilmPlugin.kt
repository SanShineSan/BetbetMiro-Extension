package com.sad25kag.adikfilm

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AdikFilmPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AdikFilm())
    }
}
