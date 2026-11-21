package com.nuvio.app.features.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ServerVideoDownloadDialog(
    url: String,
    contentType: String,
    videoId: String,
    parentMetaId: String,
    parentMetaType: String,
    title: String,
    logo: String?,
    poster: String?,
    background: String?,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    episodeThumbnail: String?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val config = remember { ServerVideoDownloader.configuration() }
    var cards by remember { mutableStateOf<List<ServerVideoCard>>(emptyList()) }
    var selectedCardIndex by remember { mutableStateOf(0) }
    var selectedOptionIndex by remember { mutableStateOf(0) }
    var progress by remember { mutableStateOf<ServerVideoProgress?>(null) }
    var loading by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var completed by remember { mutableStateOf(false) }

    LaunchedEffect(url, config) {
        if (config == null) {
            loading = false
            error = "Configure the Video Downloader server URL and API token in Settings > Services."
            return@LaunchedEffect
        }
        try {
            val mode = when {
                url.contains("tiktok.com", ignoreCase = true) -> "tiktok"
                url.contains("instagram.com", ignoreCase = true) ||
                    url.contains("facebook.com", ignoreCase = true) -> "social"
                else -> "web"
            }
            val result = ServerVideoDownloader.fetchInfo(config, url, mode)
            cards = result.videos
            if (cards.isEmpty()) error = result.error ?: "No downloadable videos were found."
        } catch (e: Exception) {
            error = e.message ?: "Could not connect to the download server."
        } finally {
            loading = false
        }
    }

    val selectedCard = cards.getOrNull(selectedCardIndex)
    val options = selectedCard?.options.orEmpty()

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = {
            Text(
                when {
                    completed -> "Download added"
                    working -> "Server download"
                    else -> "Server Download"
                }
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (loading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text("Checking link…")
                    }
                }

                if (!loading && cards.size > 1 && !working && !completed) {
                    Text("Choose video")
                    cards.forEachIndexed { index, card ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedCardIndex = index
                                    selectedOptionIndex = 0
                                }
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedCardIndex == index,
                                onClick = {
                                    selectedCardIndex = index
                                    selectedOptionIndex = 0
                                },
                            )
                            Text(card.title, modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }

                if (!loading && !working && !completed && selectedCard != null) {
                    Text(selectedCard.title)
                    if (!selectedCard.uploader.isNullOrBlank()) {
                        Text("By ${selectedCard.uploader}")
                    }
                    Text("Quality")
                    options.forEachIndexed { index, option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedOptionIndex = index }
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedOptionIndex == index,
                                onClick = { selectedOptionIndex = index },
                            )
                            Text(option.label, modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                    if (options.isEmpty()) {
                        Text("No quality options were returned by the server.")
                    }
                }

                if (working) {
                    Text(progress?.name ?: "Preparing download…")
                    val percent = progress?.percent?.toFloat()?.coerceIn(0f, 100f)
                    if (percent != null) {
                        LinearProgressIndicator(
                            progress = { percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("${percent.toInt()}%")
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    progress?.speed?.let { Text("Speed: $it") }
                    progress?.eta?.let { Text("Remaining: $it") }
                    progress?.note?.let { Text(it) }
                }

                if (completed) {
                    Text("The file is now being transferred to Nuvio Downloads.")
                }

                error?.let { Text(it) }
            }
        },
        confirmButton = {
            when {
                completed -> TextButton(onClick = onDismiss) { Text("Done") }
                working || loading -> TextButton(onClick = {}, enabled = false) {
                    Text("Please wait")
                }
                else -> TextButton(
                    enabled = selectedCard != null && options.isNotEmpty(),
                    onClick = {
                        val activeConfig = config ?: return@TextButton
                        val card = selectedCard ?: return@TextButton
                        val option = options.getOrNull(selectedOptionIndex) ?: return@TextButton
                        working = true
                        error = null
                        scope.launch {
                            try {
                                val jobId = ServerVideoDownloader.start(
                                    config = activeConfig,
                                    url = url,
                                    option = option,
                                    card = card,
                                )

                                while (true) {
                                    val latest = ServerVideoDownloader.progress(
                                        config = activeConfig,
                                        jobId = jobId,
                                    )
                                    progress = latest

                                    when (latest.status.lowercase()) {
                                        "done" -> {
                                            val result = DownloadsRepository.enqueueServerFile(
                                                contentType = contentType,
                                                videoId = videoId,
                                                parentMetaId = parentMetaId,
                                                parentMetaType = parentMetaType,
                                                title = card.title.ifBlank { title },
                                                logo = logo,
                                                poster = poster,
                                                background = background,
                                                seasonNumber = seasonNumber,
                                                episodeNumber = episodeNumber,
                                                episodeTitle = episodeTitle,
                                                episodeThumbnail = episodeThumbnail,
                                                sourceUrl = ServerVideoDownloader.fileUrl(
                                                    config = activeConfig,
                                                    jobId = jobId,
                                                ),
                                                token = activeConfig.token,
                                                qualityLabel = option.label,
                                                audio = option.q == "audio",
                                            )
                                            if (result != DownloadEnqueueResult.Started) {
                                                error("The server file could not be added to Nuvio Downloads.")
                                            }
                                            working = false
                                            completed = true
                                            break
                                        }
                                        "error" -> error(
                                            latest.error ?: latest.note ?: "The server download failed."
                                        )
                                    }

                                    delay(1000)
                                }
                            } catch (e: Exception) {
                                error = e.message ?: "The server download failed."
                                working = false
                            }
                        }
                    },
                ) {
                    Text("Start download")
                }
            }
        },
        dismissButton = {
            if (!working) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
