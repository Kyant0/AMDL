package com.kyant.amdl.api

data class TrackMetadata(
    val name: String?,
    val artistName: String?,
    val albumName: String?,
    val albumArtistName: String?,
    val composerName: String?,
    val genreNames: List<String>?,
    val releaseDate: String?,
    val albumReleaseDate: String?,
    val isrc: String?,
    val lyrics: String?,
    val syllableLyrics: String?
)
