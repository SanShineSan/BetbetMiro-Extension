package com.nodrakorid

internal object NoDrakorIDSepeda {
    const val MAIN_URL = "https://richemmerson.com"
    const val SITE_NAME = "NoDrakorID"
    const val LANGUAGE = "id"

    val mainPages = listOf(
        NoDrakorIDCategory("/", "Beranda"),
        NoDrakorIDCategory("/genre/action/", "Action"),
        NoDrakorIDCategory("/genre/adventure/", "Adventure"),
        NoDrakorIDCategory("/genre/animation/", "Animation"),
        NoDrakorIDCategory("/genre/comedy/", "Comedy"),
        NoDrakorIDCategory("/genre/crime/", "Crime"),
        NoDrakorIDCategory("/genre/drama/", "Drama"),
        NoDrakorIDCategory("/genre/family/", "Family"),
        NoDrakorIDCategory("/genre/fantasy/", "Fantasy"),
        NoDrakorIDCategory("/genre/history/", "History"),
        NoDrakorIDCategory("/genre/horror/", "Horror"),
        NoDrakorIDCategory("/genre/mystery/", "Mystery"),
        NoDrakorIDCategory("/genre/romance/", "Romance"),
        NoDrakorIDCategory("/genre/science-fiction/", "Science Fiction"),
        NoDrakorIDCategory("/genre/thriller/", "Thriller"),
        NoDrakorIDCategory("/genre/tv-movie/", "TV Movie"),
        NoDrakorIDCategory("/genre/war/", "War"),
        NoDrakorIDCategory("/genre/western/", "Western"),
        NoDrakorIDCategory("/genre/semi-filipina/", "Semi Filipina"),
        NoDrakorIDCategory("/genre/semi-jepang/", "Semi Jepang"),
        NoDrakorIDCategory("/genre/semi-korea/", "Semi Korea"),
        NoDrakorIDCategory("/genre/semi-philippines/", "Semi Philippines"),
        NoDrakorIDCategory("/country/korea/", "Korea"),
        NoDrakorIDCategory("/country/japan/", "Japan"),
        NoDrakorIDCategory("/country/china/", "China"),
        NoDrakorIDCategory("/country/thailand/", "Thailand"),
        NoDrakorIDCategory("/country/philippines/", "Philippines"),
        NoDrakorIDCategory("/country/usa/", "USA"),
        NoDrakorIDCategory("/country/united-kingdom/", "United Kingdom"),
        NoDrakorIDCategory("/country/hong-kong/", "Hong Kong"),
        NoDrakorIDCategory("/country/canada/", "Canada"),
        NoDrakorIDCategory("/country/australia/", "Australia"),
        NoDrakorIDCategory("/year/2026/", "2026"),
        NoDrakorIDCategory("/year/2025/", "2025"),
        NoDrakorIDCategory("/year/2024/", "2024"),
        NoDrakorIDCategory("/year/2023/", "2023"),
        NoDrakorIDCategory("/year/2022/", "2022"),
        NoDrakorIDCategory("/year/2021/", "2021"),
        NoDrakorIDCategory("/year/2020/", "2020"),
        NoDrakorIDCategory("/year/2019/", "2019"),
        NoDrakorIDCategory("/year/2018/", "2018"),
        NoDrakorIDCategory("/year/2017/", "2017"),
        NoDrakorIDCategory("/year/2016/", "2016"),
        NoDrakorIDCategory("/year/2015/", "2015")
    )

    val playerNumbers = listOf("1", "2", "3", "4", "5", "6", "7", "8")

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
        "fetch_player",
        "ajax_player",
        "player_content"
    )
}
