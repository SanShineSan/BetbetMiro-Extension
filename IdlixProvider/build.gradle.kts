version = 11

cloudstream {
    language = "id"
    authors = listOf("BetbetMiro")
    description = "Idlix provider with API homepage, categories, series episodes, subtitles, and hardened playback extraction."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf(
        "TvSeries",
        "Movie",
        "Anime",
        "AsianDrama"
    )

    isCrossPlatform = false
    iconUrl = "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/Icons/idlix.png"
}