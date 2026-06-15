package com.dgs

object DGSSeeds {
    const val MAIN_URL = "https://deepgoretube.site"

    object Path {
        const val HOME = "/home/"
    }

    fun mainPageRows(): Array<Pair<String, String>> = arrayOf(
        Path.HOME to "Home"
    )
}
