package com.kraptor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLDecoder

open class ReklamSiker : ExtractorApi() {
    override val name = "ReklamSiker"
    override val mainUrl = "https://snaptik.ae"
    override val requiresReferer = true

    private val secureBase = "https://451nj1za7g9v2kexgxatdrh.linux-developers.top/vgroupWRSc/vsecureWRSc/"
    private val userAgent =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/149.0.0.0 Mobile Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = url.queryParam("id")
        val host = url.queryParam("host")

        if (!id.isNullOrBlank() && !host.isNullOrBlank() && host.startsWith("tuberep_", ignoreCase = true)) {
            val mirror = host.substringAfterLast("_").filter { it.isDigit() }
            if (mirror.isNotBlank() && emitSecureVideo("$id$mirror", callback)) return
        }

        val document = runCatching { app.get(url, referer = referer).document }.getOrNull() ?: return
        val iframe = document.selectFirst("iframe[src]")?.attr("src")?.normalizeUrl() ?: return
        loadExtractor(iframe, url, subtitleCallback, callback)
    }

    private suspend fun emitSecureVideo(
        line: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val secureUrl = "${secureBase}?line=$line&waiting=C&background=grey"
        val document = runCatching { app.get(secureUrl, referer = secureUrl).document }.getOrNull() ?: return false
        val m3u8 = Regex("""src:\s*['\"]([^'\"]+\.m3u8[^'\"]*)""", RegexOption.IGNORE_CASE)
            .find(document.html())
            ?.groupValues
            ?.getOrNull(1)
            ?: return false

        M3u8Helper.generateM3u8(
            name,
            m3u8,
            referer = secureUrl,
            headers = mapOf(
                "Referer" to secureUrl,
                "User-Agent" to userAgent,
                "Accept" to "*/*",
            ),
        ).forEach(callback)
        return true
    }

    private fun String.normalizeUrl(): String {
        val cleaned = trim().replace("&amp;", "&")
        return when {
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("/") -> "$mainUrl$cleaned"
            else -> cleaned
        }
    }

    private fun String.queryParam(name: String): String? = runCatching {
        substringAfter("?", "")
            .split("&")
            .firstOrNull { it.substringBefore("=") == name }
            ?.substringAfter("=", "")
            ?.let { URLDecoder.decode(it, "UTF-8") }
    }.getOrNull()
}
