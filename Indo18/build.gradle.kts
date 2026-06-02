version = 11

cloudstream {
    language = "id"
    authors = listOf("BetbetMiro")
    description = "Indo18 provider for indo18.cc with safe adult categories, /v page parsing, search, detail parsing, related videos, and repaired playback resolving from indo18.cc embeds, stream API hosts, Doodstream, Jomblo/Playmogo redirects, packed scripts, encoded media URLs, HLS, MP4, and Cloudstream extractors."

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
    iconUrl = "https://www.google.com/s2/favicons?domain=indo18.cc&sz=%size%"
}
