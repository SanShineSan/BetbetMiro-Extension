version = 17

cloudstream {
    language = "id"
    authors = listOf("sad25kag")
    description = "Indo18 provider with cleaned categories, content-removal filtering, root-slug detail parsing, and hardened playback resolving for encoded player/redirect payloads, Jomblo/Playmogo, Doodstream, HLS, MP4, and Cloudstream extractors."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 0

    tvTypes = listOf(
        "NSFW"
    )

    isCrossPlatform = false
    iconUrl = "https://www.google.com/s2/favicons?domain=www.indo18.com&sz=%size%"
}
