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

        // Find episodes - look for links with pattern Xحلقة (like 1حلقة, 2حلقة)
        val episodes = mutableListOf<Episode>()
        
        // Pattern: Xحلقة where X is a number (e.g., "1حلقة", "46حلقة")
        document.select("a[href*='watch.php']").forEach { epLink ->
            val epHref = fixUrl(epLink.attr("href"))
            val epText = epLink.text().trim()
            
            // Match "Xحلقة" pattern (number followed by حلقة)
            val epNumMatch = Regex("""(\d+)\s*حلقة""").find(epText)
            if (epNumMatch != null) {
                val epNum = epNumMatch.groupValues[1].toIntOrNull()
                if (epNum != null) {
                    episodes.add(newEpisode(epHref) {
                        name = "الحلقة $epNum"
                        episode = epNum
                    })
                }
            }
        }
        
        // If no episodes found with the short pattern, try other patterns
        if (episodes.isEmpty()) {
            document.select("a[href*='watch.php']").forEach { epLink ->
                val epHref = fixUrl(epLink.attr("href"))
                val epText = epLink.text().trim()
                
                // Match "الحلقة X" pattern 
                val epNumMatch = Regex("""الحلقة\s*(\d+)""").find(epText)
                if (epNumMatch != null) {
                    val epNum = epNumMatch.groupValues[1].toIntOrNull()
                    if (epNum != null && epHref != url) {
                        episodes.add(newEpisode(epHref) {
                            name = "الحلقة $epNum"
                            episode = epNum
                        })
                    }
                }
            }
        }

        // Sort episodes by number and remove duplicates
        val uniqueEpisodes = episodes.distinctBy { it.data }.sortedBy { it.episode }

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

    private fun extractServersFromScript(script: String): List<String> {
        // Extract servers array from script: let servers = [...]
        val serversMatch = Regex("""let\s+servers\s*=\s*\[(.*?)\];""", RegexOption.DOT_MATCHES_ALL).find(script)
        if (serversMatch != null) {
            val serversContent = serversMatch.groupValues[1]
            // Extract URLs from the JSON-like array
            val urlPattern = Regex(""""url"\s*:\s*"([^"]+)"""")
            return urlPattern.findAll(serversContent).map { it.groupValues[1].replace("\\/", "/") }.toList()
        }
        return emptyList()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.i(name, "loadLinks for: $data")
        
        val processedUrls = mutableSetOf<String>()
        var hasLinks = false

        // Extract video ID from URL
        val vid = Regex("""vid=([a-zA-Z0-9]+)""").find(data)?.groupValues?.get(1)
        
        if (vid != null) {
            // Fetch embed.php page which contains the servers array
            val embedUrl = "$mainUrl/embed.php?vid=$vid"
            try {
                val embedResponse = app.get(embedUrl, referer = data).text
                Log.d(name, "Fetching embed page: $embedUrl")
                
                // Extract servers from the script
                val serverUrls = extractServersFromScript(embedResponse)
                Log.d(name, "Found ${serverUrls.size} servers")
                
                serverUrls.forEach { serverUrl ->
                    if (serverUrl.isNotBlank() && processedUrls.add(serverUrl)) {
                        Log.d(name, "Processing server: $serverUrl")
                        
                        // Try to use built-in extractors first
                        if (loadExtractor(serverUrl, embedUrl, subtitleCallback, callback)) {
                            hasLinks = true
                        }
                    }
                }
                
                // Also check for iframes directly in embed page
                val embedDoc = app.get(embedUrl, referer = data).document
                embedDoc.select("iframe[src]").forEach { iframe ->
                    var src = iframe.attr("src")
                    if (src.startsWith("//")) src = "https:$src"
                    if (src.isNotBlank() && !src.contains("google.com/recaptcha") && processedUrls.add(src)) {
                        Log.d(name, "Found iframe in embed: $src")
                        if (loadExtractor(src, embedUrl, subtitleCallback, callback)) {
                            hasLinks = true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(name, "Error fetching embed page: $embedUrl", e)
            }
        }

        // Fallback: Check for iframes on the main watch page
        if (!hasLinks) {
            try {
                val document = app.get(data, referer = mainUrl).document
                document.select("iframe[src]").forEach { iframe ->
                    var src = iframe.attr("src")
                    if (src.startsWith("//")) src = "https:$src"
                    if (src.isNotBlank() && !src.contains("google") && !src.contains("facebook") && processedUrls.add(src)) {
                        Log.d(name, "Found iframe on watch page: $src")
                        if (loadExtractor(src, data, subtitleCallback, callback)) {
                            hasLinks = true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(name, "Error checking main page iframes", e)
            }
        }

        return hasLinks
    }
}
