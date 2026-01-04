package com.prestige

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class Prestige : MainAPI() {
    override var mainUrl = "https://amd.brstej.com"
    override var name = "Mak Prestige"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "index.php" to "الرئيسية",
        "category818.php?cat=prss7-2025" to "مسلسلات برستيج",
        "category.php?cat=movies2-2224" to "افلام",
        "category.php?cat=ramdan1-2024" to "مسلسلات رمضان 2024",
        "newvideo.php" to "أخر الاضافات",
    )

    private fun cleanPosterUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        var u = url.trim()
        
        // Handle wp.com proxy URLs
        val wpMatch = Regex("""https?://i\d+\.wp\.com/(.+)""").find(u)
        if (wpMatch != null) {
            u = wpMatch.groupValues[1]
            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                u = "https://$u"
            }
        }
        
        // Remove query parameters
        u = u.split("?").first()
        
        // Handle protocol-relative URLs
        if (u.startsWith("//")) {
            u = "https:$u"
        }
        
        // Make absolute if relative
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            val base = mainUrl.trimEnd('/')
            u = if (u.startsWith("/")) "$base$u" else "$base/$u"
        }
        
        return u
    }

    private fun buildAbsoluteUrl(href: String?, base: String = mainUrl): String {
        if (href.isNullOrBlank()) return ""
        val h = href.trim().removePrefix("./")
        
        if (h.startsWith("http://") || h.startsWith("https://")) return h
        
        val baseTrim = base.trimEnd('/')
        return if (h.startsWith("/")) "$baseTrim$h" else "$baseTrim/$h"
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        // Match Bristege selector: div.caption h3 a
        val titleLinkElement = this.selectFirst("div.caption h3 a") ?: return null
        val href = titleLinkElement.attr("href")
        if (href.isBlank() || href == "#modal-login-form") return null
        
        val title = titleLinkElement.attr("title").ifBlank { titleLinkElement.text() }.trim()
        if (title.isBlank()) return null
        
        // Match Bristege: div.pm-video-thumb img with data-echo, data-original, or src
        val img = this.selectFirst("div.pm-video-thumb img")
        val rawPoster = img?.attr("data-echo")?.ifBlank { null }
            ?: img?.attr("data-original")?.ifBlank { null }
            ?: img?.attr("src")
        val posterUrl = cleanPosterUrl(rawPoster)
        
        val absoluteHref = buildAbsoluteUrl(href)
        val type = if (href.contains("series1.php") || href.contains("view-serie.php")) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }
        
        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, absoluteHref, type) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, absoluteHref, type) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            "$mainUrl/${request.data}&page=$page"
        } else {
            "$mainUrl/${request.data}"
        }
        
        val document = try {
            app.get(url).document
        } catch (e: Exception) {
            Log.e(name, "Error fetching main page: $url", e)
            return newHomePageResponse(request.name, emptyList())
        }
        
        // Select container elements that have thumbnail and caption
        val items = document.select("ul.pm-ul-browse-videos li, .pm-video-content, .col-md-3, .col-xs-6").mapNotNull { element ->
            element.toSearchResponse()
        }
        
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search.php?keywords=${query.replace(" ", "+")}"
        val document = try {
            app.get(searchUrl).document
        } catch (e: Exception) {
            Log.e(name, "Error searching: $searchUrl", e)
            return emptyList()
        }
        
        return document.select("ul.pm-ul-browse-videos li, .pm-video-content, .col-md-3, .col-xs-6").mapNotNull { element ->
            element.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = try {
            app.get(url).document
        } catch (e: Exception) {
            Log.e(name, "Error loading: $url", e)
            return newMovieLoadResponse("Error", url, TvType.Movie, url)
        }
        
        val title = document.selectFirst("h1, .video-title")?.text()?.trim()
            ?: document.selectFirst("title")?.text()?.substringBefore(" – ")?.trim()
            ?: "Unknown"
        
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("div.pm-video-thumb img")?.let { img ->
                cleanPosterUrl(img.attr("data-echo").ifBlank { img.attr("src") })
            }
        
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
        
        val episodes = mutableListOf<Episode>()
        
        // For series pages, look for episode links
        if (url.contains("series1.php") || url.contains("view-serie.php")) {
            document.select("ul.pm-ul-browse-videos li").forEach { item ->
                val link = item.selectFirst("div.caption h3 a") ?: return@forEach
                val epHref = buildAbsoluteUrl(link.attr("href"))
                val epTitle = link.attr("title").ifBlank { link.text() }.trim()
                
                if (epHref.isNotBlank() && epTitle.isNotBlank()) {
                    val epPoster = item.selectFirst("div.pm-video-thumb img")?.let { img ->
                        cleanPosterUrl(img.attr("data-echo").ifBlank { img.attr("src") })
                    }
                    val epNum = Regex("""الحلقة\s*(\d+)|حلقة\s*(\d+)|(\d+)""").find(epTitle)?.let { m ->
                        m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.toIntOrNull()
                    }
                    episodes.add(newEpisode(epHref) {
                        name = epTitle
                        episode = epNum
                        posterUrl = epPoster
                    })
                }
            }
            
            // Sort episodes by episode number
            episodes.sortBy { it.episode }
        }
        
        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    // Helper function to convert int to base-N string (for unpacker)
    private fun intToBaseStr(num: Int, base: Int): String {
        val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
        return if (num < base) {
            chars.getOrNull(num)?.toString() ?: ""
        } else {
            intToBaseStr(num / base, base) + (chars.getOrNull(num % base) ?: "")
        }
    }

    // Custom JS unpacker for packed JavaScript
    private fun unpackJs(packedJs: String): String? {
        try {
            val m = Regex("""eval\(function\s*\(p,a,c,k,e,d\)\s*\{[\s\S]*?\}\s*\(\s*(['"])(.*?)\1\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(['"])(.*?)\5\.split\(['"]\|['"]\)\s*\)\s*\)""", RegexOption.DOT_MATCHES_ALL).find(packedJs)
                ?: return null
            
            val payloadRaw = m.groupValues[2]
            val base = m.groupValues[3].toIntOrNull() ?: return null
            val count = m.groupValues[4].toIntOrNull() ?: return null
            val dictionary = m.groupValues[6].split("|")
            
            // Unescape the payload
            var payload = payloadRaw
                .replace(Regex("""\\x([0-9a-fA-F]{2})""")) { mr ->
                    try { mr.groupValues[1].toInt(16).toChar().toString() } catch (e: Exception) { mr.value }
                }
                .replace(Regex("""\\u([0-9a-fA-F]{4})""")) { mr ->
                    try { mr.groupValues[1].toInt(16).toChar().toString() } catch (e: Exception) { mr.value }
                }
                .replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
            
            // Build lookup table
            val lookup = mutableMapOf<String, String>()
            for (i in (count - 1) downTo 0) {
                var key = intToBaseStr(i, base)
                if (key.isBlank()) key = i.toString()
                val value = dictionary.getOrNull(i)?.ifBlank { key } ?: key
                lookup[key] = value
            }
            
            // Replace words
            val unpacked = Regex("""\b\w+\b""").replace(payload) { mr ->
                lookup[mr.value] ?: mr.value
            }
            
            return if (unpacked.isBlank()) null else unpacked
        } catch (e: Exception) {
            Log.e(name, "Error unpacking JS", e)
            return null
        }
    }

    // Find video URL in text
    private fun findVideoInText(text: String): String? {
        val patterns = listOf(
            Regex("""(?:file|src)\s*:\s*['"](https?://[^'"]+)['"]"""),
            Regex("""['"](https?://[^\s'"]+\.(?:m3u8|mp4)[^\s'"]*)['"]"""),
            Regex("""(https?://[^\s'"]+\.(?:m3u8|mp4)[^\s']*)""")
        )
        
        for (p in patterns) {
            val match = p.find(text)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }

    private fun getQualityFromName(name: String?): Int {
        return when (name?.lowercase()) {
            "360p" -> Qualities.P360.value
            "480p" -> Qualities.P480.value
            "720p" -> Qualities.P720.value
            "1080p" -> Qualities.P1080.value
            else -> Qualities.Unknown.value
        }
    }

    private suspend fun extractFromEmbed(
        embedUrl: String,
        referer: String,
        serverName: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        try {
            Log.d(name, "Extracting from embed: $embedUrl")
            
            // First try standard extractors
            if (loadExtractor(embedUrl, referer, subtitleCallback, callback)) {
                return
            }
            
            // If standard extractors fail, try manual extraction
            val embedText = app.get(embedUrl, referer = referer).text
            
            // Look for packed JavaScript
            var videoUrl: String? = null
            
            // Try JsUnpacker first
            try {
                val packedMatch = Regex("""eval\(function\(p,a,c,k,e,d\)""").find(embedText)
                if (packedMatch != null) {
                    val jsUnpacker = JsUnpacker(embedText)
                    val unpacked = jsUnpacker.unpack()
                    if (!unpacked.isNullOrBlank()) {
                        videoUrl = findVideoInText(unpacked)
                    }
                }
            } catch (e: Exception) {
                Log.e(name, "JsUnpacker failed", e)
            }
            
            // Try custom unpacker
            if (videoUrl == null) {
                val unpacked = unpackJs(embedText)
                if (!unpacked.isNullOrBlank()) {
                    videoUrl = findVideoInText(unpacked)
                }
            }
            
            // Try finding video directly in source
            if (videoUrl == null) {
                videoUrl = findVideoInText(embedText)
            }
            
            if (!videoUrl.isNullOrBlank()) {
                val isM3u8 = videoUrl.contains(".m3u8")
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$serverName",
                        url = videoUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer
                        this.quality = quality
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(name, "Error extracting from embed: $embedUrl", e)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.i(name, "loadLinks for: $data")
        
        val document = try {
            app.get(data, referer = mainUrl).document
        } catch (e: Exception) {
            Log.e(name, "Error loading page: $data", e)
            return false
        }
        
        val embedLinks = mutableListOf<Triple<String, String, String>>()
        val processedUrls = mutableSetOf<String>()
        
        // Look for play links first (page might redirect to play.php)
        val playHref = document.selectFirst("a[href*='play.php']")?.attr("href")
        val playDoc = if (playHref != null) {
            try {
                app.get(buildAbsoluteUrl(playHref), referer = data).document
            } catch (e: Exception) {
                document
            }
        } else {
            document
        }
        
        // Extract embed URLs from buttons with data-embed-url
        playDoc.select("button[data-embed-url], .watchButton[data-embed-url], [data-embed-url]").forEach { btn ->
            val embedUrl = btn.attr("data-embed-url")
            if (embedUrl.isNotBlank()) {
                val serverName = btn.text().trim().ifBlank { "Server" }
                val qualityStr = btn.attr("data-quality").ifBlank { 
                    Regex("""(\d+)[pP]""").find(serverName)?.groupValues?.get(1)?.let { "${it}p" } ?: ""
                }
                embedLinks.add(Triple(embedUrl, serverName, qualityStr))
            }
        }
        
        // Also check iframes
        playDoc.select("iframe[src]").forEach { iframe ->
            var src = iframe.attr("src")
            if (src.startsWith("//")) src = "https:$src"
            if (src.isNotBlank() && !src.contains("google") && !src.contains("facebook")) {
                embedLinks.add(Triple(src, "Player", ""))
            }
        }
        
        // Check scripts for inline video links
        playDoc.select("script").forEach { script ->
            val scriptText = script.html()
            findVideoInText(scriptText)?.let { videoUrl ->
                if (!processedUrls.contains(videoUrl)) {
                    processedUrls.add(videoUrl)
                    val isM3u8 = videoUrl.contains(".m3u8")
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name - Direct",
                            url = videoUrl,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = data
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        }
        
        // Process embed links concurrently
        coroutineScope {
            for ((embedUrl, serverName, qualityStr) in embedLinks) {
                if (processedUrls.add(embedUrl)) {
                    val quality = getQualityFromName(qualityStr)
                    val actualReferer = if (playHref.isNullOrBlank()) data else buildAbsoluteUrl(playHref, data)
                    launch {
                        extractFromEmbed(embedUrl, actualReferer, serverName, quality, callback, subtitleCallback)
                    }
                }
            }
        }
        
        return processedUrls.isNotEmpty() || embedLinks.isNotEmpty()
    }
}
