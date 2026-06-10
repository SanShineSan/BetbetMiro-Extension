package com.sad25kag.cinemacity

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CinemaCityPlugin : Plugin() {
    override fun load() {
        registerMainAPI(CinemaCityProvider())
    }
}
