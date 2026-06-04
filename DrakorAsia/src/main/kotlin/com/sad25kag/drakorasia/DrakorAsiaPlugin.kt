package com.sad25kag.drakorasia

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DrakorAsiaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DrakorAsia())
    }
}
