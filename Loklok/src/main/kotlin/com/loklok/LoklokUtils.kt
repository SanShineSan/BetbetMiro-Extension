package com.loklok

import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

object LoklokUtils {
    private const val TAG = "Loklok"
    const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.6422.165 Mobile Safari/537.36"

    val deviceId: String by lazy { generateDeviceId() }

    private fun generateDeviceId(length: Int = 16): String {
        val chars = ('a'..'f') + ('0'..'9')
        return (1..length).map { chars.random() }.joinToString("")
    }

    private fun generateSign(timestamp: Long): String {
        val raw = "FrontEnd${timestamp}5I7MD1O9GI"
        return md5(raw)
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun encode(input: String): String =
        URLEncoder.encode(input, "utf-8").replace("+", "%20")

    fun cleanText(value: String?): String = value.orEmpty()
        .replace("\u00a0", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun normalizeId(value: Any?): String? {
        return when (value) {
            null -> null
            is Number -> value.toLong().toString()
            is String -> value.trim().takeIf { it.isNotBlank() && it != "null" && it != "undefined" }
            else -> value.toString().trim().takeIf { it.isNotBlank() && it != "null" && it != "undefined" }
        }
    }

    fun parseUrlData(raw: String): UrlData? {
        runCatching { parseJson<UrlData>(raw) }
            .getOrNull()
            ?.takeIf { !it.id.isNullOrBlank() }
            ?.let { return it }

        runCatching {
            val obj = JSONObject(raw)
            val id = obj.optString("id").takeIf { it.isNotBlank() && it != "null" }
            val category = when {
                obj.has("category") -> obj.optInt("category", 1)
                obj.has("domainType") -> obj.optInt("domainType", 1)
                else -> 1
            }
            id?.let { UrlData(it, category, "json-fallback") }
        }.getOrNull()?.let { return it }

        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        val id = Regex("""[?&](?:id|contentId|movieId)=([^&#]+)""")
            .find(decoded)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
        val category = Regex("""[?&](?:category|domainType)=(-?\d+)""")
            .find(decoded)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        return id?.takeIf { it.isNotBlank() }?.let { UrlData(it, category ?: 1, "url-fallback") }
    }

    fun proxyPoster(url: String?): String? {
        val poster = url.orEmpty().trim()
        if (poster.isBlank()) return null
        if (poster.startsWith("data:", ignoreCase = true)) return null
        return "${LoklokSeeds.IMAGE_PROXY}/?url=${encode(poster)}&w=175&h=246&fit=cover&output=webp"
    }

    fun apiHeaders(contentType: Boolean = false): Map<String, String> {
        val timestamp = System.currentTimeMillis()
        val headers = linkedMapOf(
            "lang" to "en",
            "versioncode" to "11132",
            "clienttype" to "web_h5",
            "platform" to "web",
            "deviceid" to deviceId,
            "timestamp" to timestamp.toString(),
            "sign" to generateSign(timestamp),
            "User-Agent" to BROWSER_UA,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Origin" to LoklokSeeds.H5_SITE,
            "Referer" to "${LoklokSeeds.H5_SITE}/",
            "Sec-Ch-Ua" to "\"Chromium\";v=\"125\", \"Not.A/Brand\";v=\"24\"",
            "Sec-Ch-Ua-Mobile" to "?1",
            "Sec-Ch-Ua-Platform" to "\"Android\"",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-site"
        )
        if (contentType) headers["Content-Type"] = "application/json"
        return headers
    }

    fun streamHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to BROWSER_UA,
            "Accept" to "*/*",
            "Origin" to LoklokSeeds.H5_SITE,
            "Referer" to "${LoklokSeeds.H5_SITE}/"
        )
    }

    suspend fun apiGet(path: String): String {
        val url = "${LoklokSeeds.API_WEB}/$path"
        Log.d(TAG, "apiGet: $url")
        val headers = apiHeaders()
        val res = app.get(url, headers = headers)
        Log.d(TAG, "apiGet response code: ${res.code}")
        if (res.code == 200) return res.text

        Log.d(TAG, "OkHttp blocked (${res.code}), trying WebView fetch")
        val wvResult = webViewApiCall(url, headers)
        if (wvResult != null) {
            Log.d(TAG, "WebView GET success, len=${wvResult.length}")
            return wvResult
        }
        throw Exception("API call failed for $path")
    }

    suspend fun apiPost(path: String, bodyJson: String, useV2: Boolean = false): String {
        val base = if (useV2) LoklokSeeds.API_H5_V2 else LoklokSeeds.API_WEB
        val url = "$base/$path"
        Log.d(TAG, "apiPost: $url")
        val headers = apiHeaders(contentType = true)
        val body = bodyJson.toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
        val res = app.post(url, requestBody = body, headers = headers)
        Log.d(TAG, "apiPost response code: ${res.code}")
        if (res.code == 200) return res.text

        Log.d(TAG, "OkHttp POST blocked (${res.code}), trying WebView fetch")
        val wvResult = webViewApiCall(url, headers, "POST", bodyJson)
        if (wvResult != null) {
            Log.d(TAG, "WebView POST success, len=${wvResult.length}")
            return wvResult
        }
        throw Exception("POST API call failed for $path")
    }

