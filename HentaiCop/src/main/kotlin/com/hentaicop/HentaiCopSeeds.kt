package com.hentaicop

object HentaiCopSeeds {
    const val MAIN_URL = "https://hentaicop.com"

    object Path {
        const val HOME = "/"
        const val HENTAI = "/hentai/"
        const val UNCENSORED = "/uncensored/"
        const val JAV = "/jav/"
        const val TWO_D = "/2d/"
    }

    fun mainPageRows(): Array<Pair<String, String>> = arrayOf(
        Path.HOME to "Baru Ditambahkan",
        Path.HENTAI to "Hentai",
        Path.UNCENSORED to "Hentai Uncensored",
        Path.JAV to "JAV",
        Path.TWO_D to "2D"
    )
}
