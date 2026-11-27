package com.nuvio.app.features.downloads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
internal actual fun rememberDownloadExportActions(): DownloadExportActions =
    remember {
        object : DownloadExportActions {
            override fun shareFile(localFileUri: String): Boolean = false
            override fun saveToPhotos(localFileUri: String, onResult: (Boolean) -> Unit) {
                onResult(false)
            }
        }
    }
