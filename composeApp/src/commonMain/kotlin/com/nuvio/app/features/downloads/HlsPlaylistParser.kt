package com.nuvio.app.features.downloads

/**
 * Platform-independent HLS playlist parsing and URI resolution.
 *
 * Network requests and file operations deliberately belong to the platform
 * downloaders. This class only parses and rewrites playlist text.
 */
internal object HlsPlaylistParser {

    enum class ResourceType {
        PLAYLIST,
        SEGMENT,
        MAP,
        KEY,
        SUBTITLE,
    }

    data class Resource(
        val url: String,
        val type: ResourceType,
        val lineIndex: Int,
        val attributeName: String? = null,
        val originalUri: String = url,
    )

    data class Variant(
        val url: String,
        val bandwidth: Long,
        val averageBandwidth: Long?,
        val resolution: String?,
        val codecs: String?,
        val audioGroup: String?,
    )

    data class Rendition(
        val type: String,
        val groupId: String?,
        val name: String?,
        val url: String?,
        val language: String?,
        val isDefault: Boolean,
        val isAutoselect: Boolean,
    )

    data class Playlist(
        val sourceUrl: String,
        val isMaster: Boolean,
        val isEndList: Boolean,
        val variants: List<Variant>,
        val renditions: List<Rendition>,
        val resources: List<Resource>,
        val encryptionMethods: Set<String>,
        val encryptionKeyFormats: Set<String>,
        val hasByteRanges: Boolean,
        internal val lines: List<String>,
    ) {
        val isVod: Boolean
            get() = !isMaster && isEndList

        fun highestBandwidthVariant(): Variant? =
            variants.maxWithOrNull(
                compareBy<Variant> { it.bandwidth }
                    .thenBy { it.averageBandwidth ?: 0L }
            )

        /**
         * Replaces remote resource URIs with local relative paths.
         * Keys in [localUris] are fully resolved remote URLs.
         */
        fun rewriteUris(localUris: Map<String, String>): String {
            val rewritten = lines.toMutableList()

            resources.groupBy { it.lineIndex }.forEach { (lineIndex, lineResources) ->
                var line = rewritten[lineIndex]

                lineResources.forEach resourceLoop@ { resource ->
                    val local = localUris[resource.url] ?: return@resourceLoop
                    val attribute = resource.attributeName

                    line = if (attribute == null) {
                        local
                    } else {
                        val quoted = "$attribute=\"${resource.originalUri}\""
                        val unquoted = "$attribute=${resource.originalUri}"
                        when {
                            line.contains(quoted) ->
                                line.replace(quoted, "$attribute=\"$local\"")
                            line.contains(unquoted) ->
                                line.replace(unquoted, "$attribute=$local")
                            else -> line
                        }
                    }
                }

                rewritten[lineIndex] = line
            }

            return rewritten.joinToString("\n")
        }
    }

    class ParseException(message: String) : IllegalArgumentException(message)

