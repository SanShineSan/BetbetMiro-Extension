package com.dgs

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DGSPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DGSProvider())
    }
}
