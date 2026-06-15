package com.sad25kag.betbetlivetv

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class BetbetLiveTvProvider : MainAPI() {
    override var mainUrl = "https://globetv.app"
    override var name = "Betbet Live TV"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(
        "id" to "Indonesia",
        "my" to "Malaysia",
        "sg" to "Singapore",
        "ph" to "Philippines",
        "th" to "Thailand",
        "vn" to "Vietnam",
        "jp" to "Japan",
        "kr" to "South Korea",
        "in" to "India",
        "us" to "United States"
    )

    private val cache = ConcurrentHashMap<String, List<LiveChannel>>()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val country = countryByCode(request.data) ?: return newHomePageResponse(
            HomePageList(request.name, emptyList(), isHorizontalImages = true),
            hasNext = false
        )

        val channels = runCatching { channelsFor(country) }
            .onFailure { logError(it) }
            .getOrDefault(emptyList())

        val start = ((page.coerceAtLeast(1) - 1) * PAGE_SIZE).coerceAtLeast(0)
        val pageItems = channels
            .drop(start)
            .take(PAGE_SIZE)
            .map { it.toSearchResponse() }

        return newHomePageResponse(
            HomePageList(request.name, pageItems, isHorizontalImages = true),
            hasNext = channels.size > start + PAGE_SIZE
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim().lowercase(Locale.ROOT)
        if (keyword.length < 2) return emptyList()

        val matches = mutableListOf<LiveChannel>()
        for (country in countries) {
            val countryChannels = runCatching { channelsFor(country) }.getOrDefault(emptyList())
            matches += countryChannels.filter { channel ->
                channel.name.lowercase(Locale.ROOT).contains(keyword) ||
                    channel.tvgId.lowercase(Locale.ROOT).contains(keyword) ||
                    channel.country.name.lowercase(Locale.ROOT).contains(keyword)
            }
            if (matches.size >= SEARCH_LIMIT * 2) break
        }

        return matches
            .distinctBy { it.stableId }
            .take(SEARCH_LIMIT)
            .map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val countryCode = url.substringAfter("/channel/", "")
            .substringBefore("/")
            .ifBlank { throw ErrorLoadingException("Kode negara channel tidak ditemukan.") }

        val channelId = url.substringAfter("/channel/$countryCode/", "")
            .substringBefore("?")
            .substringBefore("#")
            .decodeUrl()
            .ifBlank { throw ErrorLoadingException("ID channel tidak ditemukan.") }

        val country = countryByCode(countryCode)
            ?: throw ErrorLoadingException("Negara tidak didukung: $countryCode")

        val channel = channelsFor(country).firstOrNull { it.stableId == channelId }
            ?: throw ErrorLoadingException("Channel tidak ditemukan atau terfilter demi safety repo.")

        return newLiveStreamLoadResponse(
            name = channel.displayName,
            url = channel.detailUrl,
            dataUrl = channel.toJson()
        ).apply {
            posterUrl = channel.posterUrl
            plot = buildString {
                append(channel.country.name)
                if (channel.groupTitle.isNotBlank()) append(" • ").append(channel.groupTitle)
                if (channel.label.isNotBlank()) append(" • ").append(channel.label)
                append("\n\n")
                append("Public live TV entry parsed at runtime from IPTV-org stream data. ")
                append("No login, account sharing, private cookie, token bypass, DRM bypass, proxy, or restreaming is used.")
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val channel = LiveChannel.fromJson(data) ?: return false
        if (!channel.isRepoSafe()) return false

        val headers = channel.playbackHeaders()
        callback.invoke(
            newExtractorLink(
                source = name,
                name = "${channel.displayName} - Live",
                url = channel.streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                quality = channel.quality
                referer = headers["Referer"] ?: channel.referrer.ifBlank { mainUrl }
                this.headers = headers
            }
        )

        return true
    }

    private suspend fun channelsFor(country: Country): List<LiveChannel> {
        cache[country.code]?.let { return it }

        val text = app.get(
            "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/${country.code}.m3u",
            headers = mapOf(
                "Accept" to "application/vnd.apple.mpegurl,text/plain,*/*",
                "User-Agent" to USER_AGENT
            ),
            timeout = 30L
        ).text

        val parsed = parseM3u(text, country)
            .filter { it.isRepoSafe() }
            .distinctBy { it.stableId }
            .sortedWith(compareBy({ it.groupTitle.ifBlank { "~" } }, { it.name }))

        cache[country.code] = parsed
        return parsed
    }

    private fun parseM3u(text: String, country: Country): List<LiveChannel> {
        val result = mutableListOf<LiveChannel>()
        var currentInfo: String? = null
        var currentName: String? = null
        var currentAttrs: Map<String, String> = emptyMap()
        var pendingReferrer = ""
        var pendingUserAgent = ""

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    currentInfo = line
                    currentName = line.substringAfterLast(',', missingDelimiterValue = "").trim()
                    currentAttrs = parseAttributes(line)
                    pendingReferrer = ""
                    pendingUserAgent = ""
                }

                line.startsWith("#EXTVLCOPT:http-referrer=", ignoreCase = true) -> {
                    pendingReferrer = line.substringAfter("=", "").trim()
                }

                line.startsWith("#EXTVLCOPT:http-user-agent=", ignoreCase = true) -> {
                    pendingUserAgent = line.substringAfter("=", "").trim()
                }

                line.startsWith("http://", ignoreCase = true) || line.startsWith("https://", ignoreCase = true) -> {
                    val rawName = currentName.orEmpty().ifBlank { currentAttrs["tvg-id"].orEmpty() }
                    if (rawName.isNotBlank()) {
                        result += LiveChannel(
                            name = rawName.cleanChannelName(),
                            rawName = rawName,
                            tvgId = currentAttrs["tvg-id"].orEmpty(),
                            tvgLogo = currentAttrs["tvg-logo"].orEmpty(),
                            groupTitle = currentAttrs["group-title"].orEmpty(),
                            streamUrl = line,
                            referrer = pendingReferrer,
                            userAgent = pendingUserAgent,
                            label = currentInfo.orEmpty().extractBracketLabels(),
                            country = country
                        )
                    }
                    currentInfo = null
                    currentName = null
                    currentAttrs = emptyMap()
                    pendingReferrer = ""
                    pendingUserAgent = ""
                }
            }
        }

        return result
    }

    private fun parseAttributes(line: String): Map<String, String> {
        return ATTR_REGEX.findAll(line)
            .associate { match -> match.groupValues[1].lowercase(Locale.ROOT) to match.groupValues[2].trim() }
    }

    private fun countryByCode(code: String): Country? {
        return countries.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }

    private fun LiveChannel.toSearchResponse(): SearchResponse {
        return newLiveSearchResponse(
            name = displayName,
            url = detailUrl,
            type = TvType.Live
        ).apply {
            posterUrl = this@toSearchResponse.posterUrl
            lang = country.code
        }
    }

    private data class Country(
        val code: String,
        val name: String
    )

    private data class LiveChannel(
        val name: String,
        val rawName: String,
        val tvgId: String,
        val tvgLogo: String,
        val groupTitle: String,
        val streamUrl: String,
        val referrer: String,
        val userAgent: String,
        val label: String,
        val country: Country
    ) {
        val stableId: String by lazy {
            listOf(tvgId, name, groupTitle, label)
                .joinToString("|")
                .ifBlank { name }
        }

        val displayName: String by lazy {
            buildString {
                append(name)
                if (label.isNotBlank()) append(" ").append(label)
                append(" • ").append(country.name)
            }.trim()
        }

        val detailUrl: String by lazy {
            "https://globetv.app/channel/${country.code}/${stableId.encodeUrl()}"
        }

        val posterUrl: String? by lazy {
            fixUrlNull(tvgLogo).takeUnless { it.isNullOrBlank() } ?: faviconFromStream()
        }

        val quality: Int by lazy {
            val text = "$rawName $label $streamUrl".lowercase(Locale.ROOT)
            when {
                text.contains("2160") || text.contains("4k") -> Qualities.P2160.value
                text.contains("1440") -> Qualities.P1440.value
                text.contains("1080") -> Qualities.P1080.value
                text.contains("720") -> Qualities.P720.value
                text.contains("576") -> Qualities.P480.value
                text.contains("480") -> Qualities.P480.value
                text.contains("360") -> Qualities.P360.value
                text.contains("240") -> Qualities.P240.value
                else -> Qualities.Unknown.value
            }
        }

        fun isRepoSafe(): Boolean {
            val url = streamUrl.trim()
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return false
            if (!url.lowercase(Locale.ROOT).substringBefore("?").endsWith(".m3u8")) return false

            val haystack = listOf(name, rawName, tvgId, groupTitle, label, streamUrl)
                .joinToString(" ")
                .lowercase(Locale.ROOT)

            if (BLOCKED_FLAGS.any { haystack.contains(it) }) return false
            if (PREMIUM_OR_RISKY_KEYWORDS.any { haystack.contains(it) }) return false
            if (ADULT_KEYWORDS.any { haystack.contains(it) }) return false
            if (SUSPICIOUS_URL_PARTS.any { streamUrl.lowercase(Locale.ROOT).contains(it) }) return false
            if (CREDENTIAL_URL_REGEX.containsMatchIn(streamUrl)) return false

            return true
        }

        fun playbackHeaders(): Map<String, String> {
            val origin = referrer.originOrNull()
            return buildMap {
                put("Accept", "application/vnd.apple.mpegurl,application/x-mpegURL,*/*")
                put("User-Agent", userAgent.ifBlank { USER_AGENT })
                if (referrer.isNotBlank()) put("Referer", referrer)
                if (!origin.isNullOrBlank()) put("Origin", origin)
            }
        }

        fun toJson(): String {
            return JSONObject().apply {
                put("name", name)
                put("rawName", rawName)
                put("tvgId", tvgId)
                put("tvgLogo", tvgLogo)
                put("groupTitle", groupTitle)
                put("streamUrl", streamUrl)
                put("referrer", referrer)
                put("userAgent", userAgent)
                put("label", label)
                put("countryCode", country.code)
                put("countryName", country.name)
            }.toString()
        }

        private fun faviconFromStream(): String? {
            val host = runCatching { URI(streamUrl).host }.getOrNull()?.removePrefix("www.")
            return host?.takeIf { it.isNotBlank() }?.let {
                "https://www.google.com/s2/favicons?domain=$it&sz=128"
            }
        }

        companion object {
            fun fromJson(text: String): LiveChannel? {
                return runCatching {
                    val json = JSONObject(text)
                    val country = Country(
                        code = json.optString("countryCode"),
                        name = json.optString("countryName")
                    )
                    LiveChannel(
                        name = json.optString("name"),
                        rawName = json.optString("rawName"),
                        tvgId = json.optString("tvgId"),
                        tvgLogo = json.optString("tvgLogo"),
                        groupTitle = json.optString("groupTitle"),
                        streamUrl = json.optString("streamUrl"),
                        referrer = json.optString("referrer"),
                        userAgent = json.optString("userAgent"),
                        label = json.optString("label"),
                        country = country
                    )
                }.getOrNull()
            }
        }
    }

    companion object {
        private const val PAGE_SIZE = 60
        private const val SEARCH_LIMIT = 80

        private val countries = listOf(
            Country("id", "Indonesia"),
            Country("my", "Malaysia"),
            Country("sg", "Singapore"),
            Country("ph", "Philippines"),
            Country("th", "Thailand"),
            Country("vn", "Vietnam"),
            Country("jp", "Japan"),
            Country("kr", "South Korea"),
            Country("in", "India"),
            Country("us", "United States")
        )

        private val ATTR_REGEX = Regex("""([a-zA-Z0-9_-]+)="([^"]*)""")

        private val BLOCKED_FLAGS = listOf(
            "[geo-blocked]",
            "geo-blocked",
            "[not 24/7]",
            "not 24/7",
            "drm",
            "widevine",
            "login required",
            "subscription",
            "premium"
        )

        private val PREMIUM_OR_RISKY_KEYWORDS = listOf(
            "bein", "beinsports", "spotv", "espn", "fox sports", "sky sports", "bt sport",
            "dazn", "eurosport", "supersport", "premier league", "champions league", "laliga",
            "serie a", "bundesliga", "nba", "nfl", "mlb", "ufc", "wwe", "f1", "formula 1",
            "hbo", "cinemax", "showtime", "starz", "disney", "netflix", "prime video", "paramount",
            "cinema", "bioskop", "movie channel", "pay-per-view", "ppv", "astro supersport",
            "fox movies", "warner tv", "axn", "thrill", "hits movies"
        )

        private val ADULT_KEYWORDS = listOf(
            "adult", "xxx", "porn", "sex", "erotic", "playboy", "brazzers", "redtube"
        )

        private val SUSPICIOUS_URL_PARTS = listOf(
            "primestreams", "xtream", "stalker", "get.php?username=", "/movie/", "/series/",
            "/live/mookie", "m3u_plus", "type=m3u", "output=ts", "output=m3u8"
        )

        private val CREDENTIAL_URL_REGEX = Regex("""https?://[^/\s]+:[^/\s]+@""", RegexOption.IGNORE_CASE)
    }
}

private fun String.cleanChannelName(): String {
    return replace(Regex("""\[[^\]]+]"""), "")
        .replace(Regex("""\([^)]*p\)""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

private fun String.extractBracketLabels(): String {
    return Regex("""\[[^\]]+]""")
        .findAll(this)
        .map { it.value }
        .filterNot { label ->
            val lower = label.lowercase(Locale.ROOT)
            lower.contains("not 24/7") || lower.contains("geo-blocked")
        }
        .joinToString(" ")
        .trim()
}

private fun String.encodeUrl(): String {
    return URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}

private fun String.decodeUrl(): String {
    return URLDecoder.decode(this, "UTF-8")
}

private fun String.originOrNull(): String? {
    return runCatching {
        val uri = URI(this)
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        "$scheme://$host$port"
    }.getOrNull()
}
