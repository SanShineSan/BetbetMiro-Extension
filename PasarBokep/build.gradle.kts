version = 2

cloudstream {
    language = "id"
    authors = listOf("BetbetMiro")
    description = "PasarBokep provider for pasarbokep.com with country-based homepage rows, WordPress REST fallback parsing, safer metadata parsing, and stronger direct/embed video extraction."

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
    iconUrl = "https://www.google.com/s2/favicons?domain=pasarbokep.com&sz=%size%"
}
