package com.samehadaku

object SamehadakuSeeds {
    const val MAIN_URL = "https://v2.samehadaku.how/"
    const val MAIN_ROOT = "https://v2.samehadaku.how"
    const val LANDING_URL = "https://samehadaku.care"
    const val LEGACY_BATCH_URL = "https://v1.samehadaku.how"
    const val BATCH_URL = "$LEGACY_BATCH_URL/batch/"
    const val CATEGORY_DATA_SEPARATOR = "||"

    val mirrors = listOf(
        MAIN_URL,
        LEGACY_BATCH_URL
    )

    val websiteGenres = listOf(
        "Fantasy" to "fantasy",
        "Action" to "action",
        "Adventure" to "adventure",
        "Comedy" to "comedy",
        "Shounen" to "shounen",
        "School" to "school",
        "Romance" to "romance",
        "Drama" to "drama",
        "Supernatural" to "supernatural",
        "Isekai" to "isekai",
        "Sci-Fi" to "sci-fi",
        "Seinen" to "seinen",
        "Reincarnation" to "reincarnation",
        "Historical" to "historical",
        "Mystery" to "mystery",
        "Super Power" to "super-power",
        "Harem" to "harem",
        "Slice of Life" to "slice-of-life",
        "Ecchi" to "ecchi",
        "Sports" to "sports"
    )

    val mainPage = listOf(
        SamehadakuCategory("Anime", "$MAIN_ROOT/anime-terbaru/%page%", true, SamehadakuCategoryMode.HomeLatest),
        SamehadakuCategory("Movie", "$MAIN_ROOT/anime-type/movie/%page%"),
        SamehadakuCategory("Fantasy", "$MAIN_ROOT/genre/fantasy/%page%"),
        SamehadakuCategory("Action/Adventure", "$MAIN_ROOT/genre/action/%page%$CATEGORY_DATA_SEPARATOR$MAIN_ROOT/genre/adventure/%page%"),
        SamehadakuCategory("Comedy", "$MAIN_ROOT/genre/comedy/%page%"),
        SamehadakuCategory("Romance", "$MAIN_ROOT/genre/romance/%page%"),
        SamehadakuCategory("Supernatural", "$MAIN_ROOT/genre/supernatural/%page%"),
        SamehadakuCategory("Isekai", "$MAIN_ROOT/genre/isekai/%page%"),
        SamehadakuCategory("Sci-Fi", "$MAIN_ROOT/genre/sci-fi/%page%"),
        SamehadakuCategory("Seinen", "$MAIN_ROOT/genre/seinen/%page%"),
        SamehadakuCategory("Reincarnation", "$MAIN_ROOT/genre/reincarnation/%page%"),
        SamehadakuCategory("Super Power", "$MAIN_ROOT/genre/super-power/%page%"),
        SamehadakuCategory("Historical", "$MAIN_ROOT/genre/historical/%page%"),
        SamehadakuCategory("Mystery", "$MAIN_ROOT/genre/mystery/%page%"),
        SamehadakuCategory("Harem", "$MAIN_ROOT/genre/harem/%page%"),
        SamehadakuCategory("Slice of Life", "$MAIN_ROOT/genre/slice-of-life/%page%"),
        SamehadakuCategory("Ecchi", "$MAIN_ROOT/genre/ecchi/%page%")
    )

