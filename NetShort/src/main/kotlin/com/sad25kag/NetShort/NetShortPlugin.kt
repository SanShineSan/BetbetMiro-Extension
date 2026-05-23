package com.sad25kag.NetShort

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class NetShortPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(NetShort())
    }
}