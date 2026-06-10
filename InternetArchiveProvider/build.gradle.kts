// Use an integer for version numbers
version = 5

cloudstream {
    description = "Watch movies, classic TV, animation, and public-domain video from the Internet Archive"
    authors = listOf("sad25kag")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Anime", "Others")
    iconUrl = "https://www.google.com/s2/favicons?domain=archive.org&sz=%size%"

    isCrossPlatform = true
}
