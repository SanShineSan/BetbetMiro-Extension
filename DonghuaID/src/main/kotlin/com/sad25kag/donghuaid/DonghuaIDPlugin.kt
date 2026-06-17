package com.sad25kag.donghuaid

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DonghuaIDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DonghuaID())
        registerExtractorAPI(DonghuaIDDailyMotion())
        registerExtractorAPI(DonghuaIDGeoDailyMotion())
        registerExtractorAPI(DonghuaIDOdnoklassniki())
        registerExtractorAPI(DonghuaIDOkRuSSL())
        registerExtractorAPI(DonghuaIDOkRuHTTP())
        registerExtractorAPI(DonghuaIDTurboVidhls())
        registerExtractorAPI(DonghuaIDTurboSPlayer())
        registerExtractorAPI(DonghuaIDShortIcu())
        registerExtractorAPI(DonghuaIDShortInk())
        registerExtractorAPI(DonghuaIDAbyssPlayer())
        registerExtractorAPI(DonghuaIDVidhideVip())
        registerExtractorAPI(DonghuaIDVectorX())
        registerExtractorAPI(DonghuaIDFileLionsCallistanise())
        registerExtractorAPI(DonghuaIDFileLionsTo())
        registerExtractorAPI(DonghuaIDFileLionsLive())
        registerExtractorAPI(DonghuaIDFileLionsSite())
        registerExtractorAPI(DonghuaIDFileLionsOnline())
    }
}
