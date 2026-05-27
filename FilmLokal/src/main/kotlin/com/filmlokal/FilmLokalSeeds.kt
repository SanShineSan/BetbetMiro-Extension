package com.filmlokal

object FilmLokalSeeds {
    const val MAIN_URL = "https://tv1.filmlokal.me"

    fun mainPageRows(): Array<Pair<String, String>> = arrayOf(
        "/page/%d/" to "Upload Terbaru",
        "/best-rating/page/%d/" to "Best Rating",
        "/film-series/page/%d/" to "Film Series",
        "/anime-boruto/page/%d/" to "Anime Boruto",
        "/action/page/%d/" to "Action",
        "/adventure/page/%d/" to "Adventure",
        "/animation/page/%d/" to "Animation",
        "/comedy/page/%d/" to "Comedy",
        "/crime/page/%d/" to "Crime",
        "/drama/page/%d/" to "Drama",
        "/fantasy/page/%d/" to "Fantasy",
        "/horror/page/%d/" to "Horror",
        "/mystery/page/%d/" to "Mystery",
        "/romance/page/%d/" to "Romance",
        "/sci-fi/page/%d/" to "Sci-Fi",
        "/thriller/page/%d/" to "Thriller",
        "/year/2026/page/%d/" to "Tahun 2026",
        "/year/2025/page/%d/" to "Tahun 2025",
        "/country/usa/page/%d/" to "USA",
        "/country/japan/page/%d/" to "Japan",
        "/country/korea/page/%d/" to "Korea",
        "/country/china/page/%d/" to "China",
        "/21-mph/page/%d/" to "21 Mph",
        "/sub-indo/page/%d/" to "Subtitle Indonesia",
        "/sub-english/page/%d/" to "Subtitle English",
        "/film-semi/page/%d/" to "Film Semi",
        "/censored/page/%d/" to "Jeruk Censored",
        "/uncensored/page/%d/" to "Jeruk Uncensored",
        "/live-streaming-igo/page/%d/" to "Jeruk Live Igo",
        "/jeruk-barat/page/%d/" to "Jeruk Barat",
        "/asian-amateur/page/%d/" to "Asian Amateur",
        "/jav/page/%d/" to "ALL JAV"
    )
}
