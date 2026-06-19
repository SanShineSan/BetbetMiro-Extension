// use an integer for version numbers
version = 3

cloudstream {
    language = "id"
    description = "Nonton film dan series subtitle Indonesia dari ayononton.live"
    authors = listOf("sad25kag")

    /**
     * Status int as follows:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries",
    )

    iconUrl = "https://ayononton.live/wp-content/themes/muvipro/images/favicon/96x96.png"
}
