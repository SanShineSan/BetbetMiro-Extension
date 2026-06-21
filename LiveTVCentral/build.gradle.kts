version = 3

cloudstream {
    authors = listOf("sad25kag")
    language = "en"
    description = "LiveTVCentral live TV provider. Channel list sourced from sitemap. Playback resolves embed and official stream URLs from channel detail pages."

    /**
     * Status int:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1
    tvTypes = listOf("Live")
    iconUrl = "https://www.google.com/s2/favicons?domain=livetvcentral.com&sz=%size%"
    isCrossPlatform = true
}
