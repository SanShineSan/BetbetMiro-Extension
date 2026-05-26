package com.reynime

import com.lagradost.cloudstream3.TvType
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.reynime.ReynimeUtils.cleanEscaped
import com.reynime.ReynimeUtils.cleanTitle
import com.reynime.ReynimeUtils.extractEpisodeNumber
import com.reynime.ReynimeUtils.extractSeriesId
import com.reynime.ReynimeUtils.extractSeriesSlug
import com.reynime.ReynimeUtils.normalizePoster
import com.reynime.ReynimeUtils.normalizeUrl
import com.reynime.ReynimeUtils.slugify

object ReynimeParser {
    val backendVideoKeys = listOf(
        "download_url",
        "downloadUrl",
        "video_url",
        "videoUrl",
        "reynime_video_url",
        "vip_video_url",
        "regular_video_url",
        "regular_video_url_1",
        "regular_video_url_2",
        "regular_video_url_3",
        "regular_video_url_4",
        "regular_video_url_5",
        "regular_video_url_6",
        "regular_video_url_7",
        "regular_video_url_8",
        "embed_url",
        "embedUrl",
        "player_url",
        "playerUrl",
        "stream_url",
        "streamUrl",
        "hls",
        "hls_url",
        "hlsUrl",
        "m3u8",
        "source",
        "src",
        "file",
        "url",
        "link",
        "video",
        "direct_url",
        "directUrl"
    )

    fun parseSeries(text: String, baseUrl: String, mainUrl: String): List<ReynimeSeries> {
        val clean = text.cleanEscaped()
        val jsonSeries = parseSeriesFromJson(clean, baseUrl, mainUrl)
        if (jsonSeries.isNotEmpty()) return jsonSeries

        val document = Jsoup.parse(clean, baseUrl)
        return parseSeriesFromDocument(document, baseUrl, mainUrl)
    }

    fun parseSeriesDetail(text: String, baseUrl: String, mainUrl: String): ReynimeSeries? {
        val clean = text.cleanEscaped()
        parseSeriesFromJson(clean, baseUrl, mainUrl).firstOrNull()?.let { return it }
        val document = Jsoup.parse(clean, baseUrl)
        val title = document.selectFirst("h1, [class*=title], [class*=Title]")?.text()?.cleanTitle()
            ?: document.selectFirst("meta[property=og:title], meta[name=twitter:title]")?.attr("content")?.cleanTitle()
            ?: return null
        if (isBadTitle(title)) return null

        val id = extractSeriesId(baseUrl) ?: extractSeriesId(clean) ?: return null
        val slug = extractSeriesSlug(baseUrl) ?: title.slugify()
        val poster = document.selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content")
            ?: document.select("img").firstNotNullOfOrNull { it.getImageAttr() }
        val description = document.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.cleanEscaped()
            ?: document.selectFirst("[class*=synopsis], [class*=description], [class*=desc], p")?.text()?.cleanEscaped()
            ?: "Streaming Donghua subtitle Indonesia di Reynime."
        val status = Regex("""(?:status|type)\s*[:=]\s*([A-Za-z ]+)""", RegexOption.IGNORE_CASE).find(clean)?.groupValues?.getOrNull(1)?.trim() ?: "Ongoing"
        val genres = extractGenreWords(clean)
        val latest = document.select("a, button, span, div")
            .mapNotNull { extractEpisodeNumber(it.text()) }
            .maxOrNull() ?: 1

        return ReynimeSeries(
            id = id,
            title = title,
            slug = slug,
            poster = normalizePoster(poster, baseUrl, mainUrl),
            kind = if (genres.any { it.equals("anime", true) }) "Anime" else "Donghua",
            status = status,
            type = TvType.Anime,
            latestEpisode = latest.coerceAtLeast(1),
            genres = genres,
            description = description
        )
    }

