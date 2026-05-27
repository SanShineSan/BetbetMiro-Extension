package com.gomunime

object GomunimeSeeds {
    const val MAIN_URL = "https://gomunime.top"

    object Path {
        const val HOME = "/"
        const val ONGOING = "/status/ongoing"
        const val COMPLETED = "/status/completed"
        const val MOVIES = "/type/movie"
        const val TOP_RATED = "/koleksi/anime-skor-mal-tertinggi"
        const val CULTIVATION = "/koleksi/anime-cultivation-terbaik"

        const val FANTASY = "/genre/fantasy"
        const val ACTION = "/genre/action"
        const val COMEDY = "/genre/comedy"
        const val SHOUNEN = "/genre/shounen"
        const val ROMANCE = "/genre/romance"
        const val ADVENTURE = "/genre/adventure"
        const val SCHOOL = "/genre/school"
        const val SEINEN = "/genre/seinen"
        const val ISEKAI = "/genre/isekai"
        const val DRAMA = "/genre/drama"
        const val ADULT_CAST = "/genre/adult-cast"
        const val SUPERNATURAL = "/genre/supernatural"
        const val REINCARNATION = "/genre/reincarnation"
        const val SCI_FI = "/genre/sci-fi"
        const val SUSPENSE = "/genre/suspense"
        const val HISTORICAL = "/genre/historical"
        const val MILITARY = "/genre/military"
        const val SHOUJO = "/genre/shoujo"
        const val SLICE_OF_LIFE = "/genre/slice-of-life"
        const val MYSTERY = "/genre/mystery"
    }

    fun mainPageRows(): Array<Pair<String, String>> = arrayOf(
        Path.HOME to "Episode Terbaru",
        Path.ONGOING to "Anime Ongoing",
        Path.COMPLETED to "Anime Tamat",
        Path.MOVIES to "Anime Movies",
        Path.TOP_RATED to "Rating Tertinggi",
        Path.CULTIVATION to "Cultivation",
        Path.FANTASY to "Fantasy",
        Path.ACTION to "Action",
        Path.COMEDY to "Comedy",
        Path.SHOUNEN to "Shounen",
        Path.ROMANCE to "Romance",
        Path.ADVENTURE to "Adventure",
        Path.SCHOOL to "School",
        Path.SEINEN to "Seinen",
        Path.ISEKAI to "Isekai",
        Path.DRAMA to "Drama",
        Path.ADULT_CAST to "Adult Cast",
        Path.SUPERNATURAL to "Supernatural",
        Path.REINCARNATION to "Reincarnation",
        Path.SCI_FI to "Sci-Fi",
        Path.SUSPENSE to "Suspense",
        Path.HISTORICAL to "Historical",
        Path.MILITARY to "Military",
        Path.SHOUJO to "Shoujo",
        Path.SLICE_OF_LIFE to "Slice of Life",
        Path.MYSTERY to "Mystery",
    )
}
