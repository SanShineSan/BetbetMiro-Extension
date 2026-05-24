version = 3

cloudstream {
    language = "id"
    authors = listOf("BetbetMiro")
    description = "IndoDrama21 rebuilt for the active IP source with clean grouped categories, resilient listing parser, episode mapping, Dooplay AJAX support, and hardened playback extraction."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf(
        "AsianDrama",
        "TvSeries",
        "Movie"
    )

    isCrossPlatform = false
    iconUrl = "https://www.google.com/s2/favicons?domain=89.124.111.64&sz=%size%"
}
