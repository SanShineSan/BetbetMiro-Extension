package com.sad25kag.minioppai

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MiniOppaiPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MiniOppai())
    }
}
