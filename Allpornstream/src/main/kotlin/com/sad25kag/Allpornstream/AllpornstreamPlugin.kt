package com.sad25kag.Allpornstream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AllpornstreamPlugin: Plugin() {
    override fun load() {
        registerMainAPI(Allpornstream())
        registerExtractorAPI(MyDaddyExtractor())
    }
}