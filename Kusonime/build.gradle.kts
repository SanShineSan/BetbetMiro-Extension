// use an integer for version numbers
version = 1

cloudstream {
    language = "id"
    authors = listOf("sad25kag")
    description = "Kusonime — Download Anime Batch Subtitle Indonesia"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1
    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
        "OVA",
    )

    iconUrl = "https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://kusonime.com&size=%size%"
}
