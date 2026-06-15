version = 1

cloudstream {
    authors = listOf("sad25kag")
    language = "id"
    description = "Live TV public-stream provider using a conservative IPTV-org whitelist. No login, account sharing, private token, DRM, proxy, or restreaming."

    /**
     * Status int:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 3
    tvTypes = listOf("Live")
    iconUrl = "https://www.google.com/s2/favicons?domain=globetv.app&sz=%size%"
    isCrossPlatform = true
}
