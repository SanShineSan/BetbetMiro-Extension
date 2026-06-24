package com.anixcafe

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnixCafeProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnixCafeProvider())
        registerExtractorAPI(Playmogo())
        registerExtractorAPI(AnixCafeVideoplayer())
        registerExtractorAPI(AnixCafeDailymotion())
        registerExtractorAPI(AnixCafeGeoDailymotion())
        registerExtractorAPI(AnixCafeOdnoklassniki())
        registerExtractorAPI(AnixCafeOkRuSSL())
        registerExtractorAPI(AnixCafeOkRuHTTP())
        registerExtractorAPI(AnixCafeVidguard())
        registerExtractorAPI(AnixCafeVidguardBembed())
        registerExtractorAPI(AnixCafeVidguardListeamed())
        registerExtractorAPI(AnixCafeVidguardVgfplay())
    }
}
