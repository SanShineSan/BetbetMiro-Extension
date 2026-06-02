version = 12

cloudstream {
    language = "id"
    authors = listOf("BetbetMiro")
    description = "Indo18 provider with source-origin fallback, safer listing/search/detail parsing, and hardened playback resolving from direct media, embed/player payloads, stream API hosts, Doodstream, Jomblo/Playmogo redirects, packed scripts, encoded URLs, HLS, MP4, and Cloudstream extractors."

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
