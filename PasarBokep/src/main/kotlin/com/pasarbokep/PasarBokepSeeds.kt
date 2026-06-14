package com.pasarbokep

object PasarBokepSeeds {
    /**
     * Only source-backed playable category rows. Generic latest row and uncensored branch are
     * excluded because the app row became noisy/dead for category playback validation.
     */
    val mainPage = listOf(
        PasarBokepCategory("Bokep Indo", "/category/bokep-indo/"),
        PasarBokepCategory("Bokep Korea", "/category/bokep-korea/"),
        PasarBokepCategory("Bokep Barat", "/category/bokep-barat/"),
        PasarBokepCategory("Bokep Jilbab", "/category/bokep-jilbab/"),
        PasarBokepCategory("Bokep Jepang", "/category/bokep-jepang/"),
    )

    val blockedPathHints = listOf(
        "/dmca",
        "/copyright",
        "/contact",
        "/lapor-link-rusak",
        "/privacy",
        "/terms",
        "/login",
        "/register",
        "/tag/",
        "/category/",
        "/blog-category/",
        "/photos-category/",
        "/page/",
        "/wp-admin",
        "/wp-login",
        "#",
    )

    val blockedTitleHints = listOf(
        "dmca",
        "copyright",
        "contact",
        "lapor link rusak",
        "login",
        "register",
        "reset password",
        "latest videos",
        "random videos",
        "show more",
        "home",
        "next",
        "last",
        "loading",
        "refresh",
        "close",
        "category:",
        "photo category:",
        "cerita dewasa",
        "cerita sex",
        "cerita selingkuh",
        "komik hentai",
    )
}
