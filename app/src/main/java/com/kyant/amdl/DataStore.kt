package com.kyant.amdl

import android.content.Context
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.kyant.amdl.api.AmTokens
import com.kyant.amdl.downloader.UserConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class DataStore(context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val dataStore =
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope
        ) {
            context.preferencesDataStoreFile("settings")
        }

    private val devToken = Value(stringPreferencesKey("dev_token"), "")
    private val mediaUserToken = Value(stringPreferencesKey("media_user_token"), "")
    private val language = Value(stringPreferencesKey("language"), "zh-Hans-CN")
    private val downloadPath = Value(stringPreferencesKey("download_path"), "")
    private val saveByAlbum = Value(booleanPreferencesKey("save_by_album"), false)
    private val saveTtml = Value(booleanPreferencesKey("save_ttml"), true)

    val tokens: AmTokens by derivedStateOf {
        AmTokens(
            devToken = devToken.value,
            mediaUserToken = mediaUserToken.value
        )
    }

    val config: UserConfig by derivedStateOf {
        UserConfig(
            language = language.value,
            downloadPath = downloadPath.value,
            saveByAlbum = saveByAlbum.value,
            saveTtml = saveTtml.value
        )
    }

    fun setTokens(value: AmTokens) {
        devToken.value = value.devToken
        mediaUserToken.value = value.mediaUserToken
    }

    fun setLanguage(value: String) {
        language.value = value
    }

    fun setDownloadPath(value: String) {
        downloadPath.value = value
    }

    fun setSaveByAlbum(value: Boolean) {
        saveByAlbum.value = value
    }

    fun setSaveTtml(value: Boolean) {
        saveTtml.value = value
    }

    inner class Value<T>(
        private val key: Preferences.Key<T>,
        private val initialValue: T
    ) {

        private var state by mutableStateOf(
            runBlocking { dataStore.data.first()[key] ?: initialValue }
        )

        var value: T
            get() = state
            set(value) {
                scope.launch {
                    dataStore.edit { it[key] = value }
                    withContext(Dispatchers.Main) {
                        state = value
                    }
                }
            }
    }
}
