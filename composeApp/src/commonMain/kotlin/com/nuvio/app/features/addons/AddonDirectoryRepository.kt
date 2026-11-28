package com.nuvio.app.features.addons

import nuvio.composeapp.generated.resources.Res
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class AddonDirectoryEntry(
    val manifestUrl: String,
    val name: String,
    val description: String,
    val logoUrl: String?,
    val stars: Int,
    val isNsfw: Boolean,
    val categories: List<String>,
    val isAlternativeInstance: Boolean,
)

internal data class AddonDirectoryPage(
    val entries: List<AddonDirectoryEntry>,
    val page: Int,
    val totalPages: Int,
    val total: Int,
    val hasNextPage: Boolean,
)

internal object AddonDirectoryRepository {
    private const val PAGE_SIZE = 100
    private const val API_URL = "https://stremio-addons.net/api/v0/addons"

    private val json = Json {
        ignoreUnknownKeys = true
    }

    @OptIn(org.jetbrains.compose.resources.ExperimentalResourceApi::class)
    suspend fun recommended(): List<AddonDirectoryEntry> {
        val bytes = Res.readBytes("files/recommended-addons.json")
        return json.parseToJsonElement(bytes.decodeToString()).jsonArray.mapNotNull {
            it.asObject()?.toDirectoryEntry(false)
        }
    }

    suspend fun fetchPage(
        page: Int = 1,
        search: String = "",
    ): AddonDirectoryPage {
        val url = buildString {
            append(API_URL)
            append("?page=")
            append(page.coerceAtLeast(1))
            append("&limit=")
            append(PAGE_SIZE)
            append("&sort_by=stars&order=desc")
            if (search.isNotBlank()) {
                append("&search=")
                append(search.trim().encodeURLParameter())
            }
        }

        val root = json.parseToJsonElement(httpGetText(url)).jsonObject
        val addons = root["addons"]?.asArray().orEmpty()
        val pagination = root["pagination"]?.asObject()

        val entries = buildList {
            addons.forEach { element ->
                val addon = element.asObject() ?: return@forEach
                addon.toDirectoryEntry(false)?.let(::add)

                addon["instances"].asArray().forEach { instanceElement ->
                    instanceElement.asObject()
                        ?.toDirectoryEntry(true, addon.categoryLabels())
                        ?.let(::add)
                }
            }
        }

        return AddonDirectoryPage(
            entries = entries,
            page = pagination?.int("page") ?: page,
            totalPages = pagination?.int("totalPages") ?: page,
            total = pagination?.int("total") ?: 0,
            hasNextPage = pagination?.boolean("hasNextPage") ?: false,
        )
    }

    private fun JsonObject.toDirectoryEntry(
        isAlternativeInstance: Boolean,
        inherited: List<String> = emptyList(),
    ): AddonDirectoryEntry? {
        val manifestUrl = string("manifestUrl").trim()
        if (!manifestUrl.startsWith("https://") && !manifestUrl.startsWith("http://")) {
            return null
        }

        val manifest = this["manifest"].asObject()
        val labels = categoryLabels()
        return AddonDirectoryEntry(
            manifestUrl = manifestUrl,
            name = manifest?.string("name")
                ?.takeIf(String::isNotBlank)
                ?: string("slug").ifBlank { manifestUrl.substringAfterLast('/').ifBlank { "Addon" } },
            description = manifest?.string("description").orEmpty(),
            logoUrl = manifest?.string("logo")?.takeIf(String::isNotBlank),
            stars = int("stars"),
            isNsfw = boolean("nsfw") || "Adult (18+)" in inherited || "Adult (18+)" in labels,
            categories = (inherited + labels).distinctBy { it.lowercase() }.ifEmpty { listOf("Other") },
            isAlternativeInstance = isAlternativeInstance,
        )
    }

    private fun JsonObject.categoryLabels(): List<String> = buildList {
        val manifest = this@categoryLabels["manifest"].asObject()
        if (this@categoryLabels.boolean("nsfw")) add("Adult (18+)")
        for (tag in this@categoryLabels["categories"].asArray()) {
            val label = tag.asObject()?.string("name") ?: tag.jsonPrimitiveOrNull()?.contentOrNull
            label?.trim()?.takeIf { it.isNotEmpty() }?.let { add(contentLabel(it)) }
        }
        for (type in manifest?.get("types").asArray()) {
            when (type.jsonPrimitiveOrNull()?.contentOrNull) {
                "movie" -> add("Films")
                "series" -> add("Series")
                "tv", "channel" -> add("Live TV")
            }
        }
        for (catalog in manifest?.get("catalogs").asArray()) {
            for (extra in catalog.asObject()?.get("extra").asArray()) {
                val filter = extra.asObject() ?: continue
                if (!filter.string("name").equals("genre", ignoreCase = true)) continue
                for (option in filter["options"].asArray()) {
                    val genre = option.jsonPrimitiveOrNull()?.contentOrNull?.trim().orEmpty()
                    if (genre.isNotEmpty()) add(contentLabel(genre))
                }
            }
        }
        for (resource in manifest?.get("resources").asArray()) {
            when (resource.asObject()?.string("name") ?: resource.jsonPrimitiveOrNull()?.contentOrNull) {
                "catalog" -> add("Catalogs")
                "meta" -> add("Metadata")
                "stream" -> add("Streams")
                "subtitles" -> add("Subtitles")
            }
        }
    }

    private fun contentLabel(label: String): String = when (label.lowercase()) {
        "adult", "nsfw", "18+", "adult (18+)" -> "Adult (18+)"
        "movies", "movie", "film", "films" -> "Films"
        "tv shows", "tv series", "series" -> "Series"
        "live tv" -> "Live TV"
        else -> label.replaceFirstChar(Char::uppercase)
    }

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitiveOrNull()?.contentOrNull.orEmpty()

    private fun JsonObject.int(key: String): Int =
        this[key]?.jsonPrimitiveOrNull()?.intOrNull ?: 0

    private fun JsonObject.boolean(key: String): Boolean =
        this[key]?.jsonPrimitiveOrNull()?.booleanOrNull ?: false

    private fun JsonElement?.asObject(): JsonObject? =
        (this as? JsonObject)

    private fun JsonElement?.asArray(): List<JsonElement> =
        (this as? JsonArray)?.jsonArray?.toList().orEmpty()

    private fun JsonElement?.jsonPrimitiveOrNull(): JsonPrimitive? =
        this as? JsonPrimitive
}
