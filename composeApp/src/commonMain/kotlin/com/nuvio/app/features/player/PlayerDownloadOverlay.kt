package com.nuvio.app.features.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.debrid.toastMessage
import com.nuvio.app.features.downloads.DownloadsRepository
import kotlinx.coroutines.launch

@Composable
internal fun PlayerDownloadOverlay(runtime: PlayerScreenRuntime) {
    val downloadsState by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.uiState
    }.collectAsStateWithLifecycle()

    val stream = runtime.activeStream
    val sourceUrl = runtime.activeSourceUrl
    val shouldResolve = stream?.let {
        DirectDebridPlaybackResolver.shouldResolveToPlayableStream(it)
    } ?: false

    val directUrl = stream?.playableDirectUrl
    val canDownload = stream != null &&
        runtime.activeTorrentInfoHash == null &&
        runtime.p2pResolvedSourceUrl == null &&
        (
            directUrl == sourceUrl ||
                (shouldResolve && sourceUrl.startsWith("http", ignoreCase = true))
            )

    if (!canDownload) return

    var isEnqueuing by remember(stream, sourceUrl) {
        mutableStateOf(false)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        FloatingActionButton(
            onClick = {
                if (isEnqueuing) return@FloatingActionButton

                val currentStream = runtime.activeStream ?: return@FloatingActionButton
                val parentMetaId = runtime.parentMetaId
                val season = runtime.activeSeasonNumber
                val episode = runtime.activeEpisodeNumber

                val logicalKey = if (season != null && episode != null) {
                    "${parentMetaId.trim()}|$season|$episode"
                } else {
                    "${parentMetaId.trim()}|movie"
                }

                if (downloadsState.items.any { it.logicalContentKey == logicalKey }) {
                    NuvioToastController.show("This title is already in Downloads")
                    return@FloatingActionButton
                }

                isEnqueuing = true
                runtime.scope.launch {
                    try {
                        when (
                            val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(
                                stream = currentStream,
                                season = season,
                                episode = episode,
                            )
                        ) {
                            is DirectDebridPlayableResult.Success -> {
                                val result = DownloadsRepository.enqueueFromStream(
                                    contentType = runtime.contentType ?: runtime.parentMetaType,
                                    videoId = runtime.activeVideoId ?: runtime.parentMetaId,
                                    parentMetaId = parentMetaId,
                                    parentMetaType = runtime.parentMetaType,
                                    title = runtime.title,
                                    logo = runtime.logo,
                                    poster = runtime.poster,
                                    background = runtime.background,
                                    seasonNumber = season,
                                    episodeNumber = episode,
                                    episodeTitle = runtime.activeEpisodeTitle,
                                    episodeThumbnail = runtime.activeEpisodeThumbnail,
                                    stream = resolved.stream,
                                    replaceExisting = false,
                                )
                                NuvioToastController.show(result.toastMessage())
                            }

                            else -> {
                                resolved.toastMessage()?.let(NuvioToastController::show)
                            }
                        }
                    } finally {
                        isEnqueuing = false
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(24.dp),
        ) {
            Text(
                text = if (isEnqueuing) "…" else "↓",
                fontSize = 25.sp,
            )
        }
    }
}