    fun parse(text: String, playlistUrl: String): Playlist {
        val normalized = text.removePrefix("\uFEFF")
        val lines = normalized
            .lineSequence()
            .map { it.trim() }
            .toList()

        if (lines.isEmpty() || lines.firstOrNull() != "#EXTM3U") {
            throw ParseException("Invalid HLS playlist: missing #EXTM3U header")
        }

        val variants = mutableListOf<Variant>()
        val renditions = mutableListOf<Rendition>()
        val resources = mutableListOf<Resource>()
        val encryptionMethods = linkedSetOf<String>()
        val encryptionKeyFormats = linkedSetOf<String>()
        var hasByteRanges = false
        var isEndList = false
        var pendingVariantAttributes: Map<String, String>? = null

        lines.forEachIndexed { index, line ->
            if (line.isEmpty()) return@forEachIndexed

            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    pendingVariantAttributes =
                        parseAttributes(line.substringAfter(':'))
                }

                line.startsWith("#EXT-X-MEDIA:") -> {
                    val attributes = parseAttributes(line.substringAfter(':'))
                    val type = attributes["TYPE"].orEmpty()
                    val originalUri = attributes["URI"]
                    val resolvedUri = originalUri?.let {
                        resolveUrl(playlistUrl, it)
                    }

                    renditions += Rendition(
                        type = type,
                        groupId = attributes["GROUP-ID"],
                        name = attributes["NAME"],
                        url = resolvedUri,
                        language = attributes["LANGUAGE"],
                        isDefault = attributes["DEFAULT"].equals("YES", true),
                        isAutoselect = attributes["AUTOSELECT"].equals("YES", true),
                    )

                    if (originalUri != null && resolvedUri != null) {
                        resources += Resource(
                            url = resolvedUri,
                            type = if (type.equals("SUBTITLES", true)) {
                                ResourceType.SUBTITLE
                            } else {
                                ResourceType.PLAYLIST
                            },
                            lineIndex = index,
                            attributeName = "URI",
                            originalUri = originalUri,
                        )
                    }
                }

                line.startsWith("#EXT-X-SESSION-KEY:") -> {
                    val attributes = parseAttributes(line.substringAfter(':'))
                    val method = attributes["METHOD"].orEmpty()
                    if (method.isNotEmpty()) encryptionMethods += method
                    encryptionKeyFormats += attributes["KEYFORMAT"] ?: "identity"
                    addAttributeResource(
                        resources, index, attributes, playlistUrl,
                        ResourceType.KEY, "URI"
                    )
                }

                line.startsWith("#EXT-X-KEY:") -> {
                    val attributes = parseAttributes(line.substringAfter(':'))
                    val method = attributes["METHOD"].orEmpty()
                    if (method.isNotEmpty()) encryptionMethods += method
                    encryptionKeyFormats += attributes["KEYFORMAT"] ?: "identity"
                    addAttributeResource(
                        resources, index, attributes, playlistUrl,
                        ResourceType.KEY, "URI"
                    )
                }

                line.startsWith("#EXT-X-MAP:") -> {
                    val attributes = parseAttributes(line.substringAfter(':'))
                    if (attributes.containsKey("BYTERANGE")) hasByteRanges = true
                    addAttributeResource(
                        resources, index, attributes, playlistUrl,
                        ResourceType.MAP, "URI"
                    )
                }

                line.startsWith("#EXT-X-BYTERANGE:") ||
                    line.startsWith("#EXT-X-BYTERANGE", ignoreCase = true) -> {
                    hasByteRanges = true
                }

                line == "#EXT-X-ENDLIST" -> {
                    isEndList = true
                }

                line.startsWith("#") -> Unit

                else -> {
                    val resolved = resolveUrl(playlistUrl, line)
                    val attributes = pendingVariantAttributes

                    if (attributes != null) {
                        variants += Variant(
                            url = resolved,
                            bandwidth = attributes["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                            averageBandwidth = attributes["AVERAGE-BANDWIDTH"]?.toLongOrNull(),
                            resolution = attributes["RESOLUTION"],
                            codecs = attributes["CODECS"],
                            audioGroup = attributes["AUDIO"],
                        )
                        resources += Resource(
                            url = resolved,
                            type = ResourceType.PLAYLIST,
                            lineIndex = index,
                            originalUri = line,
                        )
                        pendingVariantAttributes = null
                    } else {
                        resources += Resource(
                            url = resolved,
                            type = ResourceType.SEGMENT,
                            lineIndex = index,
                            originalUri = line,
                        )
                    }
                }
            }
        }

        if (pendingVariantAttributes != null) {
            throw ParseException("Invalid master playlist: variant URI is missing")
        }

        val isMaster = variants.isNotEmpty() ||
            lines.any { it.startsWith("#EXT-X-MEDIA:") }

