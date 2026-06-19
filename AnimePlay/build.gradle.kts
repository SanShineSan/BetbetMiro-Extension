// use an integer for version numbers
version = 1

cloudstream {
    language = "id"
    // All of these properties are optional, you can safely remove them

    description = "Nonton anime subtitle Indonesia dari anime-play.id"
    authors = listOf("sad25kag")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified

    // List of video source types. Two options: "subs" OR "dubbing"
    tvTypes = listOf("Anime", "AnimeMovie", "OVA")

    iconUrl = "https://anime-play.id/uploads/branding/favicon-1771327092431-f45ad0ab.png"
}
