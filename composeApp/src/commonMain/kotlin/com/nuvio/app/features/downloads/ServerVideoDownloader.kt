package com.nuvio.app.features.downloads

import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.features.servers.ServerStorage
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val SERVER_URL_KEY = "video_downloader.url"
private const val SERVER_TOKEN_KEY = "video_downloader.token"

private val serverJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

data class ServerVideoConfig(
    val baseUrl: String,
    val token: String,
)

@Serializable
data class ServerVideoInfoResponse(
    val site: String? = null,
    val extractor: String? = null,
    val videos: List<ServerVideoCard> = emptyList(),
    val error: String? = null,
)

@Serializable
data class ServerVideoCard(
    val title: String = "Video",
    val thumbnail: String? = null,
    val duration: Double? = null,
    val uploader: String? = null,
    val options: List<ServerVideoOption> = emptyList(),
    val src: String? = null,
    val ref: String? = null,
    val item: Int? = null,
)

@Serializable
data class ServerVideoOption(
    val label: String = "Download",
    val q: String = "best",
)

@Serializable
data class ServerVideoStartResponse(
    val id: String? = null,
    val error: String? = null,
)

@Serializable
data class ServerVideoProgress(
    val status: String = "queued",
    val percent: Double? = null,
    val speed: String? = null,
    val eta: String? = null,
    val downloaded: Long? = null,
    val total: Long? = null,
    val name: String? = null,
    val size: String? = null,
    val error: String? = null,
    val note: String? = null,
)

object ServerVideoDownloader {
    fun configuration(): ServerVideoConfig? {
        val url = ServerStorage.read(SERVER_URL_KEY)
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: return null
        val token = ServerStorage.read(SERVER_TOKEN_KEY)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return ServerVideoConfig(url, token)
    }

    suspend fun fetchInfo(
        config: ServerVideoConfig,
        url: String,
        mode: String,
    ): ServerVideoInfoResponse = request(config) { client ->
        val response = client.post("${config.baseUrl}/api/app/info") {
            header("X-Token", config.token)
            contentType(ContentType.Application.Json)
            setBody(serverJson.encodeToString(mapOf("url" to url, "mode" to mode)))
        }
        val text = response.bodyAsText()
        val result = serverJson.decodeFromString<ServerVideoInfoResponse>(text)
        if (response.status.value !in 200..299) {
            error(result.error ?: "Server could not inspect this link.")
        }
        result
    }

    suspend fun start(
        config: ServerVideoConfig,
        url: String,
        option: ServerVideoOption,
        card: ServerVideoCard,
    ): String = request(config) { client ->
        val body = buildMap<String, String> {
            put("url", url)
            put("q", option.q)
            card.src?.let { put("src", it) }
            card.ref?.let { put("ref", it) }
            card.item?.let { put("item", it.toString()) }
        }
        val response = client.post("${config.baseUrl}/api/app/start") {
            header("X-Token", config.token)
            contentType(ContentType.Application.Json)
            setBody(serverJson.encodeToString(body))
        }
        val text = response.bodyAsText()
        val result = serverJson.decodeFromString<ServerVideoStartResponse>(text)
        if (response.status.value !in 200..299) {
            error(result.error ?: "Server could not start the download.")
        }
        result.id?.takeIf { it.isNotBlank() }
            ?: error(result.error ?: "Server did not return a download ID.")
    }

    suspend fun progress(
        config: ServerVideoConfig,
        jobId: String,
    ): ServerVideoProgress = request(config) { client ->
        val response = client.get("${config.baseUrl}/api/app/progress/$jobId") {
            header("X-Token", config.token)
        }
        val text = response.bodyAsText()
        val result = serverJson.decodeFromString<ServerVideoProgress>(text)
        if (response.status.value !in 200..299) {
            error(result.error ?: "Could not read server download progress.")
        }
        result
    }

    fun fileUrl(config: ServerVideoConfig, jobId: String): String =
        "${config.baseUrl}/api/app/file/$jobId"

    private suspend fun <T> request(
        config: ServerVideoConfig,
        block: suspend (io.ktor.client.HttpClient) -> T,
    ): T {
        val client = createApiHttpClient()
        return try {
            block(client)
        } finally {
            client.close()
        }
    }
}
