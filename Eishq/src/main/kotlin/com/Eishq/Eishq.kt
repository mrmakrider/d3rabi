package com.eishq

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Eishq : MainAPI() {
    override var mainUrl = "https://new.eishq.net"
    override var name = "قصة عشق"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "video/series/" to "مسلسلات",
        "%d8%a3%d8%ad%d8%af%d8%ab-%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a7%d8%aa-2/" to "أحدث الحلقات",
        "video/movies/" to "أفلام",
    )

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = selectFirst("a[href*='/video/']") ?: return null
        val href = linkElement.attr("href")
        if (href.isBlank()) return null

        val title = selectFirst("h3, .title, img[alt]")?.let {
            it.text().ifBlank { it.attr("alt") }
        }?.trim() ?: linkElement.attr("title").ifBlank { return null }

        // Try multiple ways to get the poster image
        val posterUrl = run {
            // First try background-image from .imgSer div (main method for this site)
            val bgStyle = selectFirst(".imgSer, .poster div[style*='background']")?.attr("style")
            if (bgStyle != null) {
                val bgUrl = Regex("""url\s*\(\s*['"]?([^'")\s]+)['"]?\s*\)""").find(bgStyle)?.groupValues?.get(1)
                if (!bgUrl.isNullOrBlank()) return@run fixUrlNull(bgUrl)
            }
            
            // Fallback to img tag with various lazy-load attributes
            selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank {
                    img.attr("data-lazy-src").ifBlank {
                        img.attr("data-original").ifBlank {
                            img.attr("src")
                        }
                    }
                }
            }?.let { fixUrlNull(it) }
        }

        val isSeries = href.contains("/series/") || !href.contains("/movies/")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            "$mainUrl/${request.data}page/$page/"
        } else {
            "$mainUrl/${request.data}"
        }

        val document = try {
            app.get(url).document
        } catch (e: Exception) {
            Log.e(name, "Error fetching main page: $url", e)
            return newHomePageResponse(request.name, emptyList())
        }

        // Find content items - the site uses .block-post containers
        val items = document.select(".block-post, article, .post-item, .video-item").mapNotNull { element ->
            element.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = try {
            app.get(searchUrl).document
        } catch (e: Exception) {
            Log.e(name, "Error searching: $searchUrl", e)
            return emptyList()
        }

        return document.select(".block-post, article, .post-item, .video-item").mapNotNull { element ->
            element.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = try {
            app.get(url).document
        } catch (e: Exception) {
            Log.e(name, "Error loading: $url", e)
            return newMovieLoadResponse("Error", url, TvType.Movie, url)
        }

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("title")?.text()?.substringBefore(" - ")?.trim()
            ?: "Unknown"

        // Try multiple ways to get poster
        val poster = run {
            // First try og:image meta tag
            document.selectFirst("meta[property=og:image]")?.attr("content")?.let { 
                if (it.isNotBlank()) return@run it 
            }
            
            // Try background-image from .imgSer div
            val bgStyle = document.selectFirst(".imgSer, .poster div[style*='background']")?.attr("style")
            if (bgStyle != null) {
                val bgUrl = Regex("""url\s*\(\s*['"]?([^'")\s]+)['"]?\s*\)""").find(bgStyle)?.groupValues?.get(1)
                if (!bgUrl.isNullOrBlank()) return@run fixUrlNull(bgUrl)
            }
            
            // Fallback to img tags
            document.selectFirst(".post-thumbnail img, .video-poster img, article img")?.let { img ->
                fixUrlNull(img.attr("data-src").ifBlank { img.attr("src") })
            }
        }

        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst(".description, .plot, .story")?.text()

        val year = document.selectFirst("a[href*='/years/']")?.text()?.toIntOrNull()

        val genre = document.select("a[href*='/genre/']").mapNotNull { it.text().takeIf { t -> t.isNotBlank() } }

        // Find episodes from the المواسم والحلقات section
        val episodes = mutableListOf<Episode>()
        
        // Look for episode links - episodes are in anchor tags with href containing '/video/'
        document.select("a[href*='/video/']:has(.episode-number), a[href*='/video/layl'], a[href*='/video/'][href*='-ep-']").forEach { epLink ->
            val epHref = epLink.attr("href")
            if (epHref.isBlank() || epHref == url) return@forEach
            
            val epTitle = epLink.text().trim().ifBlank {
                epLink.selectFirst("span, div")?.text()?.trim() ?: "Episode"
            }
            
            // Extract episode number from URL or text
            val epNum = Regex("""ep-(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""الحلقة\s*(\d+)|حلقة\s*(\d+)|(\d+)""").find(epTitle)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.toIntOrNull()

            episodes.add(newEpisode(epHref) {
                name = epTitle
                episode = epNum
            })
        }

        // If no episodes found with specific selectors, try more general approach
        if (episodes.isEmpty()) {
            document.select("a[href*='-ep-']").forEach { epLink ->
                val epHref = fixUrl(epLink.attr("href"))
                if (epHref.isBlank() || epHref == url) return@forEach
                
                val epTitle = epLink.text().trim().ifBlank { "Episode" }
                val epNum = Regex("""ep-(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                
                episodes.add(newEpisode(epHref) {
                    name = epTitle
                    episode = epNum
                })
            }
        }

        // Sort episodes by number
        episodes.sortBy { it.episode }
        
        // Remove duplicates by URL
        val uniqueEpisodes = episodes.distinctBy { it.data }

        return if (uniqueEpisodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, uniqueEpisodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = genre
            }
        } else {
            // Check if this is a movie or single episode
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = genre
            }
        }
    }

    private fun findVideoUrl(text: String): String? {
        val patterns = listOf(
            Regex("""(?:file|src|source)\s*[:=]\s*['"]([^'"]+\.(?:m3u8|mp4)[^'"]*)['"]"""),
            Regex("""['"]([^'"]+\.(?:m3u8|mp4)[^'"]*)['"]"""),
            Regex("""(https?://[^\s'"]+\.(?:m3u8|mp4)[^\s'"]*)""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                val url = match.groupValues[1]
                if (url.isNotBlank() && (url.contains(".m3u8") || url.contains(".mp4"))) {
                    return url
                }
            }
        }
        return null
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

        val processedUrls = mutableSetOf<String>()
        var hasLinks = false

        // 1. Extract Base64 encoded video URL from hidden form input "watch"
        document.select("input[name=watch]").forEach { input ->
            val base64Value = input.attr("value")
            if (base64Value.isNotBlank()) {
                try {
                    val decoded = String(android.util.Base64.decode(base64Value, android.util.Base64.DEFAULT))
                    Log.d(name, "Decoded watch input: $decoded")
                    
                    // Parse JSON to extract "v" field containing video URL
                    val videoUrlMatch = Regex(""""v"\s*:\s*"([^"]+)"""").find(decoded)
                    videoUrlMatch?.groupValues?.get(1)?.let { videoUrl ->
                        val cleanUrl = videoUrl.replace("\\/", "/")
                        if (cleanUrl.isNotBlank() && processedUrls.add(cleanUrl)) {
                            Log.d(name, "Found video URL from watch input: $cleanUrl")
                            if (loadExtractor(cleanUrl, data, subtitleCallback, callback)) {
                                hasLinks = true
                            } else {
                                // Try to manually extract from the embed page
                                try {
                                    val embedResponse = app.get(cleanUrl, referer = data).text
                                    findVideoUrl(embedResponse)?.let { directUrl ->
                                        if (processedUrls.add(directUrl)) {
                                            val isM3u8 = directUrl.contains(".m3u8")
                                            callback(
                                                newExtractorLink(
                                                    source = name,
                                                    name = "$name - Primary",
                                                    url = directUrl,
                                                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                                ) {
                                                    this.referer = cleanUrl
                                                    this.quality = Qualities.Unknown.value
                                                }
                                            )
                                            hasLinks = true
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(name, "Error extracting from embed: $cleanUrl", e)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(name, "Error decoding Base64 watch input", e)
                }
            }
        }

        // 2. Check downloads page for additional server links
        try {
            val downloadsUrl = if (data.contains("?")) {
                data.substringBefore("?") + "?do=downloads"
            } else {
                data.trimEnd('/') + "/?do=downloads"
            }
            
            val downloadsDoc = app.get(downloadsUrl, referer = data).document
            
            // Look for download links in table or list
            downloadsDoc.select("a[href*='vidsp'], a[href*='dood'], a[href*='embed'], a[href*='stream'], table a[href]").forEach { link ->
                val href = link.attr("href")
                if (href.isNotBlank() && !href.contains("eishq.net") && processedUrls.add(href)) {
                    Log.d(name, "Found download link: $href")
                    if (loadExtractor(href, data, subtitleCallback, callback)) {
                        hasLinks = true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Error fetching downloads page", e)
        }

        // 3. Check for iframes on the main page
        document.select("iframe[src]").forEach { iframe ->
            var src = iframe.attr("src")
            if (src.startsWith("//")) src = "https:$src"
            if (src.isNotBlank() && !src.contains("google") && !src.contains("facebook") && processedUrls.add(src)) {
                Log.d(name, "Found iframe: $src")
                if (loadExtractor(src, data, subtitleCallback, callback)) {
                    hasLinks = true
                } else {
                    try {
                        val iframeDoc = app.get(src, referer = data).text
                        findVideoUrl(iframeDoc)?.let { videoUrl ->
                            if (processedUrls.add(videoUrl)) {
                                val isM3u8 = videoUrl.contains(".m3u8")
                                callback(
                                    newExtractorLink(
                                        source = name,
                                        name = "$name - iframe",
                                        url = videoUrl,
                                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = src
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                                hasLinks = true
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(name, "Error extracting from iframe: $src", e)
                    }
                }
            }
        }

        // 4. Check scripts for video URLs
        document.select("script").forEach { script ->
            val scriptText = script.html()
            findVideoUrl(scriptText)?.let { videoUrl ->
                if (processedUrls.add(videoUrl)) {
                    val isM3u8 = videoUrl.contains(".m3u8")
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name - Script",
                            url = videoUrl,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = data
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    hasLinks = true
                }
            }
        }

        return hasLinks
    }
}
