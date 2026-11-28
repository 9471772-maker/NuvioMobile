package com.nuvio.app.features.addons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioSurfaceCard

@Composable
internal fun AddonDirectorySection(
    installedUrls: Set<String>,
    onInstall: (AddonDirectoryEntry) -> Unit,
) {
    var searchText by rememberSaveable { mutableStateOf("") }
    var appliedSearch by rememberSaveable { mutableStateOf("") }
    var reloadToken by remember { mutableStateOf(0) }
    var entries by remember { mutableStateOf(emptyList<AddonDirectoryEntry>()) }
    var recommendations by remember { mutableStateOf(emptyList<AddonDirectoryEntry>()) }
    var totalAddons by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(appliedSearch, reloadToken) {
        loading = true
        error = null
        entries = emptyList()
        totalAddons = 0

        try {
            recommendations = AddonDirectoryRepository.recommended().filter {
                it.name.contains(appliedSearch, ignoreCase = true) ||
                    it.description.contains(appliedSearch, ignoreCase = true)
            }
            val combined = linkedMapOf<String, AddonDirectoryEntry>()
            var requestedPage = 1
            var hasMore = true

            while (hasMore) {
                val result = AddonDirectoryRepository.fetchPage(
                    page = requestedPage,
                    search = appliedSearch,
                )

                totalAddons = result.total
                result.entries.forEach { entry ->
                    combined[entry.manifestUrl] = entry
                }

                entries = combined.values.toList()
                hasMore = result.hasNextPage
                requestedPage++

                if (requestedPage > 1000 && hasMore) {
                    throw IllegalStateException(
                        "Addon directory exceeded the 1000-page safety limit."
                    )
                }
            }
        } catch (exception: Exception) {
            error = exception.message ?: "Unable to load the addon directory."
        } finally {
            loading = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Browse Stremio Addons",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Search the community catalogue and install individual addons directly into Nuvio.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val categories = (recommendations + entries).flatMap { it.categories }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
        var expanded by remember { mutableStateOf(false) }
        androidx.compose.foundation.layout.Box {
            androidx.compose.material3.TextButton(onClick = { expanded = true }) {
                Text(category ?: "All categories")
            }
            androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                (listOf<String?>(null) + categories).forEach { option ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(option ?: "All categories") },
                        onClick = { category = option; expanded = false },
                    )
                }
            }
        }
        val matches = remember(entries, recommendations, category) {
            (recommendations + entries)
                .filter { entry -> category == null || entry.categories.any { it.equals(category, ignoreCase = true) } }
                .distinctBy { it.manifestUrl }
                .sortedWith(compareBy<AddonDirectoryEntry>(
                    { it.name.dropWhile { char -> !char.isLetterOrDigit() }.lowercase() },
                    { it.name.lowercase() },
                    { it.manifestUrl },
                ))
        }

        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            placeholder = { Text("Search addons...") },
        )
        NuvioPrimaryButton(
            text = "Search directory",
            enabled = !loading,
            onClick = {
                appliedSearch = searchText.trim()
                reloadToken++
            },
        )

        Text(
            text = "Available catalogue addons: $totalAddons",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Matching addons: ${matches.size} • A–Z",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (loading && entries.isEmpty()) {
            Text(
                text = "Loading addons...",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            NuvioPrimaryButton(
                text = "Retry",
                enabled = !loading,
                onClick = {
                    reloadToken++
                },
            )
        }

        if (!loading && matches.isEmpty() && error == null) {
            Text(
                text = "No addons found. Try another search.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        matches.forEach { entry ->
            AddonDirectoryEntryCard(
                entry = entry,
                recommended = recommendations.any { it.manifestUrl == entry.manifestUrl },
                installed = entry.manifestUrl in installedUrls,
                onInstall = { onInstall(entry) },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Directory provided by ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Stremio Addons",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier,
            )
        }
        NuvioPrimaryButton(
            text = "Visit Stremio Addons",
            onClick = {
                uriHandler.openUri("https://stremio-addons.net")
            },
        )
    }
}

@Composable
private fun AddonDirectoryEntryCard(
    entry: AddonDirectoryEntry,
    recommended: Boolean,
    installed: Boolean,
    onInstall: () -> Unit,
) {
    NuvioSurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (!entry.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = entry.logoUrl,
                    contentDescription = entry.name,
                    modifier = Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop,
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (recommended) Text("Recommended", style = MaterialTheme.typography.labelSmall)
                if (entry.isAlternativeInstance) {
                    Text(
                        text = "Alternative server instance",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (entry.isNsfw) {
                    Text(
                        text = "18+ Adult content",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (entry.stars > 0) {
                    Text(
                        text = "★ ${entry.stars}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (entry.description.isNotBlank()) {
            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (entry.categories.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                entry.categories.forEach { category ->
                    Text(
                        text = category,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = entry.manifestUrl,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        NuvioPrimaryButton(
            text = if (installed) "Installed" else "Install addon",
            enabled = !installed,
            onClick = onInstall,
        )
    }
}
