version = 8

cloudstream {
    description = "Filmapik"
    language = "id"
    authors = listOf("BetbetMiro")

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

    // %size% diganti menjadi angka pasti (256) agar icon valid dan jernih
    iconUrl = "https://www.google.com/s2/favicons?domain=filmapik.fitness&sz=256"
}