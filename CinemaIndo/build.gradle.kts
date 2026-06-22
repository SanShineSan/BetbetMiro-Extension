version = 3

cloudstream {
    description = "CinemaIndo — Movie and TV provider"
    language = "id"
    authors = listOf("sad25kag")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 3

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "AsianDrama"
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=tv.cinemaindo.pw&sz=%size%"
}
