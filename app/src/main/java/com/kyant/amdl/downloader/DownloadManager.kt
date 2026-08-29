package com.kyant.amdl.downloader

import com.kyant.amdl.api.AmApi
import com.kyant.amdl.api.AmTokens
import com.kyant.amdl.engine.Cdm
import com.kyant.amdl.engine.M4aTagger
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
import java.io.File
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
                task.status = Task.Status.Downloading

                val getMetadataJob = async { api.getTrackMetadata(track) }
                val downloadM4aJob = async { api.getM4a(track.id, cdm, atmos = task.config.preferAtmos) }
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

                val metadata = getMetadataJob.await()
                val m4aData = downloadM4aJob.await()
                val artwork = downloadArtworkJob?.await()

                task.status = Task.Status.Processing

                val fileName = metadata.getFileName(task.config)

                M4aTagger(m4aData).use { tagger ->
                    metadata.setTag(tagger)

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
                }

                if (metadata.syllableLyrics != null) {
                    try {
                        val ttmlFile = File(task.config.downloadPath, "$fileName.ttml")
                        ttmlFile.parentFile?.mkdirs()
                        ttmlFile.writeText(metadata.syllableLyrics)
                    } catch (e: Exception) {
                        throw IllegalArgumentException("Failed to save TTML file", e)
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
