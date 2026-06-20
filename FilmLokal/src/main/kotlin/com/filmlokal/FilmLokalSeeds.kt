package com.filmlokal

object FilmLokalSeeds {
    const val MAIN_URL = "https://tv1.filmlokal.me"

    fun mainPageRows(): Array<Pair<String, String>> = arrayOf(
        "http://tv1.filmlokal.me/featured/page/%d/" to "Featured",
        "https://tv1.filmlokal.me/action/page/%d/" to "Action",
        "https://tv1.filmlokal.me/adventure/page/%d/" to "Adventure",
        "https://tv1.filmlokal.me/animation/page/%d/" to "Animation",
        "https://tv1.filmlokal.me/comedy/page/%d/" to "Comedy",
        "https://tv1.filmlokal.me/crime/page/%d/" to "Crime",
        "https://tv1.filmlokal.me/drama/page/%d/" to "Drama",
        "https://tv1.filmlokal.me/fantasy/page/%d/" to "Fantasy",
        "https://tv1.filmlokal.me/horror/page/%d/" to "Horror",
        "https://tv1.filmlokal.me/mystery/page/%d/" to "Mystery",
        "https://tv1.filmlokal.me/romance/page/%d/" to "Romance",
        "https://tv1.filmlokal.me/thriller/page/%d/" to "Thriller"
    )
}
