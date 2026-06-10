package com.sad25kag.cinemacity

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.nicehttp.NiceResponse
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import java.net.URLEncoder

object CinemaCityUtils {
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val cloudflareInterceptor by lazy { CinemaCityCloudflareInterceptor(cloudflareKiller) }
    private var dynamicCookies: Map<String, String> = emptyMap()

    suspend fun get(url: String): NiceResponse {
        val response = app.get(
            url,
            headers = CinemaCityConstans.REQUEST_HEADERS,
            cookies = dynamicCookies,
            interceptor = cloudflareInterceptor
        )
        if (response.cookies.isNotEmpty()) dynamicCookies = dynamicCookies + response.cookies
        val cookieHeader = cloudflareKiller.getCookieHeaders(url).toMap()["Cookie"].orEmpty()
        Regex("cf_clearance=([^;]+)").find(cookieHeader)?.groupValues?.getOrNull(1)?.let {
            dynamicCookies = dynamicCookies + ("cf_clearance" to it)
        }
        return response
    }

    suspend fun post(url: String, data: Map<String, String>): NiceResponse {
        val response = app.post(
            url,
            data = data,
            headers = CinemaCityConstans.REQUEST_HEADERS + mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to CinemaCityConstans.MAIN_URL
            ),
            cookies = dynamicCookies,
            interceptor = cloudflareInterceptor
        )
        if (response.cookies.isNotEmpty()) dynamicCookies = dynamicCookies + response.cookies
        return response
    }

    fun encodeQuery(query: String): String = URLEncoder.encode(query, "UTF-8")

    fun absoluteUrl(url: String?): String? {
        val value = url?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("/") -> CinemaCityConstans.MAIN_URL + value
            else -> CinemaCityConstans.MAIN_URL + "/" + value
        }
    }

    fun isDetailUrl(url: String): Boolean {
        return (url.contains("/movies/", true) || url.contains("/tv-series/", true)) &&
            url.endsWith(".html", true)
    }

    fun isTvSeries(url: String, title: String? = null): Boolean {
        return url.contains("/tv-series/", true) || title.orEmpty().contains(Regex("S\\d+|Season", RegexOption.IGNORE_CASE))
    }

    fun cleanTitle(title: String): String {
        return title
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\s*(Watch|Details|Download|Play)\\s*$", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    fun parseYear(text: String?): Int? {
        return Regex("(?:19|20)\\d{2}").find(text.orEmpty())?.value?.toIntOrNull()
    }

    fun parseSeasonNumber(text: String?, fallback: Int? = null): Int? {
        return Regex("(?:Season|S)\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(text.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: text.orEmpty().filter { it.isDigit() }.toIntOrNull()
            ?: fallback
    }

    fun parseEpisodeNumber(text: String?, fallback: Int? = null): Int? {
        return Regex("(?:Episode|E)\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(text.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: text.orEmpty().filter { it.isDigit() }.toIntOrNull()
            ?: fallback
    }

    fun decodeAtobScripts(html: String): List<String> {
        val results = mutableListOf<String>()
        val patterns = listOf(
            Regex("eval\\s*\\(\\s*atob\\s*\\(\\s*['\\\"]([^'\\\"]+)['\\\"]\\s*\\)\\s*\\)", RegexOption.DOT_MATCHES_ALL),
            Regex("atob\\s*\\(\\s*['\\\"]([^'\\\"]+)['\\\"]\\s*\\)", RegexOption.DOT_MATCHES_ALL)
        )
        patterns.forEach { pattern ->
            pattern.findAll(html).forEach { match ->
                val raw = match.groupValues.getOrNull(1).orEmpty()
                runCatching { base64Decode(raw) }.getOrNull()?.takeIf { it.isNotBlank() }?.let(results::add)
            }
        }
        return results.distinct()
    }

    fun unescapeSource(value: String): String {
        return value
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\'", "'")
            .trim()
    }
}

private class CinemaCityCloudflareInterceptor(
    private val cloudflareKiller: CloudflareKiller
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code == 403 || response.code == 503) {
            val body = response.peekBody(1024 * 1024).string()
            val html = Jsoup.parse(body).html()
            if (html.contains("Just a moment", true) || html.contains("cf-browser-verification", true)) {
                response.close()
                return cloudflareKiller.intercept(chain)
            }
        }
        return response
    }
}
