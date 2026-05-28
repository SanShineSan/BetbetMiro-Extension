package com.putarflix

internal object PutarFlixSeeds {
    const val MAIN_URL = "https://putarflix.com"
    const val SITE_NAME = "PutarFlix"
    const val LANGUAGE = "id"

    // Source-backed rows from the current PutarFlix menu/search filter.
    // Keep the list broad but stable so provider tests do not depend on one fragile homepage block.
    val mainPages = listOf(
        PutarFlixCategory("/", "Beranda"),
        PutarFlixCategory("/category/film-bioskop-terbaru/", "Film Bioskop Terbaru"),
        PutarFlixCategory("/category/film-indonesia-terbaru/", "Film Indonesia Terbaru"),
        PutarFlixCategory("/category/series-indonesia/", "Series Indonesia"),
        PutarFlixCategory("/category/tv-show/", "TV Show"),
        PutarFlixCategory("/category/box-office/", "Box Office"),
        PutarFlixCategory("/category/film-semi/", "Film Semi"),
        PutarFlixCategory("/category/vivamax/", "Vivamax"),
        PutarFlixCategory("/category/action/", "Action"),
        PutarFlixCategory("/category/aksi/", "Aksi"),
        PutarFlixCategory("/category/adventure/", "Adventure"),
        PutarFlixCategory("/category/petualangan/", "Petualangan"),
        PutarFlixCategory("/category/drama/", "Drama"),
        PutarFlixCategory("/category/horror/", "Horror"),
        PutarFlixCategory("/category/kengerian/", "Kengerian"),
        PutarFlixCategory("/category/thriller/", "Thriller"),
        PutarFlixCategory("/category/cerita-seru/", "Cerita Seru"),
        PutarFlixCategory("/category/comedy/", "Comedy"),
        PutarFlixCategory("/category/komedi/", "Komedi"),
        PutarFlixCategory("/category/romance/", "Romance"),
        PutarFlixCategory("/category/percintaan/", "Percintaan"),
        PutarFlixCategory("/category/fantasy/", "Fantasy"),
        PutarFlixCategory("/category/fantasi/", "Fantasi"),
        PutarFlixCategory("/category/science-fiction/", "Science Fiction"),
        PutarFlixCategory("/category/cerita-fiksi/", "Cerita Fiksi"),
        PutarFlixCategory("/category/crime/", "Crime"),
        PutarFlixCategory("/category/kejahatan/", "Kejahatan"),
        PutarFlixCategory("/category/mystery/", "Mystery"),
        PutarFlixCategory("/category/misteri/", "Misteri"),
        PutarFlixCategory("/category/keluarga/", "Keluarga"),
        PutarFlixCategory("/category/animasi/", "Animasi"),
        PutarFlixCategory("/category/dokumenter/", "Dokumenter"),
        PutarFlixCategory("/category/history/", "History"),
        PutarFlixCategory("/category/sejarah/", "Sejarah"),
        PutarFlixCategory("/category/musik/", "Musik"),
        PutarFlixCategory("/category/war/", "War"),
        PutarFlixCategory("/category/film-china-terbaru/", "Film China Terbaru"),
        PutarFlixCategory("/category/film-india-terbaru/", "Film India Terbaru"),
        PutarFlixCategory("/category/film-korea-terbaru/", "Film Korea Terbaru"),
        PutarFlixCategory("/category/film-tv/", "Film TV"),
        PutarFlixCategory("/category/bioskopkeren/", "Bioskopkeren"),
        PutarFlixCategory("/category/dutamovie21/", "Dutamovie21"),
        PutarFlixCategory("/category/drakorkita/", "Drakorkita"),
        PutarFlixCategory("/category/dramaqu/", "Dramaqu"),
        PutarFlixCategory("/category/idlix/", "Idlix"),
        PutarFlixCategory("/category/indoxxi/", "Indoxxi"),
        PutarFlixCategory("/category/layarkaca21/", "Layarkaca21"),
        PutarFlixCategory("/category/lk21/", "Lk21"),
        PutarFlixCategory("/category/rebahin/", "Rebahin"),
        PutarFlixCategory("/category/siapbos21/", "Siapbos21"),
        PutarFlixCategory("/category/siapbosxx1/", "SIAPBOSXX1")
    )

    // The visible player tabs on current PutarFlix pages are base, ?player=2, and ?player=3.
    val playerNumbers = listOf("1", "2", "3")

    // WordPress movie themes commonly use these actions. Invalid actions safely return empty responses.
    val ajaxActions = listOf(
        "doo_player_ajax",
        "dooplay_player",
        "muvipro_player_content",
        "player_ajax",
        "player_ajax_request"
    )
}
