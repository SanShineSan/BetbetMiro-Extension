package com.putarflix

internal object PutarFlixSeeds {
    const val MAIN_URL = "https://putarflix.com"
    const val SITE_NAME = "PutarFlix"
    const val LANGUAGE = "id"

    // Source-backed rows trimmed to the Boss-approved mainPage set.
    val mainPages = listOf(
        PutarFlixCategory("/category/film-bioskop-terbaru/", "Film Bioskop Terbaru"),
        PutarFlixCategory("/category/film-indonesia-terbaru/", "Film Indonesia Terbaru"),
        PutarFlixCategory("/category/box-office/", "Box Office"),
        PutarFlixCategory("/category/drama/", "Drama"),
        PutarFlixCategory("/category/film-semi/", "Film Semi"),
        PutarFlixCategory("/category/vivamax/", "Vivamax"),
        PutarFlixCategory("/category/action/", "Action"),
        PutarFlixCategory("/category/thriller/", "Thriller"),
        PutarFlixCategory("/category/horror/", "Horror / Kengerian"),
        PutarFlixCategory("/category/comedy/", "Comedy / Komedi"),
        PutarFlixCategory("/category/romance/", "Romance / Percintaan"),
        PutarFlixCategory("/category/science-fiction/", "Science Fiction / Cerita Fiksi"),
        PutarFlixCategory("/category/fantasy/", "Fantasy / Fantasi"),
        PutarFlixCategory("/category/crime/", "Crime / Kejahatan"),
        PutarFlixCategory("/category/mystery/", "Mystery / Misteri")
    )

    // The visible player tabs on current PutarFlix pages are base, ?player=2, and ?player=3.
    val playerNumbers = listOf("1", "2", "3", "4", "5", "6")

    // WordPress movie themes commonly use these actions. Invalid actions safely return empty responses.
    val ajaxActions = listOf(
        "doo_player_ajax",
        "dooplay_player",
        "dt_player_ajax",
        "muvipro_player_content",
        "player_ajax",
        "player_ajax_request",
        "get_player",
        "get_video",
        "load_player",
        "fetch_player"
    )
}
