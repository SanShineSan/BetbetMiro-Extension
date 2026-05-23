version = 3

cloudstream {
    language = "id"
    authors = listOf("BetbetMiro")
    description = "Indo18 provider for indo18.com with safe adult categories, search, detail parsing, related videos, jomblo/playmogo wrapper support, direct playback, packed script extraction, iframe fallback, nested host resolving, and public HLS/MP4 detection."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf(
        "NSFW"
    )

    isCrossPlatform = false
    iconUrl = "https://www.google.com/s2/favicons?domain=indo18.com&sz=%size%"
}