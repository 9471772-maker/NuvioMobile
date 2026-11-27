package com.nuvio.app.features.downloads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Downloads a self-contained HLS VOD package into [stagingDirectory]. */
internal suspend fun transferAndroidHlsDownload(
    item: DownloadItem,
    stagingDirectory: File,
    client: OkHttpClient = downloadHttpClient,
    onProgress: (downloadedBytes: Long) -> Unit,
): File = coroutineScope {
    val activeCall = AtomicReference<Call?>(null)
    val downloadedBytes = AtomicLong(0L)

    val transfer = async(Dispatchers.IO) {
        try {
            val sourceOrigin = Request.Builder().url(item.sourceUrl).build().url
            val hlsClient = client.newBuilder()
                .addNetworkInterceptor { chain ->
                    val request = chain.request()
                    val target = request.url
                    val safeRequest = if (
                        target.scheme == sourceOrigin.scheme &&
                        target.host == sourceOrigin.host &&
                        target.port == sourceOrigin.port
                    ) {
                        request
                    } else {
                        request.newBuilder().apply {
                            item.sourceHeaders.keys.forEach { removeHeader(it) }
                        }.build()
                    }
                    chain.proceed(safeRequest)
                }
                .build()

            require(item.fileName.isNotBlank() && File(item.fileName).name == item.fileName) {
                "Invalid HLS download filename"
            }

            if (stagingDirectory.exists() && !stagingDirectory.deleteRecursively()) {
                throw IOException("Could not clear the partial HLS download")
            }
            if (!stagingDirectory.mkdirs() && !stagingDirectory.isDirectory) {
                throw IOException("Could not create HLS download directory")
            }

            fun report(bytes: Long) {
                onProgress(downloadedBytes.addAndGet(bytes))
            }

            fun request(url: String): Request = Request.Builder().url(url).apply {
                item.sourceHeaders.forEach { (key, value) ->
                    if (!key.equals("Range", true) &&
                        !key.equals("If-Range", true) &&
                        !key.equals("Accept-Encoding", true)
                    ) {
                        header(key, value)
                    }
                }
                header("Accept-Encoding", "identity")
            }.build()

            suspend fun fetchBytes(url: String): Pair<ByteArray, String> {
                currentCoroutineContext().ensureActive()
                val call = hlsClient.newCall(request(url))
                activeCall.set(call)

                return call.execute().use { response ->
                    if (!response.isSuccessful) throw DownloadHttpException(response.code)
                    val body = response.body ?: throw IOException("Empty HLS response")
                    body.bytes().also {
                        report(it.size.toLong())
                        activeCall.compareAndSet(call, null)
                    } to response.request.url.toString()
                }
            }

            suspend fun fetchFile(url: String, target: File) {
                currentCoroutineContext().ensureActive()
                target.parentFile?.let {
                    if (!it.isDirectory && !it.mkdirs()) {
                        throw IOException("Could not create HLS resource directory")
                    }
                }

                val call = hlsClient.newCall(request(url))
                activeCall.set(call)

                call.execute().use { response ->
                    if (!response.isSuccessful) throw DownloadHttpException(response.code)
                    val body = response.body ?: throw IOException("Empty HLS response")

                    body.byteStream().use { input ->
                        FileOutputStream(target).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                report(count.toLong())
                            }
                        }
                    }
                    activeCall.compareAndSet(call, null)
                }
            }

            suspend fun readPlaylist(url: String): HlsPlaylistParser.Playlist {
                val (bytes, finalUrl) = fetchBytes(url)
                return HlsPlaylistParser.parse(bytes.toString(Charsets.UTF_8), finalUrl)
            }

            fun validateMediaPlaylist(playlist: HlsPlaylistParser.Playlist) {
                if (!playlist.isVod) throw IOException("Live HLS playlists cannot be downloaded")
                if (playlist.hasByteRanges) {
                    throw IOException("HLS byte-range playlists are not supported yet")
                }
                if (playlist.lines.any { it.startsWith("#EXT-X-SESSION-KEY:") }) {
                    throw IOException("HLS session encryption keys are not supported")
                }
                if (playlist.encryptionMethods.any {
                    !it.equals("AES-128", true) && !it.equals("NONE", true)
                }) {
                    throw IOException("Unsupported HLS encryption method")
                }
                if (playlist.encryptionKeyFormats.any { !it.equals("identity", true) }) {
                    throw IOException("DRM-protected HLS content is not supported")
                }
                if (playlist.encryptionMethods.any { it.equals("AES-128", true) } &&
                    playlist.resources.none { it.type == HlsPlaylistParser.ResourceType.KEY }
                ) {
                    throw IOException("AES-128 HLS playlist is missing its key URI")
                }
                if (playlist.resources.any {
                    it.type == HlsPlaylistParser.ResourceType.PLAYLIST
                }) {
                    throw IOException("Nested HLS playlists are not supported")
                }
                if (playlist.resources.any {
                    it.type == HlsPlaylistParser.ResourceType.SUBTITLE
                }) {
                    throw IOException("External HLS subtitle playlists are not supported")
                }
            }

            fun safeExtension(url: String, fallback: String): String {
                val extension = url.substringBefore('#')
                    .substringBefore('?')
                    .substringAfterLast('.', "")
                return if (extension.matches(Regex("[A-Za-z0-9]{1,8}"))) {
                    ".$extension"
                } else {
                    fallback
                }
            }

            suspend fun downloadMediaPlaylist(
                playlist: HlsPlaylistParser.Playlist,
                relativeDirectory: String,
            ) {
                validateMediaPlaylist(playlist)

                val conflictingResource = playlist.resources
                    .groupBy { it.url }
                    .values
                    .firstOrNull { resources ->
                        resources.map { it.type }.distinct().size > 1
                    }
                if (conflictingResource != null) {
                    throw IOException("An HLS resource URL is used for conflicting resource types")
                }

                val localUris = linkedMapOf<String, String>()
                var segmentIndex = 0
                var mapIndex = 0
                var keyIndex = 0

                playlist.resources.distinctBy { it.url }.forEach { resource ->
                    currentCoroutineContext().ensureActive()

                    val relativePath = when (resource.type) {
                        HlsPlaylistParser.ResourceType.SEGMENT ->
                            "$relativeDirectory/segments/segment_${segmentIndex++}${safeExtension(resource.url, ".bin")}"
                        HlsPlaylistParser.ResourceType.MAP ->
                            "$relativeDirectory/maps/map_${mapIndex++}${safeExtension(resource.url, ".bin")}"
                        HlsPlaylistParser.ResourceType.KEY ->
                            "$relativeDirectory/keys/key_${keyIndex++}.key"
                        else -> throw IOException("Unsupported HLS resource in media playlist")
                    }.removePrefix("/")

                    val downloadedResource = File(stagingDirectory, relativePath)
                    fetchFile(resource.url, downloadedResource)
                    if (resource.type == HlsPlaylistParser.ResourceType.KEY &&
                        downloadedResource.length() != 16L
                    ) {
                        throw IOException("Invalid AES-128 HLS key: expected 16 bytes")
                    }

                    val localReference = if (relativeDirectory.isEmpty()) {
                        relativePath
                    } else {
                        relativePath.removePrefix("$relativeDirectory/")
                    }
                    localUris[resource.url] = localReference
                }

                val playlistPath = if (relativeDirectory.isEmpty()) {
                    "index.m3u8"
                } else {
                    "$relativeDirectory/index.m3u8"
                }

                val output = File(stagingDirectory, playlistPath)
                output.parentFile?.let {
                    if (!it.isDirectory && !it.mkdirs()) {
                        throw IOException("Could not create HLS playlist directory")
                    }
                }
                output.writeText(playlist.rewriteUris(localUris), Charsets.UTF_8)
            }

            val sourcePlaylist = readPlaylist(item.sourceUrl)
            if (sourcePlaylist.lines.any { it.startsWith("#EXT-X-SESSION-KEY:") }) {
                throw IOException("HLS session encryption keys are not supported")
            }
            if (sourcePlaylist.encryptionMethods.any {
                !it.equals("AES-128", true) && !it.equals("NONE", true)
            }) {
                throw IOException("Unsupported HLS encryption method")
            }
            if (sourcePlaylist.encryptionKeyFormats.any { !it.equals("identity", true) }) {
                throw IOException("DRM-protected HLS content is not supported")
            }
            if (sourcePlaylist.encryptionMethods.any { it.equals("AES-128", true) } &&
                sourcePlaylist.resources.none { it.type == HlsPlaylistParser.ResourceType.KEY }
            ) {
                throw IOException("AES-128 HLS playlist is missing its key URI")
            }

            val indexFile = File(stagingDirectory, "index.m3u8")

            if (!sourcePlaylist.isMaster) {
                downloadMediaPlaylist(sourcePlaylist, "")
            } else {
                val variant = sourcePlaylist.highestBandwidthVariant()
                    ?: throw IOException("HLS master playlist has no playable variants")

                val audioRendition = variant.audioGroup?.let { group ->
                    sourcePlaylist.renditions
                        .filter {
                            it.type.equals("AUDIO", true) &&
                                it.groupId == group &&
                                it.url != null
                        }
                        .sortedWith(
                            compareByDescending<HlsPlaylistParser.Rendition> { it.isDefault }
                                .thenByDescending { it.isAutoselect }
                        )
                        .firstOrNull()
                }

                val videoPlaylist = readPlaylist(variant.url)
                downloadMediaPlaylist(videoPlaylist, "video")

                if (audioRendition != null) {
                    val audioPlaylist = readPlaylist(audioRendition.url!!)
                    downloadMediaPlaylist(audioPlaylist, "audio")
                }

                val masterLines = mutableListOf("#EXTM3U", "#EXT-X-VERSION:6")

                if (audioRendition != null) {
                    val name = (audioRendition.name ?: "Audio")
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                    masterLines +=
                        "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"$name\",DEFAULT=YES,AUTOSELECT=YES,URI=\"audio/index.m3u8\""
                }

                val attributes = mutableListOf(
                    "BANDWIDTH=${variant.bandwidth.coerceAtLeast(1L)}"
                )
                variant.averageBandwidth?.let {
                    attributes += "AVERAGE-BANDWIDTH=$it"
                }
                variant.resolution?.let {
                    attributes += "RESOLUTION=$it"
                }
                variant.codecs?.let {
                    attributes += "CODECS=\"${it.replace("\"", "")}\""
                }
                if (audioRendition != null) attributes += "AUDIO=\"audio\""

                masterLines += "#EXT-X-STREAM-INF:${attributes.joinToString(",")}"
                masterLines += "video/index.m3u8"
                indexFile.writeText(
                    masterLines.joinToString("\n", postfix = "\n"),
                    Charsets.UTF_8
                )
            }

            if (!indexFile.isFile) {
                throw IOException("HLS download did not produce an index playlist")
            }
            stagingDirectory
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw error
        }
    }

    try {
        transfer.await()
    } finally {
        activeCall.getAndSet(null)?.cancel()
    }
}
