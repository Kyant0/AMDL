package com.kyant.amdl.downloader

data class UserConfig(
    val language: String,
    val downloadPath: String,
    val saveByAlbum: Boolean,
    val mergeSingles: Boolean,
    val saveTtml: Boolean
)
