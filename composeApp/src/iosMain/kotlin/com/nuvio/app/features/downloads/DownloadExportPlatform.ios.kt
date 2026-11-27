package com.nuvio.app.features.downloads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.uikit.LocalUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Photos.PHAccessLevelAddOnly
import platform.Photos.PHAssetChangeRequest
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHAuthorizationStatusNotDetermined
import platform.Photos.PHPhotoLibrary
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIViewController

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun rememberDownloadExportActions(): DownloadExportActions {
    val presenter = LocalUIViewController.current
    return remember(presenter) {
        IosDownloadExportActions(presenter)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosDownloadExportActions(
    private val presenter: UIViewController,
) : DownloadExportActions {

    override fun shareFile(localFileUri: String): Boolean {
        val url = existingLocalFileUrl(localFileUri) ?: return false
        val controller = UIActivityViewController(
            activityItems = listOf(url),
            applicationActivities = null,
        )
        presenter.presentViewController(controller, animated = true, completion = null)
        return true
    }

    override fun saveToPhotos(localFileUri: String, onResult: (Boolean) -> Unit) {
        val url = existingLocalFileUrl(localFileUri)
        if (url == null) {
            onResult(false)
            return
        }

        when (PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelAddOnly)) {
            PHAuthorizationStatusAuthorized,
            PHAuthorizationStatusLimited -> createPhotoAsset(url, onResult)

            PHAuthorizationStatusNotDetermined -> {
                PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelAddOnly) { status ->
                    if (status == PHAuthorizationStatusAuthorized ||
                        status == PHAuthorizationStatusLimited
                    ) {
                        createPhotoAsset(url, onResult)
                    } else {
                        onResult(false)
                    }
                }
            }

            else -> onResult(false)
        }
    }

    private fun createPhotoAsset(url: NSURL, onResult: (Boolean) -> Unit) {
        PHPhotoLibrary.sharedPhotoLibrary().performChanges(
            changeBlock = {
                PHAssetChangeRequest.creationRequestForAssetFromVideoAtFileURL(url)
            },
            completionHandler = { success, _ ->
                onResult(success)
            },
        )
    }

    private fun existingLocalFileUrl(value: String): NSURL? {
        val url = NSURL(string = value)
        val path = url.path ?: return null
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return null
        return url
    }
}
