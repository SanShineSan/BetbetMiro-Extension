package com.sad25kag.iptvorg

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class IPTVOrgPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(IPTVOrgProvider())
    }
}
