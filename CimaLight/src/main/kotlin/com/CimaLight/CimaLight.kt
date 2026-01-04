package com.cimalight

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink

class CimaLight : MainAPI() {
    override var mainUrl = "https://cimalite.cam"
    override var name = "CimaLight"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/category/movies/" to "أفلام",
        "$mainUrl/category/series/" to "مسلسلات",
        "$mainUrl/views/" to "الأكثر مشاهدة",
        "$mainUrl/recent/" to "أحدث الإضافات",
    )

    private fun cleanPosterUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        var u = url.trim()
        
        if (u.startsWith("//")) {
            u = "https:$u"
        }
        
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            val base = mainUrl.trimEnd('/')
            u = if (u.startsWith("/")) "$base$u" else "$base/$u"
        }
        
        return u
    }

    private fun buildAbsoluteUrl(href: String?, base: String = mainUrl): String {
        if (href.isNullOrBlank()) return ""
        val h = href.trim()
        
        if (h.startsWith("http://") || h.startsWith("https://")) return h
        
        val baseTrim = base.trimEnd('/')
        return if (h.startsWith("/")) "$baseTrim$h" else "$baseTrim/$h"
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        // Try multiple selectors for the link
        val link = this.selectFirst("a[href*='/movie/'], a[href*='/episode/'], a[href*='/season/'], a[href*='/series/']")
            ?: this.selectFirst("a[title]")
            ?: this.selectFirst("a")
            ?: return null
        
        val href = link.attr("href")
        if (href.isBlank() || href == "#") return null
        
        val title = link.attr("title").ifBlank { 
            this.selectFirst("h3, h2, .title, .name")?.text() ?: link.text()
        }.trim()
        if (title.isBlank()) return null
        
        // Get thumbnail from img inside this container
        val img = this.selectFirst("img")
        val posterUrl = cleanPosterUrl(
            img?.attr("data-lazy-src")?.ifBlank { null }
                ?: img?.attr("data-src")?.ifBlank { null }
                ?: img?.attr("src")
        )
        
        val absoluteHref = buildAbsoluteUrl(href)
        
        // Determine type based on URL
        val type = when {
            href.contains("/movie/") -> TvType.Movie
            href.contains("/series/") || href.contains("/season/") || href.contains("/episode/") -> TvType.TvSeries
            else -> TvType.Movie
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
            "${request.data}page/$page/"
        } else {
            request.data
        }
        
        val document = try {
            app.get(url).document
        } catch (e: Exception) {
            Log.e(name, "Error fetching main page: $url", e)
            return newHomePageResponse(request.name, emptyList())
        }
        
        // Try multiple container selectors
        val items = document.select(".owl-item, .box-item, article, .post, .item, li.post-item").mapNotNull { element ->
            element.toSearchResponse()
        }.distinctBy { it.url }
        
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = try {
            app.get(searchUrl).document
        } catch (e: Exception) {
            Log.e(name, "Error searching: $searchUrl", e)
            return emptyList()
        }
        
        return document.select(".owl-item, .box-item, article, .post, .item, li.post-item").mapNotNull { element ->
            element.toSearchResponse()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = try {
            app.get(url).document
        } catch (e: Exception) {
            Log.e(name, "Error loading: $url", e)
            return newMovieLoadResponse("Error", url, TvType.Movie, url)
        }
        
        val title = document.selectFirst("h1, .entry-title, .post-title, title")?.text()?.trim()
            ?.replace(" مترجم", "")?.replace(" مترجمة", "")
            ?: "Unknown"
        
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst(".poster img, .thumbnail img, img.attachment-large")?.let { img ->
                cleanPosterUrl(img.attr("data-lazy-src").ifBlank { img.attr("src") })
            }
        
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst(".story, .description, .plot, .synopsis")?.text()
        
        val episodes = mutableListOf<Episode>()
        
        // Check if this is a series/season page - look for episode links
        val isSeriesPage = url.contains("/series/") || url.contains("/season/")
        
        if (isSeriesPage) {
            // Look for episode links on the page
            document.select("a[href*='/episode/']").forEach { epLink ->
                val epHref = buildAbsoluteUrl(epLink.attr("href"))
                val epTitle = epLink.attr("title").ifBlank { epLink.text() }.trim()
                
                if (epHref.isNotBlank() && epTitle.isNotBlank() && !episodes.any { it.data == epHref }) {
                    val epNum = Regex("""الحلقة\s*(\d+)|حلقة\s*(\d+)|[Ee]pisode\s*(\d+)|[Ee]p\s*(\d+)|(\d+)""").find(epTitle)?.let { m ->
                        m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.toIntOrNull()
                    }
                    episodes.add(newEpisode(epHref) {
                        name = epTitle
                        episode = epNum
                    })
                }
            }
            
            // Sort by episode number
            episodes.sortBy { it.episode }
        }
        
        // Check if it's an episode page - it should play directly
        val isEpisodePage = url.contains("/episode/") || url.contains("/movie/")
        
        return when {
            episodes.isNotEmpty() -> {
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = description
                }
            }
            isEpisodePage -> {
                // Single episode or movie - treat as movie for playback
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.plot = description
                }
            }
            else -> {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.plot = description
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.i(name, "loadLinks for: $data")
        
        // Navigate to watch page
        val watchUrl = if (data.endsWith("/")) "${data}watch/" else "$data/watch/"
        
        val watchDoc = try {
            app.get(watchUrl, referer = data).document
        } catch (e: Exception) {
            Log.e(name, "Error loading watch page: $watchUrl", e)
            // Try direct page if watch page fails
            try {
                app.get(data).document
            } catch (e2: Exception) {
                return false
            }
        }
        
        var foundLinks = false
        val processedUrls = mutableSetOf<String>()
        
        // Extract embed URLs from li[data-index] elements
        watchDoc.select("li[data-index], ul.servers li[data-index]").forEach { serverLi ->
            val embedUrl = serverLi.attr("data-index")
            if (embedUrl.isNotBlank() && processedUrls.add(embedUrl)) {
                val serverName = serverLi.text().trim().ifBlank { "Server" }
                Log.d(name, "Found server: $serverName -> $embedUrl")
                
                try {
                    if (loadExtractor(embedUrl, watchUrl, subtitleCallback, callback)) {
                        foundLinks = true
                    }
                } catch (e: Exception) {
                    Log.e(name, "Error extracting from $embedUrl", e)
                }
            }
        }
        
        // Also check for iframes directly on the page
        watchDoc.select("iframe[src]").forEach { iframe ->
            var src = iframe.attr("src")
            if (src.startsWith("//")) src = "https:$src"
            if (src.isNotBlank() && !src.contains("google") && !src.contains("facebook") && processedUrls.add(src)) {
                Log.d(name, "Found iframe: $src")
                try {
                    if (loadExtractor(src, watchUrl, subtitleCallback, callback)) {
                        foundLinks = true
                    }
                } catch (e: Exception) {
                    Log.e(name, "Error extracting from iframe $src", e)
                }
            }
        }
        
        // Check original page too for servers
        if (!foundLinks) {
            val originalDoc = try {
                app.get(data).document
            } catch (e: Exception) {
                return foundLinks
            }
            
            originalDoc.select("li[data-index]").forEach { serverLi ->
                val embedUrl = serverLi.attr("data-index")
                if (embedUrl.isNotBlank() && processedUrls.add(embedUrl)) {
                    val serverName = serverLi.text().trim().ifBlank { "Server" }
                    Log.d(name, "Found server (original page): $serverName -> $embedUrl")
                    
                    try {
                        if (loadExtractor(embedUrl, data, subtitleCallback, callback)) {
                            foundLinks = true
                        }
                    } catch (e: Exception) {
                        Log.e(name, "Error extracting from $embedUrl", e)
                    }
                }
            }
        }
        
        return foundLinks
    }
}
