package com.nontonhentai

object NontonHentaiSeeds {
    const val MAIN_URL = "https://nontonhentai.net"

    object Path {
        const val HOME = "/"
        const val LATEST = "/hentai-terbaru"
        const val COMPLETED = "/completed"
        const val HENTAI = "/genres/hentai/"
        const val UNCENSORED = "/anime/?genre%5B%5D=uncensored&order=update&type="
        const val TWO_D = "/genres/2d/"
        const val JAV = "/anime/?genre%5B%5D=jav&order=update&status=&sub="
    }

    fun mainPageRows(): Array<Pair<String, String>> = arrayOf(
        Path.HOME to "Episode Terbaru",
        Path.LATEST to "Sedang Tayang",
        Path.COMPLETED to "Selesai Tayang",
        Path.HENTAI to "Hentai",
        Path.UNCENSORED to "Uncensored",
        Path.TWO_D to "2D",
        Path.JAV to "JAV",
    )
}
