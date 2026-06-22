package com.sad25kag.layarwarna21

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class LayarWarna21Plugin : BasePlugin() {
    override fun load() {
        registerMainAPI(LayarWarna21())
    }
}
