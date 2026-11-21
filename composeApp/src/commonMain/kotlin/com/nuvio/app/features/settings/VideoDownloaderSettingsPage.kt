package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.servers.ServerStorage
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.settings_video_downloader_api_token
import nuvio.composeapp.generated.resources.settings_video_downloader_api_token_description
import nuvio.composeapp.generated.resources.settings_video_downloader_section_title
import nuvio.composeapp.generated.resources.settings_video_downloader_server_url
import nuvio.composeapp.generated.resources.settings_video_downloader_server_url_description
import org.jetbrains.compose.resources.stringResource

private const val VIDEO_DOWNLOADER_URL_KEY = "video_downloader.url"
private const val VIDEO_DOWNLOADER_TOKEN_KEY = "video_downloader.token"

internal fun LazyListScope.videoDownloaderSettingsContent(isTablet: Boolean) {
    item {
        VideoDownloaderServerSettings(isTablet = isTablet)
    }
}

@Composable
private fun VideoDownloaderServerSettings(isTablet: Boolean) {
    var savedUrl by rememberSaveable {
        mutableStateOf(ServerStorage.read(VIDEO_DOWNLOADER_URL_KEY).orEmpty())
    }
    var savedToken by rememberSaveable {
        mutableStateOf(ServerStorage.read(VIDEO_DOWNLOADER_TOKEN_KEY).orEmpty())
    }

    var urlDraft by rememberSaveable { mutableStateOf(savedUrl) }
    var tokenDraft by rememberSaveable { mutableStateOf(savedToken) }

    val normalizedUrl = urlDraft.trim().trimEnd('/')
    val normalizedToken = tokenDraft.trim()

    SettingsSection(
        title = stringResource(Res.string.settings_video_downloader_section_title),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = if (isTablet) 20.dp else 16.dp,
                        vertical = if (isTablet) 16.dp else 14.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(Res.string.settings_video_downloader_server_url),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(Res.string.settings_video_downloader_server_url_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                OutlinedTextField(
                    value = urlDraft,
                    onValueChange = { urlDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = {
                        Text(stringResource(Res.string.settings_video_downloader_server_url))
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f),
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        disabledContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                )

                Button(
                    onClick = {
                        urlDraft = normalizedUrl
                        ServerStorage.write(
                            VIDEO_DOWNLOADER_URL_KEY,
                            normalizedUrl.ifBlank { null },
                        )
                        savedUrl = normalizedUrl
                    },
                    enabled = normalizedUrl != savedUrl,
                ) {
                    Text(stringResource(Res.string.action_save))
                }
            }

            SettingsGroupDivider(isTablet = isTablet)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = if (isTablet) 20.dp else 16.dp,
                        vertical = if (isTablet) 16.dp else 14.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(Res.string.settings_video_downloader_api_token),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(Res.string.settings_video_downloader_api_token_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SettingsSecretTextField(
                    value = tokenDraft,
                    onValueChange = { tokenDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(Res.string.settings_video_downloader_api_token),
                )

                Button(
                    onClick = {
                        tokenDraft = normalizedToken
                        ServerStorage.write(
                            VIDEO_DOWNLOADER_TOKEN_KEY,
                            normalizedToken.ifBlank { null },
                        )
                        savedToken = normalizedToken
                    },
                    enabled = normalizedToken != savedToken,
                ) {
                    Text(stringResource(Res.string.action_save))
                }
            }
        }
    }
}
