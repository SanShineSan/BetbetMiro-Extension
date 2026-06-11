version = 6

cloudstream {
    language = "id"
    authors = listOf("BetbetMiro")
    description = "IndoDrama21 HAR-backed active domain build with Muvipro card/detail parsing and Asiastream HLS playback resolver."

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
    iconUrl = "https://www.google.com/s2/favicons?domain=indodrama21.online&sz=%size%"
}
