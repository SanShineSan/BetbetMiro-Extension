package com.sad25kag.bioskopgo

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class BioskopGoPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(BioskopGo())
    }
}
