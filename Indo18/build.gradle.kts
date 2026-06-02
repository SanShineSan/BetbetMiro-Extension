version = 13

cloudstream {
    language = "id"
    authors = listOf("BetbetMiro")
    description = "Indo18 provider with active www.indo18.com origin, root-slug detail parsing, source-matched categories, safer posters, and hardened playback resolving from Jomblo/Playmogo redirects, embed/player payloads, stream API hosts, Doodstream, HLS, MP4, and Cloudstream extractors."

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
    iconUrl = "https://www.google.com/s2/favicons?domain=www.indo18.com&sz=%size%"
}
