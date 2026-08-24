package com.kyant.amdl.downloader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kyant.amdl.api.Track

data class Task(
    val id: String,
    val track: Track,
    val config: UserConfig
) {

    var status by mutableStateOf(Status.Pending)
    var error by mutableStateOf<String?>(null)

    enum class Status {
        Pending,
        Preparing,
        Downloading,
        Processing,
        Completed,
        Failed
    }
}