    private suspend fun webViewApiCall(
        url: String,
        headers: Map<String, String>,
        method: String = "GET",
        body: String? = null
    ): String? {
        val captured = AtomicReference<String?>(null)
        val headersJs = headers.entries.joinToString(",\n") { (key, value) ->
            "${JSONObject.quote(key)}: ${JSONObject.quote(value)}"
        }
        val fetchOptions = if (method == "POST" && body != null) {
            """
                method: 'POST',
                headers: {$headersJs},
                body: ${JSONObject.quote(body)}
            """.trimIndent()
        } else {
            """
                method: 'GET',
                headers: {$headersJs}
            """.trimIndent()
        }

        val script = """
            (function() {
                try {
                    if (window.__loklokApiResult) return window.__loklokApiResult;
                    if (!window.__loklokFetching) {
                        window.__loklokFetching = true;
                        fetch(${JSONObject.quote(url)}, { $fetchOptions })
                            .then(function(r) { return r.text(); })
                            .then(function(t) { window.__loklokApiResult = t; })
                            .catch(function(e) { window.__loklokApiResult = 'ERR:' + e.message; });
                    }
                    return null;
                } catch(e) { return 'ERR:' + e.message; }
            })()
        """.trimIndent()

        val resolver = WebViewResolver(
            interceptUrl = Regex("""__LOKLOK_WV_NEVER_MATCH__"""),
            additionalUrls = listOf(
                Regex(""".*\.js.*"""),
                Regex(""".*\.css.*"""),
                Regex(""".*\.png.*"""),
                Regex(""".*\.jpg.*"""),
                Regex(""".*event-tracking-project.*""")
            ),
            userAgent = BROWSER_UA,
            useOkhttp = false,
            script = script,
            scriptCallback = { result ->
                if (result != null && result.length > 5 && result != "null") {
                    if (result.startsWith("\"ERR:") || result.startsWith("ERR:")) {
                        Log.e(TAG, "WebView fetch error: $result")
                        captured.set(result)
                    } else {
                        val decoded = try {
                            org.json.JSONArray("[$result]").getString(0)
                        } catch (e: Exception) {
                            Log.e(TAG, "scriptCallback JSON decode failed: ${e.message}")
                            null
                        }
                        if (decoded != null && (decoded.contains("\"code\"") || decoded.contains("\"data\""))) {
                            if (captured.get() == null) {
                                Log.d(TAG, "scriptCallback: captured JSON, len=${decoded.length}")
                            }
                            captured.set(decoded)
                        }
                    }
                }
            },
            timeout = 30_000L
        )

        try {
            resolver.resolveUsingWebView(
                url = "${LoklokSeeds.H5_SITE}/",
                referer = LoklokSeeds.H5_SITE,
                method = "GET",
                requestCallBack = { captured.get() != null }
            )
        } catch (e: Exception) {
            Log.e(TAG, "webViewApiCall exception: ${e.message}")
        }

        val finalResult = captured.get()
        if (finalResult != null && (finalResult.startsWith("\"ERR:") || finalResult.startsWith("ERR:"))) {
            Log.e(TAG, "webViewApiCall failed with: $finalResult")
            return null
        }
        return finalResult
    }

    fun getQualityFromDefinition(quality: String?): Int {
        return when (quality.orEmpty().uppercase()) {
            "GROOT_FD", "360P" -> Qualities.P360.value
            "GROOT_LD", "480P" -> Qualities.P480.value
            "GROOT_SD", "720P" -> Qualities.P720.value
            "GROOT_HD", "1080P" -> Qualities.P1080.value
            else -> Qualities.Unknown.value
        }
    }

    private fun languageFromTwoLetters(code: String): String? {
        return when (code.lowercase()) {
            "id", "in" -> "Indonesian"
            "en" -> "English"
            "pt" -> "Portuguese"
            "ms" -> "Malay"
            "vi" -> "Vietnamese"
            "th" -> "Thai"
            "zh" -> "Chinese"
            "ar" -> "Arabic"
            "es" -> "Spanish"
            "fr" -> "French"
            "de" -> "German"
            "ja" -> "Japanese"
            "ko" -> "Korean"
            "it" -> "Italian"
            "tr" -> "Turkish"
            "ru" -> "Russian"
            "hi" -> "Hindi"
            "bn" -> "Bengali"
            "tl" -> "Tagalog"
            "fil" -> "Filipino"
            "my" -> "Burmese"
            "km" -> "Khmer"
            else -> null
        }
    }

    fun getLanguageName(abbr: String): String {
        return when (abbr) {
            "in_ID" -> "Indonesian"
            "pt" -> "Portuguese"
            "ms" -> "Malay"
            "vi" -> "Vietnamese"
            "th" -> "Thai"
            "zh-Hans", "zh_CN" -> "Chinese (Simplified)"
            "zh-Hant", "zh_TW" -> "Chinese (Traditional)"
            "ar" -> "Arabic"
            "es" -> "Spanish"
            "fr" -> "French"
            "de" -> "German"
            "ja" -> "Japanese"
            "ko" -> "Korean"
            else -> languageFromTwoLetters(abbr.split("_").first()) ?: abbr
        }
    }
}