    fun parseApiEpisodeList(text: String): List<ReynimeApiEpisode> {
        val root = parseJsonRoot(text.cleanEscaped()) ?: return emptyList()
        val objects = mutableListOf<JSONObject>()
        collectJsonObjects(root, objects)

        return objects.mapNotNull { obj ->
            if (!obj.looksLikeEpisodeObject()) return@mapNotNull null
            val id = obj.intValue("episode_id", "episodeId", "watch_id", "pid", "post_id")
                ?: obj.intValue("id")?.takeIf { obj.looksLikeEpisodeObject() }
                ?: return@mapNotNull null
            val label = obj.stringValue("episode_title", "title", "name", "label", "judul")
            val ep = obj.intValue("episode", "episode_number", "episodeNumber", "number", "ep", "eps")
                ?: extractEpisodeNumber(label, id.toString())
                ?: id
            val urls = backendVideoKeys.mapNotNull { obj.stringValue(it) }
                .map { it.cleanEscaped().trim() }
                .filter { it.contains("http", true) || it.startsWith("//") }
                .distinct()
            ReynimeApiEpisode(
                id = id,
                episode = ep,
                title = label?.cleanTitle()?.takeIf { it.isNotBlank() } ?: "Episode $ep",
                poster = obj.stringValue("poster", "thumbnail", "image", "cover", "thumb"),
                description = obj.stringValue("description", "synopsis", "overview"),
                urls = urls
            )
        }
            .distinctBy { it.id }
            .sortedBy { it.episode }
    }

    fun parseBackendEpisodeRecords(text: String): List<ReynimeBackendEpisode> {
        val clean = text.cleanEscaped()
        val parsed = parseJsonRoot(clean)?.let { root ->
            val objects = mutableListOf<JSONObject>()
            collectJsonObjects(root, objects)
            objects.mapNotNull { obj -> obj.toBackendEpisodeRecord() }
        }.orEmpty()
        if (parsed.isNotEmpty()) return parsed

        return Regex("""\{[^{}]*}""")
            .findAll(clean)
            .map { it.value }
            .mapNotNull { raw ->
                val id = extractJsonValue(raw, "id") ?: extractJsonValue(raw, "episode_id") ?: extractJsonValue(raw, "pid")
                val seriesId = extractJsonValue(raw, "series_id") ?: extractJsonValue(raw, "seriesId")
                val ep = extractJsonValue(raw, "episode_number") ?: extractJsonValue(raw, "episode") ?: extractJsonValue(raw, "ep")
                val title = extractJsonValue(raw, "title") ?: extractJsonValue(raw, "name") ?: extractJsonValue(raw, "label")
                val urls = backendVideoKeys.mapNotNull { extractJsonValue(raw, it) }
                    .map { it.cleanEscaped().trim() }
                    .filter { it.contains("http", true) || it.startsWith("//") }
                    .distinct()
                if (id == null && seriesId == null && ep == null && urls.isEmpty()) null else ReynimeBackendEpisode(id, seriesId, ep, title, urls = urls)
            }
            .toList()
    }

    fun parseJsonRoot(text: String): Any? {
        val clean = text.trim().cleanEscaped()
        if (clean.isBlank()) return null
        return runCatching {
            when {
                clean.startsWith("{") -> JSONObject(clean)
                clean.startsWith("[") -> JSONArray(clean)
                else -> null
            }
        }.getOrNull()
    }

    fun collectJsonObjects(value: Any?, output: MutableList<JSONObject>) {
        when (value) {
            is JSONObject -> {
                output.add(value)
                val keys = value.keys()
                while (keys.hasNext()) collectJsonObjects(value.opt(keys.next()), output)
            }
            is JSONArray -> for (i in 0 until value.length()) collectJsonObjects(value.opt(i), output)
        }
    }

    fun JSONObject.stringValue(vararg keys: String): String? {
        keys.forEach { key ->
            if (!has(key) || isNull(key)) return@forEach
            val value = opt(key).toString().cleanEscaped().trim()
            if (value.isNotBlank() && !value.equals("null", true)) return value
        }
        return null
    }