    val fallbackLatest = listOf(
        SamehadakuSeedItem("Hidarikiki no Eren", "$MAIN_ROOT/anime/hidarikiki-no-eren/"),
        SamehadakuSeedItem("Aishiteru Game wo Owarasetai", "$MAIN_ROOT/anime/aishiteru-game-wo-owarasetai/"),
        SamehadakuSeedItem("Marriagetoxin", "$MAIN_ROOT/anime/marriagetoxin/"),
        SamehadakuSeedItem("Higeki no Genkyou to Naru Saikyou Season 2", "$MAIN_ROOT/anime/higeki-no-genkyou-to-naru-saikyou-season-2/"),
        SamehadakuSeedItem("Liar Game", "$MAIN_ROOT/anime/liar-game/"),
        SamehadakuSeedItem("Isekai Nonbiri Nouka Season 2", "$MAIN_ROOT/anime/isekai-nonbiri-nouka-season-2/"),
        SamehadakuSeedItem("Tongari Boushi no Atelier", "$MAIN_ROOT/anime/tongari-boushi-no-atelier/"),
        SamehadakuSeedItem("Kuroneko to Majo no Kyoushitsu", "$MAIN_ROOT/anime/kuroneko-to-majo-no-kyoushitsu/"),
        SamehadakuSeedItem("One Piece", "$MAIN_ROOT/anime/one-piece/"),
        SamehadakuSeedItem("Tsue to Tsurugi no Wistoria Season 2", "$MAIN_ROOT/anime/tsue-to-tsurugi-no-wistoria-season-2/")
    )

    val fallbackTop = listOf(
        SamehadakuSeedItem("One Piece", "$MAIN_ROOT/anime/one-piece/"),
        SamehadakuSeedItem("Tsue to Tsurugi no Wistoria Season 2", "$MAIN_ROOT/anime/tsue-to-tsurugi-no-wistoria-season-2/"),
        SamehadakuSeedItem("Tensei shitara Slime Datta Ken Season 4", "$MAIN_ROOT/anime/tensei-shitara-slime-datta-ken-season-4/"),
        SamehadakuSeedItem("Tongari Boushi no Atelier", "$MAIN_ROOT/anime/tongari-boushi-no-atelier/"),
        SamehadakuSeedItem("The Beginning After the End Season 2", "$MAIN_ROOT/anime/the-beginning-after-the-end-season-2/"),
        SamehadakuSeedItem("Dr. Stone Season 4 Part 3", "$MAIN_ROOT/anime/dr-stone-season-4-part-3/"),
        SamehadakuSeedItem("Yomi no Tsugai", "$MAIN_ROOT/anime/yomi-no-tsugai/"),
        SamehadakuSeedItem("Classroom of the Elite Season 4", "$MAIN_ROOT/anime/classroom-of-the-elite-season-4/"),
        SamehadakuSeedItem("Marriagetoxin", "$MAIN_ROOT/anime/marriagetoxin/"),
        SamehadakuSeedItem("Higeki no Genkyou to Naru Saikyou Season 2", "$MAIN_ROOT/anime/higeki-no-genkyou-to-naru-saikyou-season-2/")
    )

    val fallbackMovies = listOf(
        SamehadakuSeedItem("Kimetsu no Yaiba – The Movie: Infinity Castle – Part 1: Akaza Returns", "$MAIN_ROOT/anime/kimetsu-no-yaiba-the-movie-infinity-castle-part-1-akaza-returns/", true),
        SamehadakuSeedItem("Chainsaw Man Reze-hen", "$MAIN_ROOT/anime/chainsaw-man-reze-hen/", true),
        SamehadakuSeedItem("Sidonia no Kishi Ai Tsumugu Hoshi", "$MAIN_ROOT/anime/sidonia-no-kishi-ai-tsumugu-hoshi/", true),
        SamehadakuSeedItem("Overlord Movie 3 Sei Oukoku hen", "$MAIN_ROOT/anime/overlord-movie-3-sei-oukoku-hen/", true),
        SamehadakuSeedItem("Boku no Hero Academia the Movie 4", "$MAIN_ROOT/anime/boku-no-hero-academia-the-movie-4/", true),
        SamehadakuSeedItem("Haikyuu!! Movie: Gomisuteba no Kessen", "$MAIN_ROOT/anime/haikyuu-movie-gomisuteba-no-kessen/", true),
        SamehadakuSeedItem("Blue Lock: Episode Nagi", "$MAIN_ROOT/anime/blue-lock-episode-nagi/", true)
    )

}
