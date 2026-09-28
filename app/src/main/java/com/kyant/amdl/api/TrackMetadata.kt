package com.kyant.amdl.api

import com.kyant.amdl.downloader.UserConfig
import com.kyant.amdl.engine.M4aIdent
import com.kyant.amdl.engine.M4aTagger

data class TrackMetadata(
    val name: String?,
    val artistName: String?,
    val albumName: String?,
    val albumArtistName: String?,
    val composerName: String?,
    val genreNames: List<String>?,
    val sortName: String?,
    val sortArtist: String?,
    val sortAlbum: String?,
    val sortAlbumArtist: String?,
    val sortComposer: String?,
    val releaseDate: String?,
    val discNumber: Int?,
    val discCount: Int?,
    val trackNumber: Int?,
    val trackCount: Int?,
    val isrc: String?,
    val copyright: String?,
    val albumReleaseDate: String?,
    val isSingle: Boolean?,
    val isCompilation: Boolean?,
    val lyrics: String?,
    val syllableLyrics: String?
) {

    fun setTag(tagger: M4aTagger) {
        name?.let { tagger.setStringTag(M4aIdent.Title, it) }
        artistName?.let { tagger.setStringTag(M4aIdent.Artist, it) }
        albumName?.let { tagger.setStringTag(M4aIdent.Album, it) }
        albumArtistName?.let { tagger.setStringTag(M4aIdent.AlbumArtist, it) }
        composerName?.let { tagger.setStringTag(M4aIdent.Composer, it) }
        genreNames?.joinToString()?.let { tagger.setStringTag(M4aIdent.Genre, it) }
        sortName?.let { tagger.setStringTag(M4aIdent.TitleSort, it) }
        sortAlbum?.let { tagger.setStringTag(M4aIdent.AlbumSort, it) }
        sortArtist?.let { tagger.setStringTag(M4aIdent.ArtistSort, it) }
        sortAlbumArtist?.let { tagger.setStringTag(M4aIdent.AlbumArtistSort, it) }
        sortComposer?.let { tagger.setStringTag(M4aIdent.ComposerSort, it) }
        releaseDate?.let { tagger.setStringTag(M4aIdent.Year, it) }
        if (discNumber != null || discCount != null) {
            tagger.setDisc(discNumber ?: 0, discCount ?: 0)
        }
        if (trackNumber != null || trackCount != null) {
            tagger.setTrack(trackNumber ?: 0, trackCount ?: 0)
        }
        isrc?.let { tagger.setIsrc(it) }
        copyright?.let { tagger.setStringTag(M4aIdent.Copyright, it) }
        isCompilation?.takeIf { it }?.let { tagger.setCompilation() }
        lyrics?.let { tagger.setStringTag(M4aIdent.Lyrics, ttmlToLrc(it)) }
    }

    fun getFileName(config: UserConfig): String {
        return if (config.saveByAlbum) {
            buildString {
                val year = albumReleaseDate?.substringBefore('-')?.sanitize()

                append(albumArtistName?.sanitize() ?: "Unknown Artist")
                append("/")

                if (!config.mergeSingles) {
                    if (year != null) {
                        append(year)
                        append(" - ")
                    }
                    append(albumName?.sanitize() ?: "Unknown Album")
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
                append(name?.sanitize() ?: "Unknown Title")
            }
        } else {
            "${artistName?.sanitize() ?: "Unknown Artist"} - ${name?.sanitize() ?: "Unknown Title"}"
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
