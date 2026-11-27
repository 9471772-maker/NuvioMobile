package com.nuvio.app.features.downloads

import androidx.compose.runtime.Composable

internal interface DownloadExportActions {
    fun shareFile(localFileUri: String): Boolean
    fun saveToPhotos(localFileUri: String, onResult: (Boolean) -> Unit)
}

@Composable
internal expect fun rememberDownloadExportActions(): DownloadExportActions
