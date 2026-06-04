package com.sad25kag.dracinsi

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DracinSIPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DracinSI())
    }
}
