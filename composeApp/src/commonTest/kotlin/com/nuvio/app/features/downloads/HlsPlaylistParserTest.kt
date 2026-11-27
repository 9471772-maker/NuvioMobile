package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class HlsPlaylistParserTest {

    @Test
    fun parsesMasterPlaylistAndSelectsHighestBandwidthVariant() {
        val playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2400000,AVERAGE-BANDWIDTH=2100000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2",AUDIO="audio"
            high/index.m3u8
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",LANGUAGE="en",DEFAULT=YES,AUTOSELECT=YES,URI="audio/en.m3u8"
        """.trimIndent()

        val parsed = HlsPlaylistParser.parse(
            playlist,
            "https://media.example/master.m3u8"
        )

        assertTrue(parsed.isMaster)
        assertEquals(2, parsed.variants.size)
        assertEquals(
            "https://media.example/high/index.m3u8",
            parsed.highestBandwidthVariant()?.url
        )
        assertEquals("avc1.640028,mp4a.40.2", parsed.variants.last().codecs)
        assertEquals("audio", parsed.variants.last().audioGroup)
        assertEquals("https://media.example/audio/en.m3u8", parsed.renditions.single().url)
        assertEquals(3, parsed.resources.size)
    }

    @Test
    fun parsesVodSegmentsMapAndAes128Key() {
        val playlist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-KEY:METHOD=AES-128,URI="../keys/key.bin",IV=0x00000000000000000000000000000001
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:6.0,
            segments/000.ts?token=abc
            #EXTINF:5.5,
            segments/001.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val parsed = HlsPlaylistParser.parse(
            playlist,
            "https://media.example/path/video/index.m3u8"
        )

        assertFalse(parsed.isMaster)
        assertTrue(parsed.isVod)
        assertEquals(setOf("AES-128"), parsed.encryptionMethods)
        assertEquals(setOf("identity"), parsed.encryptionKeyFormats)
        assertFalse(parsed.hasByteRanges)
        assertEquals(
            listOf(
                "https://media.example/path/keys/key.bin",
                "https://media.example/path/video/init.mp4",
                "https://media.example/path/video/segments/000.ts?token=abc",
                "https://media.example/path/video/segments/001.ts"
            ),
            parsed.resources.map { it.url }
        )
        assertEquals(
            listOf(
                HlsPlaylistParser.ResourceType.KEY,
                HlsPlaylistParser.ResourceType.MAP,
                HlsPlaylistParser.ResourceType.SEGMENT,
                HlsPlaylistParser.ResourceType.SEGMENT
            ),
            parsed.resources.map { it.type }
        )
    }

    @Test
    fun rewritesSegmentMapAndKeyUris() {
        val playlist = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:4.0,
            segment.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val parsed = HlsPlaylistParser.parse(
            playlist,
            "https://media.example/video/index.m3u8"
        )

        val rewritten = parsed.rewriteUris(
            mapOf(
                "https://media.example/video/key.bin" to "resources/key_0.bin",
                "https://media.example/video/init.mp4" to "resources/map_0.mp4",
                "https://media.example/video/segment.ts" to "resources/segment_0.ts"
            )
        )

        assertTrue(rewritten.contains("""URI="resources/key_0.bin""""))
        assertTrue(rewritten.contains("""URI="resources/map_0.mp4""""))
        assertTrue(rewritten.contains("resources/segment_0.ts"))
        assertTrue(rewritten.contains("#EXT-X-ENDLIST"))
    }

    @Test
    fun resolvesSupportedUriReferenceForms() {
        val base = "https://media.example/a/b/master.m3u8"

        assertEquals(
            "https://media.example/a/b/child.m3u8",
            HlsPlaylistParser.resolveUrl(base, "child.m3u8")
        )
        assertEquals(
            "https://media.example/a/child.m3u8",
            HlsPlaylistParser.resolveUrl(base, "../child.m3u8")
        )
        assertEquals(
            "https://media.example/root/child.ts",
            HlsPlaylistParser.resolveUrl(base, "/root/child.ts")
        )
        assertEquals(
            "https://cdn.example/child.ts",
            HlsPlaylistParser.resolveUrl(base, "//cdn.example/child.ts")
        )
        assertEquals(
            "https://media.example/a/b/master.m3u8?token=abc",
            HlsPlaylistParser.resolveUrl(base, "?token=abc")
        )
        assertEquals(
            "https://media.example/a/b/master.m3u8#section",
            HlsPlaylistParser.resolveUrl(base, "#section")
        )
        assertEquals(
            "https://other.example/file.ts",
            HlsPlaylistParser.resolveUrl(base, "https://other.example/file.ts")
        )
    }

    @Test
    fun rejectsPlaylistWithoutExtm3uHeader() {
        assertFailsWith<HlsPlaylistParser.ParseException> {
            HlsPlaylistParser.parse(
                "#EXTINF:4.0,\nsegment.ts",
                "https://media.example/video.m3u8"
            )
        }
    }

    @Test
    fun rejectsMasterPlaylistWithMissingVariantUri() {
        assertFailsWith<HlsPlaylistParser.ParseException> {
            HlsPlaylistParser.parse(
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100000",
                "https://media.example/master.m3u8"
            )
        }
    }

    @Test
    fun mediaPlaylistWithoutEndListIsNotVod() {
        val parsed = HlsPlaylistParser.parse(
            "#EXTM3U\n#EXTINF:4.0,\nsegment.ts",
            "https://media.example/live.m3u8"
        )

        assertFalse(parsed.isMaster)
        assertFalse(parsed.isVod)
        assertNull(parsed.highestBandwidthVariant())
    }

    @Test
    fun detectsMapByteRangesAndPreservesEncryptionKeyFormat() {
        val parsed = HlsPlaylistParser.parse(
            """
                #EXTM3U
                #EXT-X-KEY:METHOD=AES-128,URI="key.bin",KEYFORMAT="com.example.drm"
                #EXT-X-MAP:URI="init.mp4",BYTERANGE="100@0"
                #EXTINF:4.0,
                segment.ts
                #EXT-X-ENDLIST
            """.trimIndent(),
            "https://media.example/video/index.m3u8"
        )

        assertTrue(parsed.hasByteRanges)
        assertEquals(setOf("AES-128"), parsed.encryptionMethods)
        assertEquals(setOf("com.example.drm"), parsed.encryptionKeyFormats)
    }
}
