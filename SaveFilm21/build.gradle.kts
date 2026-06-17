version = 4

cloudstream {
    authors = listOf("sad25kag")
    language = "id"
    description = "SaveFilm21 provider untuk streaming film dan series subtitle Indonesia dari new13.savefilm21info.com. Parser dibuat evidence-based dari source aktif dengan homepage/category cards, detail movie/series, multi-server playback (sf21.vidplayer.live, sf21.rpmvid.com), dan episode navigator via /eps/ path."
    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "AsianDrama"
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=new13.savefilm21info.com&sz=%size%"
    isCrossPlatform = true
}
