version = 44

cloudstream {
    language = "id"
    authors = listOf("sad25kag")
    description = "Idlix provider with direct IP main URL, origin fallback routing, safe play-session JSON bodies, compile-safe extractor emission, merged redeem fallback parsing, and hardened iframe playback handling."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "AsianDrama"
    )

    isCrossPlatform = false
    iconUrl = "https://www.google.com/s2/favicons?domain=z2.idlixku.com&sz=%size%"
}
