package com.sad25kag.iptvorg

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
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class IPTVOrgProvider : MainAPI() {
    override var mainUrl = "https://iptv-org.github.io/iptv"
    override var name = "IPTV-org"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(
        "id" to "🇮🇩 Indonesia",
        "my" to "🇲🇾 Malaysia",
        "sg" to "🇸🇬 Singapore",
        "ph" to "🇵🇭 Philippines",
        "th" to "🇹🇭 Thailand",
        "vn" to "🇻🇳 Vietnam",
        "jp" to "🇯🇵 Japan",
        "kr" to "🇰🇷 South Korea",
        "in" to "🇮🇳 India",
        "us" to "🇺🇸 United States"
    )

    private val channelCache = ConcurrentHashMap<String, List<LiveChannel>>()

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
        for (country in COUNTRIES) {
            val countryMatches = runCatching { channelsFor(country) }
                .getOrDefault(emptyList())
                .filter { channel ->
                    channel.name.lowercase(Locale.ROOT).contains(keyword) ||
                        channel.tvgId.lowercase(Locale.ROOT).contains(keyword) ||
                        channel.groupTitle.lowercase(Locale.ROOT).contains(keyword) ||
                        channel.country.name.lowercase(Locale.ROOT).contains(keyword)
                }
            matches += countryMatches
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
            ?: throw ErrorLoadingException("Channel tidak ditemukan atau stream terfilter.")

        return newLiveStreamLoadResponse(
            name = channel.displayName,
            url = channel.detailUrl,
            dataUrl = channel.toJson()
        ).apply {
            posterUrl = channel.posterUrl
            plot = buildString {
                append(channel.country.flag).append(" ").append(channel.country.name)
                if (channel.groupTitle.isNotBlank()) append(" • ").append(channel.groupTitle)
                if (channel.tvgId.isNotBlank()) append("\nID: ").append(channel.tvgId)
                append("\nStream publik dari playlist resmi IPTV-org.")
                append("\nMirror tersedia: ").append(channel.safeStreams().size)
                append("\n\nNo login, account sharing, private cookie, private token, DRM bypass, proxy, or restreaming.")
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
        val streams = channel.safeStreams().distinctBy { it.streamUrl }

        streams.forEachIndexed { index, stream ->
            val headers = stream.playbackHeaders()
            val linkType = if (stream.streamUrl.lowercase(Locale.ROOT).contains(".m3u8")) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = buildString {
                        append(channel.name)
                        if (streams.size > 1) append(" - Mirror ").append(index + 1)
                        append(" - IPTV-org")
                    },
                    url = stream.streamUrl,
                    type = linkType
                ) {
                    quality = stream.quality(channel)
                    referer = headers["Referer"] ?: stream.referrer.ifBlank { mainUrl }
                    this.headers = headers
                }
            )
        }

        return streams.isNotEmpty()
    }

    private suspend fun channelsFor(country: Country): List<LiveChannel> {
        channelCache[country.code]?.let { return it }

        val parsed = playlistUrls(country).firstNotNullOfOrNull { playlistUrl ->
            runCatching {
                val text = app.get(
                    playlistUrl,
                    headers = mapOf(
                        "Accept" to "application/vnd.apple.mpegurl,text/plain,*/*",
                        "User-Agent" to USER_AGENT
                    ),
                    timeout = 30L
                ).text

                parseM3u(text, country)
                    .filter { it.safeStreams().isNotEmpty() }
                    .takeIf { it.isNotEmpty() }
            }.onFailure { logError(it) }.getOrNull()
        }
            .orEmpty()
            .sortedWith(compareBy({ it.groupTitle.ifBlank { "~" } }, { it.name }))

        channelCache[country.code] = parsed
        return parsed
    }

    private fun playlistUrls(country: Country): List<String> {
        return listOf(
            "https://iptv-org.github.io/iptv/countries/${country.code}.m3u",
            "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/${country.code}.m3u"
        )
    }

    private fun parseM3u(text: String, country: Country): List<LiveChannel> {
        val entries = mutableListOf<ParsedEntry>()
        var currentInfo = ""
        var currentName = ""
        var currentAttrs: Map<String, String> = emptyMap()
        var pendingReferrer = ""
        var pendingUserAgent = ""
        var pendingKodiProp = ""

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    currentInfo = line
                    currentName = line.substringAfterLast(',', missingDelimiterValue = "").trim()
                    currentAttrs = parseAttributes(line)
                    pendingReferrer = ""
                    pendingUserAgent = ""
                    pendingKodiProp = ""
                }

                line.startsWith("#EXTVLCOPT:http-referrer=", ignoreCase = true) -> {
                    pendingReferrer = line.substringAfter("=", "").trim()
                }

                line.startsWith("#EXTVLCOPT:http-user-agent=", ignoreCase = true) -> {
                    pendingUserAgent = line.substringAfter("=", "").trim()
                }

                line.startsWith("#KODIPROP", ignoreCase = true) -> {
                    pendingKodiProp = buildString {
                        append(pendingKodiProp)
                        append(' ')
                        append(line)
                    }
                }

                line.startsWith("http://", ignoreCase = true) || line.startsWith("https://", ignoreCase = true) -> {
                    val tvgId = currentAttrs["tvg-id"].orEmpty()
                    val groupTitle = currentAttrs["group-title"].orEmpty()
                    val rawName = currentName.ifBlank { tvgId }
                    val cleanName = rawName.cleanChannelName()
                    val explicitLogo = currentAttrs["tvg-logo"].orEmpty().toSafeUrl()

                    if (cleanName.isNotBlank()) {
                        entries += ParsedEntry(
                            stableId = stableChannelId(tvgId, cleanName, groupTitle),
                            name = cleanName,
                            rawName = rawName,
                            tvgId = tvgId,
                            tvgLogo = explicitLogo.orEmpty(),
                            groupTitle = groupTitle,
                            stream = LiveStream(
                                streamUrl = line,
                                referrer = pendingReferrer,
                                userAgent = pendingUserAgent,
                                label = currentInfo.extractBracketLabels(),
                                rawInfo = "$currentInfo $pendingKodiProp".trim()
                            )
                        )
                    }

                    currentInfo = ""
                    currentName = ""
                    currentAttrs = emptyMap()
                    pendingReferrer = ""
                    pendingUserAgent = ""
                    pendingKodiProp = ""
                }
            }
        }

        return entries
            .groupBy { it.stableId }
            .mapNotNull { (_, group) ->
                val first = group.firstOrNull() ?: return@mapNotNull null
                val logo = group.firstNotNullOfOrNull { it.tvgLogo.takeIf { value -> value.isNotBlank() } }.orEmpty()
                LiveChannel(
                    name = first.name,
                    rawName = first.rawName,
                    tvgId = first.tvgId,
                    tvgLogo = logo,
                    groupTitle = first.groupTitle,
                    country = country,
                    streams = group.map { it.stream }
                        .filter { it.isRepoSafe(first.name, first.rawName, first.tvgId, first.groupTitle) }
                        .distinctBy { it.streamUrl }
                )
            }
            .filter { it.streams.isNotEmpty() }
            .distinctBy { it.stableId }
    }

    private fun parseAttributes(line: String): Map<String, String> {
        return ATTR_REGEX.findAll(line)
            .associate { match -> match.groupValues[1].lowercase(Locale.ROOT) to match.groupValues[2].trim() }
    }

    private fun countryByCode(code: String): Country? {
        return COUNTRIES.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }

    private fun LiveChannel.toSearchResponse(): SearchResponse {
        return newLiveSearchResponse(
            name = displayName,
            url = detailUrl,
            type = TvType.Live
        ).apply {
            posterUrl = this@toSearchResponse.posterUrl
            lang = null
        }
    }

    private data class Country(
        val code: String,
        val name: String,
        val flag: String
    )

    private data class ParsedEntry(
        val stableId: String,
        val name: String,
        val rawName: String,
        val tvgId: String,
        val tvgLogo: String,
        val groupTitle: String,
        val stream: LiveStream
    )

    private data class LiveChannel(
        val name: String,
        val rawName: String,
        val tvgId: String,
        val tvgLogo: String,
        val groupTitle: String,
        val country: Country,
        val streams: List<LiveStream>
    ) {
        val stableId: String by lazy {
            stableChannelId(tvgId, name, groupTitle)
        }

        val displayName: String by lazy {
            buildString {
                append(country.flag).append(" ").append(name)
                append(" • ").append(country.name)
            }
        }

        val detailUrl: String by lazy {
            "https://iptv-org.github.io/iptv/channel/${country.code}/${stableId.encodeUrl()}"
        }

        val posterUrl: String? by lazy {
            tvgLogo.toSafeUrl()
        }

        fun safeStreams(): List<LiveStream> {
            return streams.filter { stream ->
                stream.isRepoSafe(name, rawName, tvgId, groupTitle)
            }
        }

        fun toJson(): String {
            return JSONObject().apply {
                put("name", name)
                put("rawName", rawName)
                put("tvgId", tvgId)
                put("tvgLogo", tvgLogo)
                put("groupTitle", groupTitle)
                put("countryCode", country.code)
                put("countryName", country.name)
                put("countryFlag", country.flag)
                put(
                    "streams",
                    JSONArray().apply {
                        streams.forEach { stream -> put(stream.toJsonObject()) }
                    }
                )
            }.toString()
        }

        companion object {
            fun fromJson(text: String): LiveChannel? {
                return runCatching {
                    val json = JSONObject(text)
                    val country = Country(
                        code = json.optString("countryCode"),
                        name = json.optString("countryName"),
                        flag = json.optString("countryFlag")
                    )
                    val array = json.optJSONArray("streams") ?: JSONArray()
                    val streams = buildList {
                        for (index in 0 until array.length()) {
                            val item = array.optJSONObject(index) ?: continue
                            add(LiveStream.fromJsonObject(item))
                        }
                    }
                    LiveChannel(
                        name = json.optString("name"),
                        rawName = json.optString("rawName"),
                        tvgId = json.optString("tvgId"),
                        tvgLogo = json.optString("tvgLogo"),
                        groupTitle = json.optString("groupTitle"),
                        country = country,
                        streams = streams
                    )
                }.getOrNull()
            }
        }
    }

    private data class LiveStream(
        val streamUrl: String,
        val referrer: String,
        val userAgent: String,
        val label: String,
        val rawInfo: String
    ) {
        fun isRepoSafe(
            channelName: String,
            rawName: String,
            tvgId: String,
            groupTitle: String
        ): Boolean {
            val url = streamUrl.trim()
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return false
            if (!url.isSupportedStreamUrl()) return false

            val haystack = listOf(channelName, rawName, tvgId, groupTitle, label, rawInfo, url)
                .joinToString(" ")
                .lowercase(Locale.ROOT)

            if (BLOCKED_FLAGS.any { haystack.contains(it) }) return false
            if (ADULT_KEYWORD_REGEX.containsMatchIn(haystack)) return false
            if (CREDENTIAL_URL_REGEX.containsMatchIn(url)) return false
            if (SUSPICIOUS_URL_PARTS.any { url.lowercase(Locale.ROOT).contains(it) }) return false

            return true
        }

        fun playbackHeaders(): Map<String, String> {
            val origin = referrer.originOrNull()
            return buildMap {
                put("Accept", "application/vnd.apple.mpegurl,application/x-mpegURL,video/*,*/*")
                put("User-Agent", userAgent.ifBlank { USER_AGENT })
                if (referrer.isNotBlank()) put("Referer", referrer)
                if (!origin.isNullOrBlank()) put("Origin", origin)
            }
        }

        fun quality(channel: LiveChannel): Int {
            val text = "${channel.rawName} ${channel.name} $label $rawInfo $streamUrl".lowercase(Locale.ROOT)
            return when {
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

        fun toJsonObject(): JSONObject {
            return JSONObject().apply {
                put("streamUrl", streamUrl)
                put("referrer", referrer)
                put("userAgent", userAgent)
                put("label", label)
                put("rawInfo", rawInfo)
            }
        }

        companion object {
            fun fromJsonObject(json: JSONObject): LiveStream {
                return LiveStream(
                    streamUrl = json.optString("streamUrl"),
                    referrer = json.optString("referrer"),
                    userAgent = json.optString("userAgent"),
                    label = json.optString("label"),
                    rawInfo = json.optString("rawInfo")
                )
            }
        }
    }

    companion object {
        private const val PAGE_SIZE = 60
        private const val SEARCH_LIMIT = 100

        private val COUNTRIES = listOf(
            Country("id", "Indonesia", "🇮🇩"),
            Country("my", "Malaysia", "🇲🇾"),
            Country("sg", "Singapore", "🇸🇬"),
            Country("ph", "Philippines", "🇵🇭"),
            Country("th", "Thailand", "🇹🇭"),
            Country("vn", "Vietnam", "🇻🇳"),
            Country("jp", "Japan", "🇯🇵"),
            Country("kr", "South Korea", "🇰🇷"),
            Country("in", "India", "🇮🇳"),
            Country("us", "United States", "🇺🇸")
        )

        private val ATTR_REGEX = Regex("""([a-zA-Z0-9_-]+)="([^"]*)"""")

        private val BLOCKED_FLAGS = listOf(
            "drm",
            "widevine",
            "license_type",
            "login required",
            "subscription",
            "premium",
            "pay-per-view",
            "ppv"
        )

        private val ADULT_KEYWORD_REGEX = Regex(
            """(^|[^a-z0-9])(adult|xxx|porn|sex|erotic|playboy|brazzers|redtube)([^a-z0-9]|$)""",
            RegexOption.IGNORE_CASE
        )

        private val CREDENTIAL_URL_REGEX = Regex(
            """([?&](username|password|token|auth|key)=|/get\.php\?)""",
            RegexOption.IGNORE_CASE
        )

        private val SUSPICIOUS_URL_PARTS = listOf(
            "xtream",
            "stalker",
            "m3u_plus",
            "output=ts",
            "output=m3u8"
        )
    }
}

private fun stableChannelId(tvgId: String, name: String, groupTitle: String): String {
    val base = tvgId.baseTvgId().ifBlank { "$name-$groupTitle" }
    return base
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-')
        .ifBlank { name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-') }
}

private fun String.baseTvgId(): String {
    return trim()
        .substringBefore("@")
        .substringBefore("#")
        .trim()
}

private fun String.cleanChannelName(): String {
    return replace(Regex("""\s*\[[^]]*]\s*"""), " ")
        .replace(Regex("""\s*\([^)]*\)\s*"""), " ")
        .replace(Regex("\s+"), " ")
        .trim()
}

private fun String.extractBracketLabels(): String {
    return Regex("""\[[^]]*]""")
        .findAll(this)
        .joinToString(" ") { it.value.trim() }
}

private fun String.isSupportedStreamUrl(): Boolean {
    val clean = substringBefore("?").lowercase(Locale.ROOT)
    return clean.endsWith(".m3u8") || clean.endsWith(".mp4") || contains(".m3u8", ignoreCase = true)
}

private fun String.toSafeUrl(): String? {
    val value = trim()
    if (!value.startsWith("http://", true) && !value.startsWith("https://", true)) return null
    return value
}

private fun String.encodeUrl(): String {
    return URLEncoder.encode(this, Charsets.UTF_8.name())
}

private fun String.decodeUrl(): String {
    return runCatching { URLDecoder.decode(this, Charsets.UTF_8.name()) }.getOrDefault(this)
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
