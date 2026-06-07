version = 10

cloudstream {
    description = "Dailymotion public video catalog with source-backed homepage, search, detail, and internal HLS playback resolver."
    authors = listOf("BetbetMiro")

    /**
     * Status int:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     */
    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "AsianDrama",
        "Anime",
        "AnimeMovie",
        "Cartoon",
        "Others"
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=www.dailymotion.com&sz=%size%"
    isCrossPlatform = true
}
