version = 10

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

    // Dijamin 100% valid: Menghasilkan logo huruf "F" dengan background abu-abu gelap dan teks biru terang
    iconUrl = "https://ui-avatars.com/api/?name=Filmapik&background=212121&color=03a9f4&size=256&font-size=0.5"
}
