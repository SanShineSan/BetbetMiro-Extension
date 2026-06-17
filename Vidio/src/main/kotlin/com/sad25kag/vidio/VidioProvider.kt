package com.Vidio

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class VidioProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(Vidio())
    }
}
