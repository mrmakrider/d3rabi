package com.shahid

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Shahid : MainAPI() {
    override var mainUrl = "https://w.shahidmosalsalat.me"
    override var name = "شاهد مسلسلات"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "category.php?cat=3-arabic-series3" to "مسلسلات عربية",
        "category.php?cat=1-series-turkish-2025" to "مسلسلات تركية",
        "category.php?cat=2-koryan-series" to "مسلسلات كورية وآسيوية",
        "category.php?cat=1-series-english-2025" to "مسلسلات أجنبية",
        "category.php?cat=5-aflam-3araby1" to "أفلام عربي",
        "category.php?cat=2-english-movies3" to "أفلام أجنبي",
        "category.php?cat=2-indian-movies3" to "أفلام هندي",
        "category.php?cat=1-animation-series-2025" to "انمي",
        "newvideos1.php" to "أحدث الإضافات",
    )

    private fun Element.toSearchResult(): SearchResponse? {
        // Try to find the link - site uses a.ellipsis or direct a[href*='watch.php']
        val linkElement = selectFirst("a.ellipsis, a[href*='watch.php'], a[href*='view-serie']") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        if (href.isBlank()) return null

        // Get title from link title attribute or text content
        val title = linkElement.attr("title").ifBlank { 
            linkElement.text().trim() 
        }.ifBlank { return null }

        // Try multiple ways to get the poster image
        val posterUrl = run {
            // Try background-image from span (common pattern on this site)
            val bgElement = selectFirst("span[style*='background'], div[style*='background-image']")
            if (bgElement != null) {
                val bgStyle = bgElement.attr("style")
                val bgUrl = Regex("""url\s*\(\s*['"]?([^'")\s]+)['"]?\s*\)""").find(bgStyle)?.groupValues?.get(1)
                if (!bgUrl.isNullOrBlank()) return@run fixUrlNull(bgUrl)
            }
            
            // Try picture > img (for SerieInner items)
            selectFirst("picture img, img")?.let { img ->
                img.attr("data-src").ifBlank {
                    img.attr("src").ifBlank {
                        img.attr("data-lazy-src")
                    }
                }
            }?.let { fixUrlNull(it) }
        }

        // Determine if series or movie based on URL
        val isSeries = href.contains("view-serie") || href.contains("moslslat") || 
                       !href.contains("aflam") && !href.contains("movie")

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
        val url = if (request.data.contains("?")) {
            "$mainUrl/${request.data}&page=$page"
        } else {
            "$mainUrl/${request.data}?page=$page"
        }

        val document = try {
            app.get(url).document
        } catch (e: Exception) {
            Log.e(name, "Error fetching main page: $url", e)
            return newHomePageResponse(request.name, emptyList())
        }

        // Find content items - look for grid items with links
        val items = document.select(".video-item, .post-item, article, div:has(> a.ellipsis), div:has(> a[href*='watch.php'])").mapNotNull { element ->
            element.toSearchResult()
        }.distinctBy { it.url }

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

        return document.select(".video-item, .post-item, article, div:has(> a.ellipsis), div:has(> a[href*='watch.php'])").mapNotNull { element ->
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
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - ")?.trim()
            ?: document.selectFirst("title")?.text()?.substringBefore(" - ")?.trim()
            ?: "Unknown"

        // Get poster from og:image meta tag
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")

        val description = document.selectFirst("meta[property=og:description]")?.attr("content")

        // Find episodes
        val episodes = mutableListOf<Episode>()
        
        // Check if this is a series page (view-serie.php) or video page (watch.php)
        if (url.contains("view-serie.php")) {
            // Series page - extract all episode links
            document.select("a[href*='watch.php']").forEach { epLink ->
                val epHref = fixUrl(epLink.attr("href"))
                val epTitle = epLink.text().trim()
                
                // Extract episode number from title
                val epNum = Regex("""الحلقة\s*(\d+)|حلقة\s*(\d+)|(\d+)\s*حلقة""").find(epTitle)?.let { match ->
                    match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.toIntOrNull()
                }
                
                episodes.add(newEpisode(epHref) {
                    name = epTitle
                    episode = epNum
                })
            }
        } else if (url.contains("watch.php")) {
            // Video page - look for episode list links (like 46حلقة, 47حلقة)
            // Also check for view-serie.php link to get all episodes
            val seriesLink = document.selectFirst("a[href*='view-serie.php']")?.attr("href")
            
            if (seriesLink != null) {
                // Fetch the series page for complete episode list
                try {
                    val seriesDoc = app.get(fixUrl(seriesLink)).document
                    seriesDoc.select("a[href*='watch.php']").forEach { epLink ->
                        val epHref = fixUrl(epLink.attr("href"))
                        val epTitle = epLink.text().trim()
                        
                        val epNum = Regex("""الحلقة\s*(\d+)|حلقة\s*(\d+)|(\d+)\s*حلقة""").find(epTitle)?.let { match ->
                            match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.toIntOrNull()
                        }
                        
                        episodes.add(newEpisode(epHref) {
                            name = epTitle
                            episode = epNum
                        })
                    }
                } catch (e: Exception) {
                    Log.e(name, "Error fetching series page: $seriesLink", e)
                }
            }
            
            // Also look for inline episode links on the current page (fall back)
            if (episodes.isEmpty()) {
                document.select("a[href*='watch.php']").forEach { epLink ->
                    val epHref = fixUrl(epLink.attr("href"))
                    if (epHref == url) return@forEach // Skip current page
                    
                    val epTitle = epLink.text().trim()
                    // Match patterns like "46حلقة" or "الحلقة 46"
                    val epNum = Regex("""(\d+)\s*حلقة|الحلقة\s*(\d+)""").find(epTitle)?.let { match ->
                        match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.toIntOrNull()
                    }
                    
                    if (epNum != null || epTitle.contains("حلقة") || epTitle.contains("الحلقة")) {
                        episodes.add(newEpisode(epHref) {
                            name = epTitle.ifBlank { "الحلقة $epNum" }
                            episode = epNum
                        })
                    }
                }
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
            }
        } else {
            // Check if this is a movie or single episode - use the current URL as data
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
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

        // 1. Check for iframes on the page
        document.select("iframe[src]").forEach { iframe ->
            var src = iframe.attr("src")
            if (src.startsWith("//")) src = "https:$src"
            if (src.isNotBlank() && !src.contains("google") && !src.contains("facebook") && processedUrls.add(src)) {
                Log.d(name, "Found iframe: $src")
                if (loadExtractor(src, data, subtitleCallback, callback)) {
                    hasLinks = true
                } else {
                    // Try to extract directly from iframe content
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

        // 2. Check for player.php URL pattern
        val vid = Regex("""vid=([a-zA-Z0-9]+)""").find(data)?.groupValues?.get(1)
        if (vid != null) {
            val playerUrl = "$mainUrl/player.php?vid=$vid"
            if (processedUrls.add(playerUrl)) {
                try {
                    val playerDoc = app.get(playerUrl, referer = data).text
                    findVideoUrl(playerDoc)?.let { videoUrl ->
                        if (processedUrls.add(videoUrl)) {
                            val isM3u8 = videoUrl.contains(".m3u8")
                            callback(
                                newExtractorLink(
                                    source = name,
                                    name = "$name - Player",
                                    url = videoUrl,
                                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = playerUrl
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            hasLinks = true
                        }
                    }
                    
                    // Also check for iframes in player page
                    val playerDocument = app.get(playerUrl, referer = data).document
                    playerDocument.select("iframe[src]").forEach { iframe ->
                        var src = iframe.attr("src")
                        if (src.startsWith("//")) src = "https:$src"
                        if (src.isNotBlank() && processedUrls.add(src)) {
                            if (loadExtractor(src, data, subtitleCallback, callback)) {
                                hasLinks = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(name, "Error fetching player page: $playerUrl", e)
                }
            }
        }

        // 3. Check scripts for video URLs
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
