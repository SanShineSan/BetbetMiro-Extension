package com.kusonime

import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

object KusonimeUtils {
    private val duplicateSlashRegex = Regex("""(?<!:)//+""")
    private val rootRegex = Regex("""^https?://[^/]+""")

    val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache"
    )

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    fun decode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    fun buildPageUrl(template: String, page: Int): String {
        val clean = template.trim()
        if (clean.contains("%page%")) {
            val pagePart = if (page <= 1) "" else "page/$page/"
            return clean.replace("%page%", pagePart).replace(duplicateSlashRegex, "/")
        }
        if (page <= 1) return clean
        return clean.trimEnd('/') + "/page/$page/"
    }

    fun normalizeUrl(value: String?, baseUrl: String, mainUrl: String): String {
        val clean = value.orEmpty().cleanEscaped().trim().trim('"', '\'')
        if (clean.isBlank() || clean.equals("null", true)) return ""
        if (clean.startsWith("#") || clean.startsWith("javascript:", true) || clean.startsWith("data:", true)) return ""
        val normalized = when {
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> (rootRegex.find(baseUrl)?.value ?: mainUrl) + clean
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrDefault(clean)
        }
        return normalized.replace(duplicateSlashRegex, "/")
    }

    fun String.cleanEscaped(): String {
        return this
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&#8217;", "\u2019")
            .replace("&#8216;", "\u2018")
            .replace("&#8220;", "\u201C")
            .replace("&#8221;", "\u201D")
            .replace("&#8211;", "-")
            .replace("&#8212;", "-")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun cleanTitle(value: String): String {
        return value.cleanEscaped()
            .replace(Regex("(?i)^\\s*Download\\s+"), "")
            .replace(Regex("(?i)\\s+Subtitle\\s+Indonesia.*$"), "")
            .replace(Regex("(?i)\\s+Sub\\s+Indo.*$"), "")
            .replace(Regex("(?i)\\s+Batch\\s*$"), " Batch")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '|', ':')
    }

    fun getType(value: String?): TvType {
        val clean = value.orEmpty()
        return when {
            clean.contains("movie", true) || clean.contains("film", true) || clean.contains("live action", true) -> TvType.AnimeMovie
            clean.contains("ova", true) || clean.contains("ona", true) || clean.contains("special", true) -> TvType.OVA
            else -> TvType.Anime
        }
    }

    fun getStatus(value: String?): ShowStatus? {
        val clean = value.orEmpty()
        return when {
            clean.contains("ongoing", true) || clean.contains("berlangsung", true) || clean.contains("airing", true) -> ShowStatus.Ongoing
            clean.contains("completed", true) || clean.contains("tamat", true) || clean.contains("finished", true) -> ShowStatus.Completed
            else -> null
        }
    }

    fun parseYear(value: String?): Int? {
        return Regex("""(?:19|20)\d{2}""").find(value.orEmpty())?.value?.toIntOrNull()
    }

    fun parseEpisodeNumber(value: String?): Int? {
        val clean = value.orEmpty().cleanEscaped()
        listOf(
            Regex("""(?i)Total\s+Episode\s*:?\s*(\d{1,4})"""),
            Regex("""(?i)Episode\s*(\d{1,4})"""),
            Regex("""(?i)Eps?\s*(\d{1,4})""")
        ).forEach { regex ->
            regex.find(clean)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        return null
    }

    fun String.fixQuality(): Int {
        val clean = uppercase().replace(" ", "")
        return when {
            clean.contains("4K") || clean.contains("2160") -> Qualities.P2160.value
            clean.contains("1440") -> 1440
            clean.contains("1080") || clean.contains("FHD") -> Qualities.P1080.value
            clean.contains("720") || clean == "HD" -> Qualities.P720.value
            clean.contains("480") -> Qualities.P480.value
            clean.contains("360") -> Qualities.P360.value
            else -> clean.filter { it.isDigit() }.toIntOrNull() ?: Qualities.Unknown.value
        }
    }

    fun isVideoUrl(url: String): Boolean {
        return url.contains(Regex("""\.(m3u8|mp4|mkv|webm)(?:[?#].*)?$""", RegexOption.IGNORE_CASE))
    }

    fun isSubtitleUrl(url: String): Boolean {
        return url.contains(Regex("""\.(srt|vtt|ass)(?:[?#].*)?$""", RegexOption.IGNORE_CASE))
    }

    fun shouldSkipUrl(url: String): Boolean {
        val clean = url.lowercase()
        return clean.isBlank() ||
            clean.startsWith("#") ||
            clean.startsWith("javascript:") ||
            clean.contains("facebook.com") ||
            clean.contains("twitter.com") ||
            clean.contains("x.com") ||
            clean.contains("instagram.com") ||
            clean.contains("discord") ||
            clean.contains("plus.google.com") ||
            clean.contains("bit.ly") ||
            clean.contains("s.id") ||
            clean.contains("goo.gl") ||
            clean.contains("wp-content/uploads") && (clean.endsWith(".jpg") || clean.endsWith(".jpeg") || clean.endsWith(".png") || clean.endsWith(".webp") || clean.endsWith(".gif"))
    }

    fun isDownloadHost(url: String): Boolean {
        val clean = url.lowercase()
        return listOf(
            "acefile.co",
            "drive.google.com",
            "usercontent.google.com",
            "pixeldrain.com",
            "terabox",
            "hxfile.co",
            "mega.nz",
            "megaup.net",
            "uptobox.com",
            "kusoddl",
            "gofile.io",
            "krakenfiles.com",
            "mediafire.com",
            "racaty",
            "qiwi.gg",
            "filedon.co",
            "buzzheavier.com"
        ).any { clean.contains(it) }
    }

    fun isKusonimeDetailUrl(url: String, mainUrl: String): Boolean {
        val clean = url.substringBefore("#").trimEnd('/').lowercase()
        if (!clean.startsWith(mainUrl.lowercase())) return false
        val path = clean.substringAfter(mainUrl.lowercase(), "").trim('/')
        if (path.isBlank()) return false
        if (path.contains("/page/") || path.startsWith("page/")) return false
        if (path.startsWith("tag/") || path.startsWith("genre/") || path.startsWith("genres/")) return false
        if (path.startsWith("category/") || path.startsWith("seasons/") || path.startsWith("anime-list")) return false
        if (path.startsWith("anime-movie-list") || path.startsWith("daftar-live-action") || path.startsWith("seasons-list")) return false
        if (path.startsWith("dmca") || path.startsWith("privacy") || path.startsWith("contact") || path.startsWith("faq")) return false
        return path.length > 3
    }

    fun extractLabel(text: String, label: String): String? {
        val labels = listOf(
            "Japanese", "Genre", "Seasons", "Producers", "Type", "Status", "Total Episode",
            "Score", "Duration", "Released on", "Credit", "Cerita Utama", "Download", "Matikan ADBLOCK"
        )
        val alternatives = labels.filterNot { it.equals(label, true) }.joinToString("|") { Regex.escape(it) }
        val regex = Regex("(?is)${Regex.escape(label)}\\s*:?\\s*(.*?)(?=\\s*(?:$alternatives)\\s*:|\\s*Matikan ADBLOCK|$)")
        return regex.find(text.cleanEscaped())?.groupValues?.getOrNull(1)?.cleanEscaped()?.takeIf { it.isNotBlank() }
    }

    fun originOf(url: String): String {
        return runCatching {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}"
        }.getOrDefault(url.substringBefore("/", url))
    }
}
