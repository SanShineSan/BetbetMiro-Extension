package com.javfc

object JavFCSeeds {
    const val MAIN_URL = "https://javfc2.xyz"
    const val LIVE_URL = "https://javfc2.live"

    object Path {
        const val HOME = "/home/vids.html"
        const val ALL_MOVIES = "/all-movies.html"
        const val RANKING = "/home/ranking.html"
        const val ENG_SUB = "/genre/eng-sub.html"
        const val FC2 = "/genre/fc2.html"
        const val JAV = "/genre/jav.html"
        const val WEBCAM = "/genre/webcam.html"
        const val CHINA_AV = "/genre/china-av.html"
        const val IPX = "/genre/ipx.html"
        const val FSDCC = "/genre/fsdcc.html"
        const val JUY = "/genre/juy.html"
        const val STAR = "/star/1055.html"
    }

    object Search {
        const val CHINA_AV = "China AV"
        const val IPX = "ipx"
        const val FSDCC = "fsdcc"
        const val AMATEUR = "amateur"
        const val UNCENSORED = "uncensored"
        const val JAPANESE = "japanese"
        const val STUDENT = "student"
    }

    fun mainPagePairs(): Array<Pair<String, String>> = arrayOf(
        Path.HOME to "Terbaru",
        Path.ALL_MOVIES to "All Movies",
        Path.RANKING to "Ranking",
        Path.ENG_SUB to "Engsub",
        Path.FC2 to "FC2PPV",
        Path.JAV to "JAV",
        Path.WEBCAM to "Webcam",
        "search:${Search.CHINA_AV}" to "China AV",
        Path.CHINA_AV to "China AV (Katalog)",
        "search:${Search.AMATEUR}" to "Amateur",
        "search:${Search.UNCENSORED}" to "Uncensored",
        "search:${Search.JAPANESE}" to "Japanese",
        "search:${Search.STUDENT}" to "Student"
    )
}
