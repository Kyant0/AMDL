package com.kyant.amdl.downloader

import com.kyant.amdl.api.AmApi
import com.kyant.amdl.api.AmTokens
import com.kyant.amdl.api.ttmlToLrc
import com.kyant.amdl.engine.Cdm
import com.kyant.amdl.engine.M4aIdent
import com.kyant.amdl.engine.M4aTagger
import com.kyant.amdl.engine.RustLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.outputStream
import kotlin.io.writeText
import kotlin.use
import kotlin.uuid.Uuid

class DownloadManager(
    initialTokens: AmTokens,
    initialLanguage: String
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val channel = Channel<Task>(Channel.UNLIMITED)

    private val api = MutableStateFlow(AmApi(initialTokens, initialLanguage))
    private val cdm = Cdm()

    val tasks: StateFlow<List<Task>>
        field = MutableStateFlow<List<Task>>(emptyList())
    val failedTasks: StateFlow<List<Task>>
        field = MutableStateFlow<List<Task>>(emptyList())

    init {
        scope.launch {
            repeat(8) {
                scope.launch {
                    for (task in channel) {
                        executeTask(task)
                    }
                }
            }
        }
    }

    fun updateTokens(tokens: AmTokens) {
        api.update { it.copy(tokens = tokens) }
    }

    fun updateLanguage(language: String) {
        api.update { it.copy(language = language) }
    }

    fun parseFromUrl(url: String, config: UserConfig) {
        val api = api.value
        scope.launch {
            val tracks =
                try {
                    api.getTracks(url)
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@launch
                }
            val tasks =
                tracks.map { track ->
                    Task(
                        id = Uuid.random().toString(),
                        track = track,
                        config = config
                    )
                }
            addTasks(tasks)
        }
    }

    fun retryAllFailedTasks() {
        val tasks = failedTasks.getAndUpdate { emptyList() }
        addTasks(tasks)
    }

    fun clearAllFailedTasks() {
        failedTasks.update { emptyList() }
    }

    fun retryTask(task: Task) {
        var taskToRetry: Task? = null
        failedTasks.update { tasks ->
            if (task in tasks) {
                taskToRetry = task
                tasks - task
            } else {
                tasks
            }
        }
        addTasks(listOfNotNull(taskToRetry))
    }

    private fun addTasks(tasks: List<Task>) {
        if (tasks.isEmpty()) return
        tasks.forEach { it.status = Task.Status.Pending }
        this.tasks.update { it + tasks }
        tasks.forEach { channel.trySend(it) }
    }

    private suspend fun executeTask(task: Task) {
        val api = api.value
        val track = task.track
        try {
            coroutineScope {
                task.status = Task.Status.Preparing

                val asset = api.getSongAsset(track.id)
                checkNotNull(asset) { "Failed to get song asset" }
                val assetUrl = asset.jsonObject["URL"]?.jsonPrimitive?.content
                checkNotNull(assetUrl) { "Failed to get song asset URL" }
                val m3u8 = api.getText(assetUrl)
                checkNotNull(m3u8) { "Failed to get m3u8" }

                task.status = Task.Status.Downloading

                val downloadEncryptedM4aJob = async {
                    val url =
                        m3u8.lineSequence()
                            .find { !it.startsWith('#') }
                            ?.let { assetUrl.substringBeforeLast('/') + '/' + it }
                    checkNotNull(url) { "Failed to get encrypted m4a URL" }
                    val data = api.getBytes(url)
                    checkNotNull(data) { "Failed to download encrypted m4a" }
                    data
                }
                val getContentKeyJob = async {
                    val uri =
                        m3u8.lineSequence()
                            .find { it.startsWith("#EXT-X-KEY:") }
                            ?.substringAfterLast("URI=\"")
                            ?.substringBeforeLast('\"')
                    checkNotNull(uri) { "Failed to get content key URI" }
                    val kid =
                        try {
                            Base64.decode(uri.substringAfter(','))
                        } catch (e: Exception) {
                            throw IllegalArgumentException("Failed to decode content key URI", e)
                        }
                    cdm.createLicenseRequest(kid).use { licenseRequest ->
                        val challenge = licenseRequest.getChallenge()
                        checkNotNull(challenge) { "Failed to get challenge" }
                        val licenseResponse = api.getLicense(challenge, uri, track.id)
                        checkNotNull(licenseResponse) { "Failed to get license response" }
                        val contentKey = licenseRequest.getContentKey(licenseResponse)
                        checkNotNull(contentKey) { "Failed to get content key" }
                        contentKey
                    }
                }
                val getMetadataJob = async {
                    api.getTrackMetadata(track)
                }
                val downloadArtworkJob =
                    if (track.artwork != null) {
                        async {
                            try {
                                track.artwork.getArtworkData(api)
                            } catch (e: Exception) {
                                throw IllegalArgumentException("Failed to download artwork", e)
                            }
                        }
                    } else {
                        null
                    }

                val encryptedM4a = downloadEncryptedM4aJob.await()
                val contentKey = getContentKeyJob.await()
                val metadata = getMetadataJob.await()
                val artwork = downloadArtworkJob?.await()

                task.status = Task.Status.Processing

                val decryptedM4a = RustLib.decryptM4a(contentKey, encryptedM4a)
                checkNotNull(decryptedM4a) { "Failed to decrypt m4a" }

                M4aTagger(decryptedM4a).use { tagger ->
                    metadata?.name?.let { tagger.setStringTag(M4aIdent.Title, it) }
                    metadata?.artistName?.let { tagger.setStringTag(M4aIdent.Artist, it) }
                    metadata?.albumName?.let { tagger.setStringTag(M4aIdent.Album, it) }
                    metadata?.albumArtistName?.let { tagger.setStringTag(M4aIdent.AlbumArtist, it) }
                    metadata?.composerName?.let { tagger.setStringTag(M4aIdent.Composer, it) }
                    metadata?.genreNames?.joinToString()?.let { tagger.setStringTag(M4aIdent.Genre, it) }
                    metadata?.releaseDate?.let { tagger.setStringTag(M4aIdent.Year, it) }
                    metadata?.lyrics?.let { tagger.setStringTag(M4aIdent.Lyrics, ttmlToLrc(it)) }
                    metadata?.isrc?.let { tagger.setIsrc(it) }
                    metadata?.isCompilation?.takeIf { it }?.let { tagger.setCompilation() }

                    val assetMetadata = asset.jsonObject["metadata"]?.jsonObject
                    val sortName = assetMetadata?.get("sort-name")?.jsonPrimitive?.content
                    val sortAlbum = assetMetadata?.get("sort-album")?.jsonPrimitive?.content
                    val sortArtist = assetMetadata?.get("sort-artist")?.jsonPrimitive?.content
                    val sortComposer = assetMetadata?.get("sort-composer")?.jsonPrimitive?.content
                    val discNumber = assetMetadata?.get("discNumber")?.jsonPrimitive?.intOrNull
                    val discCount = assetMetadata?.get("discCount")?.jsonPrimitive?.intOrNull
                    val trackCount = assetMetadata?.get("trackCount")?.jsonPrimitive?.intOrNull
                    val trackNumber = assetMetadata?.get("trackNumber")?.jsonPrimitive?.intOrNull
                    val copyright = assetMetadata?.get("copyright")?.jsonPrimitive?.content

                    sortName?.let { tagger.setStringTag(M4aIdent.TitleSort, it) }
                    sortAlbum?.let { tagger.setStringTag(M4aIdent.AlbumSort, it) }
                    sortArtist?.let { tagger.setStringTag(M4aIdent.ArtistSort, it) }
                    sortComposer?.let { tagger.setStringTag(M4aIdent.ComposerSort, it) }
                    if (discNumber != null || discCount != null) {
                        tagger.setDisc(discNumber ?: 0, discCount ?: 0)
                    }
                    if (trackNumber != null || trackCount != null) {
                        tagger.setTrack(trackNumber ?: 0, trackCount ?: 0)
                    }
                    copyright?.let { tagger.setStringTag(M4aIdent.Copyright, it) }

                    val fileName =
                        if (task.config.saveByAlbum) {
                            buildString {
                                val year = metadata?.albumReleaseDate?.substringBefore('-')?.sanitize()

                                append(metadata?.albumArtistName?.sanitize() ?: "Unknown Artist")
                                append("/")

                                if (!task.config.mergeSingles) {
                                    if (year != null) {
                                        append(year)
                                        append(" - ")
                                    }
                                    append(metadata?.albumName?.sanitize() ?: "Unknown Album")
                                    append("/")

                                    if (trackNumber != null && (trackCount == null || trackCount > 1)) {
                                        if (discNumber != null && (discCount == null || discCount > 1)) {
                                            append(discNumber.toString())
                                            append("-")
                                        }
                                        append(trackNumber.toString().padStart(2, '0'))
                                        append(" - ")
                                    }
                                } else {
                                    append("Singles/")

                                    if (year != null) {
                                        append(year)
                                        append(" - ")
                                    }
                                }
                                append(metadata?.name?.sanitize() ?: "Unknown Title")
                            }
                        } else {
                            "${metadata?.artistName?.sanitize() ?: "Unknown Artist"} - ${metadata?.name?.sanitize() ?: "Unknown Title"}"
                        }

                    if (artwork != null) {
                        tagger.setArtwork(artwork)
                    }
                    val taggedM4a = tagger.writeTag()
                    checkNotNull(taggedM4a) { "Failed to write m4a tag" }

                    try {
                        val m4aFile = File(task.config.downloadPath, "$fileName.m4a")
                        m4aFile.parentFile?.mkdirs()
                        m4aFile.outputStream().use {
                            it.write(taggedM4a)
                        }
                    } catch (e: Exception) {
                        throw IllegalArgumentException("Failed to save m4a file", e)
                    }

                    if (metadata?.syllableLyrics != null) {
                        try {
                            val ttmlFile = File(task.config.downloadPath, "$fileName.ttml")
                            ttmlFile.parentFile?.mkdirs()
                            ttmlFile.writeText(metadata.syllableLyrics)
                        } catch (e: Exception) {
                            throw IllegalArgumentException("Failed to save TTML file", e)
                        }
                    }
                }

                task.status = Task.Status.Completed
            }
        } catch (e: Exception) {
            e.printStackTrace()
            task.status = Task.Status.Failed(e.message)
        } finally {
            var taskToRemove: Task? = null
            tasks.update { tasks ->
                if (task in tasks) {
                    taskToRemove = task
                    tasks - task
                } else {
                    tasks
                }
            }
            if (taskToRemove != null && task.status is Task.Status.Failed) {
                failedTasks.update { it + taskToRemove }
            }
        }
    }
}

private fun String.sanitize(): String? {
    return if (this.isNotBlank()) {
        this.trim().replace(FileNameSanitizeRegex, "-")
    } else {
        null
    }
}

private val FileNameSanitizeRegex = Regex("[\\\\/:*?\"<>|]")