    fun JSONObject.intValue(vararg keys: String): Int? {
        keys.forEach { key ->
            if (!has(key) || isNull(key)) return@forEach
            when (val value = opt(key)) {
                is Number -> return value.toInt()
                is String -> value.filter { it.isDigit() }.toIntOrNull()?.let { return it }
                else -> value.toString().filter { it.isDigit() }.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun parseSeriesFromJson(text: String, baseUrl: String, mainUrl: String): List<ReynimeSeries> {
        val root = parseJsonRoot(text) ?: return emptyList()
        val objects = mutableListOf<JSONObject>()
        collectJsonObjects(root, objects)

        return objects.mapNotNull { obj ->
            if (!obj.looksLikeSeriesObject()) return@mapNotNull null
            val id = obj.intValue("series_id", "seriesId", "anime_id", "animeId", "id") ?: return@mapNotNull null
            val title = obj.stringValue("series_title", "anime_title", "title", "name", "judul")?.cleanTitle()?.takeIf { !isBadTitle(it) } ?: return@mapNotNull null
            val slug = obj.stringValue("slug", "permalink") ?: title.slugify()
            val genres = obj.stringValue("genres", "genre", "tags")?.split(',', '|', '/', ';')?.map { it.trim().lowercase().replace(" ", "-") }?.filter { it.isNotBlank() }?.toSet().orEmpty()
            val type = if (obj.stringValue("type", "kind", "category")?.contains("movie", true) == true) TvType.AnimeMovie else TvType.Anime
            ReynimeSeries(
                id = id,
                title = title,
                slug = slug,
                poster = normalizePoster(obj.stringValue("poster", "cover", "banner", "thumbnail", "image", "thumb"), baseUrl, mainUrl),
                kind = obj.stringValue("kind", "category", "type") ?: if (genres.contains("anime")) "Anime" else "Donghua",
                status = obj.stringValue("status") ?: "Ongoing",
                type = type,
                latestEpisode = obj.intValue("latest_episode", "latestEpisode", "total_episode", "totalEpisode", "episode_count", "episodeCount") ?: 1,
                firstEpisode = obj.intValue("first_episode", "firstEpisode", "start_episode", "startEpisode") ?: 1,
                year = obj.intValue("year", "release_year", "releaseYear"),
                score = obj.stringValue("score", "rating")?.toDoubleOrNull(),
                genres = genres,
                description = obj.stringValue("description", "synopsis", "overview") ?: "Streaming Donghua subtitle Indonesia di Reynime."
            )
        }
            .distinctBy { it.id }
    }

    private fun parseSeriesFromDocument(document: Document, baseUrl: String, mainUrl: String): List<ReynimeSeries> {
        return document.select("a[href*=/series/]")
            .mapNotNull { anchor ->
                val href = anchor.attr("abs:href").ifBlank { normalizeUrl(anchor.attr("href"), baseUrl, mainUrl) }
                val id = extractSeriesId(href) ?: return@mapNotNull null
                val card = anchor.parents().firstOrNull { parent ->
                    parent.select("img").isNotEmpty() || parent.className().contains("card", true) || parent.className().contains("item", true)
                } ?: anchor
                val title = listOf(
                    anchor.attr("title"),
                    anchor.selectFirst("h1, h2, h3, h4, [class*=title], [class*=name]")?.text(),
                    card.selectFirst("h1, h2, h3, h4, [class*=title], [class*=name]")?.text(),
                    anchor.text(),
                    card.text()
                ).firstOrNull { !it.isNullOrBlank() }?.cleanTitle()?.takeIf { !isBadTitle(it) } ?: return@mapNotNull null
                val poster = (card.select("img").firstNotNullOfOrNull { it.getImageAttr() } ?: anchor.select("img").firstNotNullOfOrNull { it.getImageAttr() })
                val latest = extractEpisodeNumber(card.text()) ?: 1
                val genres = extractGenreWords(card.text())
                ReynimeSeries(
                    id = id,
                    title = title,
                    slug = extractSeriesSlug(href) ?: title.slugify(),
                    poster = normalizePoster(poster, baseUrl, mainUrl),
                    kind = if (genres.contains("anime")) "Anime" else "Donghua",
                    latestEpisode = latest,
                    genres = genres
                )
            }
            .distinctBy { it.id }
    }

    private fun JSONObject.looksLikeSeriesObject(): Boolean {
        if (looksLikeEpisodeObject()) return false
        val hasIdentity = intValue("series_id", "seriesId", "anime_id", "animeId", "id") != null
        val hasTitle = stringValue("series_title", "anime_title", "title", "name", "judul") != null
        val hasCatalogSignal = has("slug") || has("poster") || has("cover") || has("banner") || has("genre") || has("genres") || has("latest_episode") || has("status")
        return hasIdentity && hasTitle && hasCatalogSignal
    }

    private fun JSONObject.looksLikeEpisodeObject(): Boolean {
        val hasPlayable = backendVideoKeys.any { stringValue(it) != null }
        val hasEpisodeIdentity = intValue("episode_id", "episodeId", "watch_id", "pid", "episode_number", "episodeNumber", "episode", "ep", "eps", "number") != null
        val label = stringValue("episode_title", "label", "judul", "title", "name")
        val hasEpisodeLabel = extractEpisodeNumber(label) != null
        val hasStrongSeriesSignal = (has("series_title") || has("anime_title") || has("latest_episode") || has("total_episode") || has("slug")) && !hasPlayable && !hasEpisodeIdentity && !hasEpisodeLabel
        return (hasPlayable || hasEpisodeIdentity || hasEpisodeLabel) && !hasStrongSeriesSignal
    }

    private fun JSONObject.toBackendEpisodeRecord(): ReynimeBackendEpisode? {
        if (!looksLikeEpisodeObject()) return null
        val urls = backendVideoKeys.mapNotNull { stringValue(it) }
            .map { it.cleanEscaped().trim() }
            .filter { it.contains("http", true) || it.startsWith("//") }
            .distinct()
        return ReynimeBackendEpisode(
            id = stringValue("id", "episode_id", "episodeId", "watch_id", "pid"),
            seriesId = stringValue("series_id", "seriesId", "anime_id", "animeId"),
            episodeNumber = stringValue("episode_number", "episodeNumber", "episode", "ep", "number"),
            title = stringValue("episode_title", "title", "name", "label"),
            poster = stringValue("poster", "thumbnail", "image", "cover", "thumb"),
            description = stringValue("description", "synopsis", "overview"),
            urls = urls
        ).takeIf { it.id != null || it.episodeNumber != null || it.urls.isNotEmpty() }
    }

    private fun extractJsonValue(text: String, key: String): String? {
        val pattern = """["']${Regex.escape(key)}["']\s*:\s*(?:["']((?:\\.|[^"'\\])*)["']|([^,}\]]+))"""
        val match = Regex(pattern, RegexOption.IGNORE_CASE).find(text) ?: return null
        return (match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: match.groupValues.getOrNull(2))
            ?.trim()
            ?.trim('"', '\'')
            ?.cleanEscaped()
            ?.takeIf { it.isNotBlank() && !it.equals("null", true) }
    }

    private fun extractGenreWords(text: String): Set<String> {
        val lower = text.lowercase()
        val genres = listOf("action", "adventure", "fantasy", "martial arts", "martial-arts", "xuanhuan", "xianxia", "wuxia", "sci-fi", "romance", "comedy", "mystery", "supernatural", "isekai", "donghua", "anime")
        return genres.filter { lower.contains(it.replace('-', ' ')) || lower.contains(it) }
            .map { it.replace(" ", "-") }
            .toSet()
    }

    private fun Element.getImageAttr(): String? {
        fun fromSrcSet(value: String?): String? = value?.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.map { it.trim().substringBefore(" ") }
            ?.lastOrNull { it.isNotBlank() }

        return fromSrcSet(attr("data-srcset"))
            ?: fromSrcSet(attr("srcset"))
            ?: attr("abs:data-src").takeIf { it.isNotBlank() }
            ?: attr("abs:data-lazy-src").takeIf { it.isNotBlank() }
            ?: attr("abs:data-original").takeIf { it.isNotBlank() }
            ?: attr("abs:src").takeIf { it.isNotBlank() }
            ?: attr("data-src").takeIf { it.isNotBlank() }
            ?: attr("data-lazy-src").takeIf { it.isNotBlank() }
            ?: attr("src").takeIf { it.isNotBlank() }
    }

    private fun isBadTitle(title: String): Boolean {
        val value = title.lowercase().trim()
        return value.isBlank() || value == "home" || value == "beranda" || value == "login" || value == "register" ||
            value == "search" || value == "genre" || value == "watch" || value == "episode" || value == "episodes" ||
            value == "reynime" || value == "reynime - nonton donghua sub indo" || value.contains("join telegram") ||
            value.contains("aktifkan javascript") || value.contains("nonton donghua sub indo gratis")
    }
}
