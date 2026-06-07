// use an integer for version numbers
version = 3

cloudstream {
    description = "MyNimeku — Portal Anime Indonesia (Stable HAR-backed)"
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
        "AnimeMovie",
        "Anime",
        "OVA",
    )

    iconUrl = "https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://www.mynimeku.com&size=%size%"
}
