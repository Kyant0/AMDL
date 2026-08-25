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

    var status: Status by mutableStateOf(Status.Pending)

    sealed interface Status {
        object Pending : Status
        object Preparing : Status
        data object Downloading : Status
        object Processing : Status
        object Completed : Status
        data class Failed(val error: String?) : Status
    }
}
