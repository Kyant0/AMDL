package com.kyant.amdl.scene

import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.system.Os
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kyant.amdl.DataStore
import com.kyant.amdl.api.AmTokens
import com.kyant.amdl.downloader.DownloadManager
import com.kyant.amdl.downloader.UserConfig

class AppState(private val context: Context) {

    private val dataStore = DataStore(context)

    val downloadManager =
        DownloadManager(
            initialTokens = dataStore.tokens,
            initialLanguage = dataStore.config.language
        )

    val isLoggedIn: Boolean
        get() {
            val tokens = dataStore.tokens
            return tokens.devToken.isNotEmpty() && tokens.mediaUserToken.isNotEmpty()
        }

    var isStoragePermissionGranted: Boolean by mutableStateOf(Environment.isExternalStorageManager())

    val isDownloadPathSet: Boolean
        get() = dataStore.config.downloadPath.isNotEmpty()

    val isSetupComplete: Boolean
        get() = isStoragePermissionGranted && isLoggedIn && isDownloadPathSet

    val config: UserConfig
        get() = dataStore.config

    val navBackStack = mutableStateListOf(if (isSetupComplete) Scene.Main else Scene.SetupWizard)

    fun grantStoragePermission() {
        val intent =
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                "package:${context.packageName}".toUri()
            )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    @Composable
    fun ObserveStoragePermission() {
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer =
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        isStoragePermissionGranted = Environment.isExternalStorageManager()
                    }
                }
            lifecycleOwner.lifecycle.addObserver(observer)

            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
    }

    fun setTokens(value: AmTokens) {
        dataStore.setTokens(value)
        downloadManager.updateTokens(value)
    }

    fun setLanguage(value: String) {
        dataStore.setLanguage(value)
        downloadManager.updateLanguage(value)
    }

    fun setSaveByAlbum(value: Boolean) {
        dataStore.setSaveByAlbum(value)
    }

    fun setSaveTtml(value: Boolean) {
        dataStore.setSaveTtml(value)
    }

    @Composable
    fun downloadPathPicker(): ManagedActivityResultLauncher<Uri?, Uri?> {
        return rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                val path = getRealPathFromDocumentTreeUri(context.contentResolver, uri)
                if (path != null) {
                    dataStore.setDownloadPath(path)
                }
            }
        }
    }

    fun parseFromClipboard() {
        val clipboardText = getClipboardText(context)
        if (clipboardText != null) {
            downloadManager.parseFromUrl(clipboardText, config)
        }
    }
}

private fun getRealPathFromDocumentTreeUri(contentResolver: ContentResolver, uri: Uri): String? {
    try {
        val documentUri =
            DocumentsContract.buildDocumentUriUsingTree(
                uri,
                DocumentsContract.getTreeDocumentId(uri)
            )
        val tempFileUri =
            DocumentsContract.createDocument(
                contentResolver,
                documentUri,
                "application/octet-stream",
                ".bomb"
            )
        if (tempFileUri != null) {
            try {
                contentResolver.openFileDescriptor(tempFileUri, "r")?.use { fd ->
                    return Os.readlink("/proc/self/fd/${fd.fd}")
                        .let {
                            if (it.startsWith("/mnt/user/0/")) {
                                it.replaceFirst("/mnt/user/0/", "/storage/")
                            } else {
                                it
                            }
                        }
                        .substringBeforeLast('/')
                }
            } catch (_: Exception) {
            } finally {
                try {
                    DocumentsContract.deleteDocument(contentResolver, tempFileUri)
                } catch (_: Exception) {
                }
            }
        }
    } catch (_: Exception) {
    }
    return null
}

private fun getClipboardText(context: Context): String? {
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return clipboardManager.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()
}
