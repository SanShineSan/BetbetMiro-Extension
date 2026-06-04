package com.sad25kag.dracinku

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DracinkuPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Dracinku())
    }
}
