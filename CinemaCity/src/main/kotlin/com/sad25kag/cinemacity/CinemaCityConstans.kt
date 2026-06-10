package com.sad25kag.cinemacity

object CinemaCityConstans {
    const val MAIN_URL = "https://cinemacity.cc"
    const val NAME = "CinemaCity"
    const val LANGUAGE = "en"
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    val MAIN_PAGE = listOf(
        "$MAIN_URL/movies/" to "Movies",
        "$MAIN_URL/tv-series/" to "TV Series",
        "$MAIN_URL/xfsearch/genre/animation/" to "Animation",
        "$MAIN_URL/xfsearch/genre/documentary/" to "Documentaries",
        "$MAIN_URL/xfsearch/genre/anime/" to "Anime",
        "$MAIN_URL/xfsearch/genre/asian/" to "Asian",
        "$MAIN_URL/xfsearch/genre/action/" to "Action",
        "$MAIN_URL/xfsearch/genre/horror/" to "Horror"
    )

    val REQUEST_HEADERS = mapOf(
        "User-Agent" to DEFAULT_USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$MAIN_URL/"
    )

    const val CARD_SELECTOR =
        "div.dar-short_item, div.ta-rel_item, div.dle-fast_item, .short-item, .short, article, .item"
    const val DETAIL_LINK_SELECTOR = "a[href*=/movies/][href$=.html], a[href*=/tv-series/][href$=.html]"
    const val POSTER_SELECTOR =
        "div.dar-full_poster img, .dar-full_poster img, .full-poster img, .poster img, .fposter img, article img, main img"
    const val DESCRIPTION_SELECTOR =
        "div.ta-full_text1, .ta-full_text1, .full-text, .fdesc, .description, .entry-content, article p, main p"
    const val RECOMMENDATION_SELECTOR = "div.ta-rel div.ta-rel_item, .ta-rel_item, .related .dar-short_item"
}
