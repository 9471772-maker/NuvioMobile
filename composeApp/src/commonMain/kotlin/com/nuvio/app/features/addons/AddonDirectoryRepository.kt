package com.nuvio.app.features.addons

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
    val hasNextPage: Boolean,
)

internal object AddonDirectoryRepository {
    private const val PAGE_SIZE = 25
    private const val API_URL = "https://stremio-addons.net/api/v0/addons"

    private val json = Json {
        ignoreUnknownKeys = true
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
                        ?.toDirectoryEntry(true)
                        ?.let(::add)
                }
            }
        }

        return AddonDirectoryPage(
            entries = entries,
            page = pagination?.int("page") ?: page,
            totalPages = pagination?.int("totalPages") ?: page,
            hasNextPage = pagination?.boolean("hasNextPage") ?: false,
        )
    }

    private fun JsonObject.toDirectoryEntry(
        isAlternativeInstance: Boolean,
    ): AddonDirectoryEntry? {
        val manifestUrl = string("manifestUrl").trim()
        if (!manifestUrl.startsWith("https://") && !manifestUrl.startsWith("http://")) {
            return null
        }

        val manifest = this["manifest"].asObject()
        val categories = this["categories"].asArray().mapNotNull { category ->
            category.asObject()?.string("name")?.takeIf(String::isNotBlank)
        }

        return AddonDirectoryEntry(
            manifestUrl = manifestUrl,
            name = manifest?.string("name")
                ?.takeIf(String::isNotBlank)
                ?: string("slug").ifBlank { manifestUrl.substringAfterLast('/').ifBlank { "Addon" } },
            description = manifest?.string("description").orEmpty(),
            logoUrl = manifest?.string("logo")?.takeIf(String::isNotBlank),
            stars = int("stars"),
            isNsfw = boolean("nsfw"),
            categories = categories,
            isAlternativeInstance = isAlternativeInstance,
        )
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
