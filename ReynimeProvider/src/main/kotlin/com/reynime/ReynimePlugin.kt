package com.reynime

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class ReynimePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(ReynimeProvider())
    }
}
