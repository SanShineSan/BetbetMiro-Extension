package com.sad25kag.nontondrakor

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NontonDrakorPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NontonDrakor())
    }
}
