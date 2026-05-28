package com.pasarbokep

object PasarBokepSeeds {
    /**
     * Playable homepage sections only.
     * Source also has Cerita/Komik menu branches, but those are article/photo pages,
     * so they are intentionally excluded from Cloudstream video rows to avoid dead playback.
     */
    val mainPage = listOf(
        PasarBokepCategory("Video Terbaru", "/"),
        PasarBokepCategory("Bokep Indo", "/category/bokep-indo/"),
        PasarBokepCategory("Bokep Korea", "/category/bokep-korea/"),
        PasarBokepCategory("Bokep Barat", "/category/bokep-barat/"),
        PasarBokepCategory("Bokep Jilbab", "/category/bokep-jilbab/"),
        PasarBokepCategory("Bokep Jepang", "/category/bokep-jepang/"),
        PasarBokepCategory("Jepang Uncensored", "/category/bokep-jepang-uncensored/"),
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
        "category:",
        "photo category:",
        "cerita dewasa",
        "cerita sex",
        "cerita selingkuh",
        "komik hentai",
    )
}
