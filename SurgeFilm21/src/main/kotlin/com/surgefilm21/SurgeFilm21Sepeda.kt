package com.surgefilm21

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Document

object SurgeFilm21Sepeda {
    val baseHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    suspend fun getDocument(url: String, referer: String = SurgeFilm21Provider.DEFAULT_MAIN_URL): Document {
        return app.get(
            url,
            headers = baseHeaders + mapOf("Referer" to referer),
            referer = referer
        ).document
    }

    suspend fun getText(url: String, referer: String = SurgeFilm21Provider.DEFAULT_MAIN_URL): String {
        return app.get(
            url,
            headers = baseHeaders + mapOf("Referer" to referer),
            referer = referer
        ).text
    }
}
