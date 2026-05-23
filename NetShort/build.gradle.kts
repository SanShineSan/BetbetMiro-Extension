version = 1

cloudstream {
    language = "id"
    authors = listOf("BetbetMiro")
    description = "NetShort provider for netshort.com/id with homepage, URL-based drama categories, plot/genre rows, search, detail parsing, episode list parsing, public media detection, and playable HLS/MP4 extraction."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf(
        "TvSeries"
    )

    isCrossPlatform = false
    iconUrl = "https://www.google.com/s2/favicons?domain=netshort.com&sz=%size%"
}