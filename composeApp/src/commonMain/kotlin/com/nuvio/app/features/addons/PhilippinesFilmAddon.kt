package com.nuvio.app.features.addons

import com.fleeksoft.ksoup.Ksoup
import io.ktor.http.Url
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object PhilippinesFilmAddon {
    const val sourceUrl = "https://krx18.com/tag/philippines-movie-18/"
    const val manifestUrl = "${sourceUrl}manifest.json"
    private const val idPrefix = "krx18:"
    private val filmResource = Regex("(meta|stream)/movie/krx18%3A([a-zA-Z0-9_-]+)\\.json")
    private val filmPath = Regex("/movies/([a-zA-Z0-9_-]+)/?")
    private const val manifest = """{
        "id":"app.nuvio.website.krx18.philippines",
        "name":"Philippines Films (18+)",
        "description":"Browse the Philippines film catalog and open films on the website.",
        "version":"1.0.0",
        "types":["movie"],
        "idPrefixes":["krx18:"],
        "resources":["catalog","meta","stream"],
        "catalogs":[{"type":"movie","id":"philippines-18","name":"Philippines Films (18+)"}],
        "behaviorHints":{"adult":true}
    }"""

    suspend fun response(url: String, forceRefresh: Boolean): String? {
        if (!url.startsWith(sourceUrl)) return null
        return when (val resource = url.removePrefix(sourceUrl)) {
            "manifest.json" -> manifest
            "catalog/movie/philippines-18.json" ->
                catalogResponse(fetchAddonHttpText(sourceUrl, forceRefresh))
            else -> {
                val (kind, slug) = filmResource.matchEntire(resource)?.destructured ?: return null
                val filmUrl = "https://krx18.com/movies/$slug/"
                if (kind == "meta") {
                    val html = fetchAddonHttpText(filmUrl, forceRefresh)
                    val document = Ksoup.parse(html, filmUrl)
                    val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
                    check(title.isNotEmpty()) { "The website did not return a recognizable film page." }
                    buildJsonObject {
                        put("meta", buildJsonObject {
                            put("id", "$idPrefix$slug")
                            put("type", "movie")
                            put("name", title)
                        })
                    }.toString()
                } else {
                    buildJsonObject {
                        put("streams", buildJsonArray {
                            addJsonObject {
                                put("name", "Open on website")
                                put("externalUrl", filmUrl)
                            }
                        })
                    }.toString()
                }
            }
        }
    }

    private fun catalogResponse(html: String): String {
        val document = Ksoup.parse(html, sourceUrl)
        val posters = document.select("a[href]").mapNotNull { anchor ->
            val slug = filmSlug(anchor.absUrl("href")) ?: return@mapNotNull null
            val image = anchor.selectFirst("img") ?: return@mapNotNull null
            val poster = sequenceOf("data-src", "data-lazy-src", "src")
                .map { image.absUrl(it) }
                .firstOrNull { it.startsWith("https://") } ?: return@mapNotNull null
            slug to poster
        }.toMap()
        val films = document.select("h2 a[href], h3 a[href], h4 a[href]").mapNotNull { anchor ->
            val slug = filmSlug(anchor.absUrl("href")) ?: return@mapNotNull null
            val title = anchor.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            slug to title
        }.distinctBy { it.first }

        check(films.isNotEmpty()) { "The website did not return a recognizable film listing." }
        return buildJsonObject {
            put("metas", buildJsonArray {
                for ((slug, title) in films) {
                    addJsonObject {
                        put("id", "$idPrefix$slug")
                        put("type", "movie")
                        put("name", title)
                        posters[slug]?.let { put("poster", it) }
                    }
                }
            })
        }.toString()
    }

    private fun filmSlug(url: String): String? {
        val parsed = runCatching { Url(url) }.getOrNull() ?: return null
        if (!parsed.host.equals("krx18.com", ignoreCase = true)) return null
        return filmPath.matchEntire(parsed.encodedPath)?.groupValues?.get(1)
    }
}
