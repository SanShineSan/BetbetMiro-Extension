package com.nodrakorid

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NoDrakorIDPlugin : Plugin() {
    override fun load() {
        registerMainAPI(NoDrakorIDProvider())
    }
}
