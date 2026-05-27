package com.youtube

object YouTubeSeeds {
    const val MAIN_URL = "https://www.youtube.com"

    val mainPage = listOf(
        YouTubeCategory(
            name = "CERITA KAMAI",
            data = "$MAIN_URL/@ceritakamai",
            mode = YouTubeCategoryMode.Channel,
            fallbackQuery = "CERITA KAMAI video terbaru"
        ),
        YouTubeCategory(
            name = "Gameplay Proplayer",
            data = "$MAIN_URL/@gameplayproplayer",
            mode = YouTubeCategoryMode.Channel,
            fallbackQuery = "Gameplay Proplayer video terbaru"
        ),
        YouTubeCategory(
            name = "Calon Sarjana",
            data = "$MAIN_URL/@calonsarjanaid",
            mode = YouTubeCategoryMode.Channel,
            fallbackQuery = "Calon Sarjana video terbaru"
        ),
        YouTubeCategory(
            name = "Alur Cerita Manhwa",
            data = "alur cerita manhwa manhua lengkap indonesia terbaru",
            mode = YouTubeCategoryMode.Search
        ),
        YouTubeCategory(
            name = "Gaming Indonesia",
            data = "gameplay indonesia terbaru",
            mode = YouTubeCategoryMode.Search
        ),
        YouTubeCategory(
            name = "Fakta & Edukasi",
            data = "fakta unik edukasi indonesia terbaru",
            mode = YouTubeCategoryMode.Search
        ),
        YouTubeCategory(
            name = "Teknologi Indonesia",
            data = "review teknologi gadget indonesia terbaru",
            mode = YouTubeCategoryMode.Search
        ),
        YouTubeCategory(
            name = "Podcast Indonesia",
            data = "podcast indonesia terbaru",
            mode = YouTubeCategoryMode.Search
        ),
        YouTubeCategory(
            name = "Musik Indonesia",
            data = "musik indonesia official terbaru",
            mode = YouTubeCategoryMode.Search
        ),
        YouTubeCategory(
            name = "Anime Indonesia",
            data = "anime indonesia review rekomendasi terbaru",
            mode = YouTubeCategoryMode.Search
        ),
        YouTubeCategory(
            name = "Kuliner Indonesia",
            data = "kuliner indonesia street food terbaru",
            mode = YouTubeCategoryMode.Search
        ),
        YouTubeCategory(
            name = "Berita Indonesia",
            data = "berita indonesia terbaru",
            mode = YouTubeCategoryMode.Search
        ),
        YouTubeCategory(
            name = "Live Streaming",
            data = "live streaming indonesia terbaru",
            mode = YouTubeCategoryMode.Search
        )
    )

    fun findCategory(name: String?, data: String?): YouTubeCategory? {
        return mainPage.firstOrNull { category ->
            category.name == name || category.data == data
        }
    }
}