        return Playlist(
            sourceUrl = playlistUrl,
            isMaster = isMaster,
            isEndList = isEndList,
            variants = variants,
            renditions = renditions,
            resources = resources,
            encryptionMethods = encryptionMethods,
            encryptionKeyFormats = encryptionKeyFormats,
            hasByteRanges = hasByteRanges,
            lines = lines,
        )
    }

    private fun addAttributeResource(
        resources: MutableList<Resource>,
        lineIndex: Int,
        attributes: Map<String, String>,
        baseUrl: String,
        type: ResourceType,
        attributeName: String,
    ) {
        val originalUri = attributes[attributeName] ?: return
        resources += Resource(
            url = resolveUrl(baseUrl, originalUri),
            type = type,
            lineIndex = lineIndex,
            attributeName = attributeName,
            originalUri = originalUri,
        )
    }

    /**
     * Parses comma-separated HLS attributes while respecting quoted values.
     */
    private fun parseAttributes(value: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false

        value.forEach { character ->
            when {
                character == '"' -> {
                    inQuotes = !inQuotes
                    current.append(character)
                }
                character == ',' && !inQuotes -> {
                    parts += current.toString()
                    current.clear()
                }
                else -> current.append(character)
            }
        }
        if (current.isNotEmpty()) parts += current.toString()

        parts.forEach { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) return@forEach

            val key = part.substring(0, separator).trim().uppercase()
            val rawValue = part.substring(separator + 1).trim()
            val parsedValue = if (
                rawValue.length >= 2 &&
                rawValue.first() == '"' &&
                rawValue.last() == '"'
            ) {
                rawValue.substring(1, rawValue.length - 1)
            } else {
                rawValue
            }

            result[key] = parsedValue
        }

        return result
    }

    /**
     * Resolves an HLS URI reference without platform URL APIs.
     * Supports absolute, protocol-relative, root-relative, query-only,
     * fragment-only, and ordinary relative references.
     */
    fun resolveUrl(baseUrl: String, reference: String): String {
        val ref = reference.trim()
        if (ref.isEmpty()) return baseUrl

        if (SCHEME_REGEX.containsMatchIn(ref)) return ref

        val base = parseAbsoluteUrl(baseUrl)
            ?: throw ParseException("Invalid base URL: $baseUrl")

        if (ref.startsWith("//")) return "${base.scheme}:$ref"

        if (ref.startsWith("#")) {
            return "${base.origin}${base.path}${base.query}$ref"
        }

        if (ref.startsWith("?")) {
            return "${base.origin}${base.path}$ref"
        }

        val referenceWithoutFragment = ref.substringBefore('#')
        val fragment = ref.substringAfter('#', "").let {
            if (ref.contains('#')) "#$it" else ""
        }
        val referencePath = referenceWithoutFragment.substringBefore('?')
        val query = referenceWithoutFragment.substringAfter('?', "").let {
            if (referenceWithoutFragment.contains('?')) "?$it" else ""
        }

        val resolvedPath = when {
            referencePath.startsWith("/") -> normalizePath(referencePath)
            referencePath.isEmpty() -> base.path
            else -> {
                val directory = base.path.substringBeforeLast('/', "")
                normalizePath("$directory/$referencePath")
            }
        }

        return "${base.origin}$resolvedPath$query$fragment"
    }

    private data class ParsedUrl(
        val scheme: String,
        val origin: String,
        val path: String,
        val query: String,
    )

    private fun parseAbsoluteUrl(value: String): ParsedUrl? {
        val match = ABSOLUTE_URL_REGEX.matchEntire(value.trim()) ?: return null
        val scheme = match.groupValues[1]
        val authority = match.groupValues[2]
        val remainder = match.groupValues[3]

        if (authority.isBlank()) return null

        val pathAndQuery = remainder.substringBefore('#')
        val path = pathAndQuery.substringBefore('?').ifEmpty { "/" }
        val query = pathAndQuery.substringAfter('?', "").let {
            if (pathAndQuery.contains('?')) "?$it" else ""
        }

        return ParsedUrl(
            scheme = scheme,
            origin = "$scheme://$authority",
            path = path,
            query = query,
        )
    }

    private fun normalizePath(path: String): String {
        val output = mutableListOf<String>()
        path.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (output.isNotEmpty()) output.removeAt(output.lastIndex)
                else -> output += segment
            }
        }

        val normalized = "/" + output.joinToString("/")
        return if (path.endsWith('/') && normalized != "/") "$normalized/" else normalized
    }

    private val SCHEME_REGEX = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    private val ABSOLUTE_URL_REGEX =
        Regex("^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]+)(.*)$")
}
