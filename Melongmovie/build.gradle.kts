version = 14

cloudstream {
    description = "Melongmovie provider maintained by BetbetMiro"
    language = "id"
    authors = listOf("sad25kag")

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
        "AsianDrama",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=tv12.melongmovies.com&sz=%size%"
}
