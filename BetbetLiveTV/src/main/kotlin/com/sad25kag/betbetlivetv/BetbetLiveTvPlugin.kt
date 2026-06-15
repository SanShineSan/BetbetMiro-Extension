package com.sad25kag.betbetlivetv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BetbetLiveTvPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(BetbetLiveTvProvider())
    }
}
