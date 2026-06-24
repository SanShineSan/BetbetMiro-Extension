version = 1

cloudstream {
    description = "Moenime provider untuk anime subtitle Indonesia dari moenime.com dengan resolver player dinamis."
    language = "id"
    authors = listOf("sad25kag")

    /**
     * Status int:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    iconUrl = "https://www.google.com/s2/favicons?domain=moenime.com&sz=%size%"
    isCrossPlatform = false
}
