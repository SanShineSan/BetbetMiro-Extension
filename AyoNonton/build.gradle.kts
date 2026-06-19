// use an integer for version numbers
version = 1

cloudstream {
    language = "id"
    // All of the following URLs will be checked when choosing a server
    hosts = listOf(
        "ayononton.live"
    )

    /**
     * Status int as follows:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries",
    )

    iconUrl = "https://ayononton.live/wp-content/themes/muvipro/images/favicon/96x96.png"
}
