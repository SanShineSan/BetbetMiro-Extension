package com.kingbokep

object KingBokepSeeds {
    const val MAIN_URL = "https://kingbokep.tv"

    object Path {
        const val LATEST = "$MAIN_URL/page/%d/"
        const val INDONESIA = "$MAIN_URL/category/indonesia/page/%d/"
        const val VIRAL = "$MAIN_URL/category/viral/page/%d/"
        const val JAPAN = "$MAIN_URL/category/jepang/page/%d/"
        const val WESTERN = "$MAIN_URL/category/barat/page/%d/"
    }

    /**
     * Keep one explicit contract for homepage rows: every row is a real URL template.
     * Search-only rows are intentionally not mixed here so pageUrl() has one job.
     */
    fun mainPageRows(): Array<Pair<String, String>> = arrayOf(
        Path.INDONESIA to "Bokep Indo",
        Path.JAPAN to "Bokep Jepang",
        Path.WESTERN to "Bokep Barat"
    )
}
