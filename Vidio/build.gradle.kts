// use an integer for version numbers
version = 1

cloudstream {
    description = "Vidio public web provider. Parses public homepage/category/detail pages and resolves only public HLS/MP4 links exposed by the source page/API; no DRM, paywall, auth, or cookie bypass."
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

    tvTypes = listOf("Movie", "TvSeries", "Live")
    iconUrl = "https://www.google.com/s2/favicons?domain=vidio.com&sz=%size%"

    isCrossPlatform = true
}
