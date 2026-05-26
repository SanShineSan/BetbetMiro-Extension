version = 32

cloudstream {
    language = "id"
    authors = listOf("BetbetMiro")
    description = "Movieon21 provider for tv.movieon21.mov with homepage categories, search, metadata, direct playback, iframe fallback, and download-host extraction."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf(
        "Movie"
    )

    isCrossPlatform = false
    iconUrl = "https://www.google.com/s2/favicons?domain=tv.movieon21.mov&sz=%size%"
}