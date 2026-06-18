package com.sad25kag.animebagus

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeBagusPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeBagus())
    }
}
