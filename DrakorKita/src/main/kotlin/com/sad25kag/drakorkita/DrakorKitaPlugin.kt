package com.sad25kag.drakorkita

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class DrakorKitaPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(DrakorKita())
    }
}
