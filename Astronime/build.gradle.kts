// use an integer for version numbers
version = 6

cloudstream {
    description = "Astronime - anime subtitle Indonesia, runtime-clean parser, player_ajax, Turbovid HLS, and Hydrax/Abyss resolver flow"
    authors = listOf("sad25kag")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1

    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
        "OVA"
    )

    language = "id"
    isCrossPlatform = false
}
