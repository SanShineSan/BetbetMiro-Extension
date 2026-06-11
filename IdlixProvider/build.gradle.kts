version = 48

cloudstream {
    language = "id"
    authors = listOf("BetbetMiro")
    description = "Idlix provider with active-domain API routing, detail URL normalization, provider session lifecycle, and session redeem playback flow."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "AsianDrama"
    )

    isCrossPlatform = false
    iconUrl = "https://www.google.com/s2/favicons?domain=z2.idlixku.com&sz=%size%"
}
