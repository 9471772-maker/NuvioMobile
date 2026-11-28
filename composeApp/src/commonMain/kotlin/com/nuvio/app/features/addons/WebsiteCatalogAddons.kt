package com.nuvio.app.features.addons

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import io.ktor.http.Url
import io.ktor.http.decodeURLPart
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object WebsiteCatalogAddons {
    private data class Source(
        val key: String,
        val name: String,
        val url: String,
        val itemPath: Regex,
        val selector: String,
        val adult: Boolean = true,
    ) {
        val base = url.trimEnd('/') + "/"
        val host = Url(url).host
        val origin = "https://$host"
        val prefix = "$key:"
        val catalogId = if (key == "krx18") "philippines-18" else "website"
        val addonId = if (key == "krx18") "app.nuvio.website.krx18.philippines" else "app.nuvio.website.$key"

        fun itemId(path: String): String =
            prefix + if (key == "krx18") path.removePrefix("/movies/") else path

        fun itemUrl(id: String): String? {
            if (!id.startsWith(prefix)) return null
            val suffix = id.removePrefix(prefix)
            val path = if (key == "krx18") "/movies/$suffix" else suffix
            if (!itemPath.matches(path)) return null
            return origin + path + if (key == "moviebox") "" else "/"
        }
    }

    private val sources = listOf(
        Source("krx18", "Philippines Films (18+)", "https://krx18.com/tag/philippines-movie-18/",
            Regex("/movies/[a-zA-Z0-9_-]+"), "h2 a[href], h3 a[href], h4 a[href]"),
        Source("pinayjuicy", "PinayJuicy · Pinoy Movies (18+)", "https://pinayjuicy.com/categories/pinoy-movies-18/page/5/",
            Regex("/[a-zA-Z0-9_-]+"), "a[href]:has(img), h2 a[href], h3 a[href]"),
        Source("erome", "EroMe Explore (18+)", "https://www.erome.com/explore",
            Regex("/a/[a-zA-Z0-9]+"), "a[href]"),
        Source("koreanpornmovie", "Korean Films (18+)", "https://koreanpornmovie.com/",
            Regex("/[a-zA-Z0-9_-]+"), "a[href]:has(img), h2 a[href], h3 a[href]"),
        Source("eporner", "Eporner Videos (18+)", "https://www.eporner.com/",
            Regex("/hd-porn/[a-zA-Z0-9]+/[a-zA-Z0-9_-]+"), "a[href]"),
        Source("moviebox", "MovieBox Films", "https://moviebox01.com/",
            Regex("/detail/[a-zA-Z0-9_-]+"), "article:has(a[href*=SUBJECTS_MOVIE]) a.movie-card", adult = false),
    )
    private val itemResource = Regex("(meta|stream)/movie/([^/]+)\\.json")
    private val durationPrefix = Regex("^\\d{1,2}:\\d{2}(?::\\d{2})?\\s+")
    private val counterOnly = Regex("[\\d\\s.,:%]+")

    fun manifestForSource(url: String): String? =
        sources.firstOrNull { it.base.trimEnd('/') == url }?.let { "${it.base}manifest.json" }

    suspend fun response(url: String, forceRefresh: Boolean): String? {
        val source = sources.firstOrNull { url.startsWith(it.base) } ?: return null
        return when (val resource = url.removePrefix(source.base)) {
            "manifest.json" -> manifest(source)
            "catalog/movie/${source.catalogId}.json" ->
                catalog(source, fetchAddonHttpText(source.url, forceRefresh))
            else -> {
                val (kind, encodedId) = itemResource.matchEntire(resource)?.destructured ?: return null
                val id = encodedId.decodeURLPart()
                val itemUrl = source.itemUrl(id) ?: return null
                if (kind == "meta") {
                    val document = Ksoup.parse(fetchAddonHttpText(itemUrl, forceRefresh), itemUrl)
                    val title = document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
                        ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
                        ?: document.title().takeIf { it.isNotBlank() }
                        ?: error("The website did not return a recognizable film page.")
                    buildJsonObject {
                        put("meta", buildJsonObject {
                            put("id", id)
                            put("type", "movie")
                            put("name", title)
                        })
                    }.toString()
                } else {
                    buildJsonObject {
                        put("streams", buildJsonArray {
                            addJsonObject {
                                put("name", "Open on website")
                                put("externalUrl", itemUrl)
                            }
                        })
                    }.toString()
                }
            }
        }
    }

    private fun manifest(source: Source): String = buildJsonObject {
        put("id", source.addonId)
        put("name", source.name)
        put("description", "Browse the catalog and open titles on the website.")
        put("version", "1.0.0")
        put("types", buildJsonArray { add(JsonPrimitive("movie")) })
        put("idPrefixes", buildJsonArray { add(JsonPrimitive(source.prefix)) })
        put("resources", buildJsonArray {
            for (resource in listOf("catalog", "meta", "stream")) add(JsonPrimitive(resource))
        })
        put("catalogs", buildJsonArray {
            addJsonObject {
                put("type", "movie")
                put("id", source.catalogId)
                put("name", source.name)
            }
        })
        put("behaviorHints", buildJsonObject { put("adult", source.adult) })
    }.toString()

    private fun catalog(source: Source, html: String): String {
        val document = Ksoup.parse(html, source.url)
        val posters = document.select("a[href]").mapNotNull { anchor ->
            val path = itemPath(source, anchor) ?: return@mapNotNull null
            val image = anchor.selectFirst("img") ?: return@mapNotNull null
            val poster = sequenceOf("data-src", "data-lazy-src", "src")
                .map(image::absUrl).firstOrNull { it.startsWith("https://") } ?: return@mapNotNull null
            path to poster
        }.toMap()
        val items = document.select(source.selector).mapNotNull { anchor ->
            val path = itemPath(source, anchor) ?: return@mapNotNull null
            val title = sequenceOf(anchor.attr("title"), anchor.text(), anchor.selectFirst("img")?.attr("alt").orEmpty())
                .map { it.trim().removePrefix("go to ").removeSuffix(" detail page").replace(durationPrefix, "").trim() }
                .firstOrNull { it.isNotBlank() && !counterOnly.matches(it) } ?: return@mapNotNull null
            path to title
        }.distinctBy { it.first }
        check(items.isNotEmpty()) { "The website did not return a recognizable catalog." }

        return buildJsonObject {
            put("metas", buildJsonArray {
                for ((path, title) in items) {
                    addJsonObject {
                        put("id", source.itemId(path))
                        put("type", "movie")
                        put("name", title)
                        posters[path]?.let { put("poster", it) }
                    }
                }
            })
        }.toString()
    }

    private fun itemPath(source: Source, anchor: Element): String? {
        val url = runCatching { Url(anchor.absUrl("href")) }.getOrNull() ?: return null
        if (!url.host.equals(source.host, ignoreCase = true)) return null
        return url.encodedPath.trimEnd('/').takeIf(source.itemPath::matches)
    }
}
