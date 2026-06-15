package com.sad25kag.avtub

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AVTubPlugin : Plugin() {
    override fun load() {
        registerMainAPI(AVTub())
    }
}
