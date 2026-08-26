package com.kyant.amdl.api

import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.io.encoding.Base64

data class AmApi(
    private val tokens: AmTokens,
    private val language: String
) {

    suspend fun getBytes(url: String): ByteArray? {
        return httpGet(url) { readBytes() }
    }

    suspend fun getText(url: String): String? {
        return httpGet(url) { readBytes().decodeToString() }
    }

    suspend fun getTracks(url: String): List<Track> {
        val uri = url.toUri()

        if (uri.host != "music.apple.com" && uri.host != "beta.music.apple.com") {
            return emptyList()
        }

        val segments = uri.pathSegments
        if (segments.size < 2) {
            return emptyList()
        }

        val storefront = segments[0]
        if (storefront == "library") {
            return emptyList()
        }

        return when (segments[1]) {
            "song" -> {
                val songId = segments.last()
                getTracksFromSong(storefront, songId)
            }

            "album" -> {
                val songId = uri.getQueryParameter("i")
                if (songId != null) {
                    getTracksFromSong(storefront, songId)
                } else {
                    val albumId = segments.last()
                    getTracksFromAlbum(storefront, albumId)
                }
            }

            else -> emptyList()
        }
    }

    suspend fun getTrackMetadata(track: Track): TrackMetadata? {
        val url =
            "https://amp-api.music.apple.com/v1/catalog/${track.storefront}/songs/${track.id}?l=$language&include=albums,lyrics,syllable-lyrics&extend=ttmlLocalizations"
        val json =
            httpGet(url) { JsonObject(readBytes().decodeToString()) }
                ?.getArrayOrNull("data")?.getObjectOrNull(0)
                ?: return null

        val attr = json.getObjectOrNull("attributes")
        val relationships = json.getObjectOrNull("relationships")
        val albumAttr =
            relationships?.getObjectOrNull("albums")
                ?.getArrayOrNull("data")?.getObjectOrNull(0)
                ?.getObjectOrNull("attributes")
        return TrackMetadata(
            name = attr?.getStringOrNull("name"),
            artistName = attr?.getStringOrNull("artistName"),
            albumName = attr?.getStringOrNull("albumName"),
            albumArtistName = attr?.getStringOrNull("albumArtistName"),
            composerName = attr?.getStringOrNull("composerName"),
            genreNames = attr?.getArrayOrNull("genreNames")?.asSequence<String>()?.toList(),
            releaseDate = attr?.getStringOrNull("releaseDate"),
            albumReleaseDate = albumAttr?.getStringOrNull("releaseDate"),
            isSingle = albumAttr?.getBooleanOrNull("isSingle"),
            isCompilation = albumAttr?.getBooleanOrNull("isCompilation"),
            isrc = attr?.getStringOrNull("isrc"),
            lyrics =
                relationships?.getObjectOrNull("lyrics")
                    ?.getArrayOrNull("data")?.getObjectOrNull(0)
                    ?.getObjectOrNull("attributes")
                    ?.getStringOrNull("ttml"),
            syllableLyrics =
                relationships?.getObjectOrNull("syllable-lyrics")
                    ?.getArrayOrNull("data")?.getObjectOrNull(0)
                    ?.getObjectOrNull("attributes")
                    ?.getStringOrNull("ttmlLocalizations")
        )
    }

    suspend fun getSongAsset(id: String): JsonObject? {
        val url = "https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/webPlayback?l=$language"
        val body =
            buildJsonObject {
                put("salableAdamId", id)
            }.toString()
        return httpPost(url, body) { JsonObject(readBytes().decodeToString()) }
            ?.getArrayOrNull("songList")?.getObjectOrNull(0)
            ?.getArrayOrNull("assets")?.asSequence<JsonObject>()
            ?.find { it.getStringOrNull("flavor") == "28:ctrp256" }
    }

    suspend fun getLicense(challenge: ByteArray, uri: String, adamId: String): ByteArray? {
        val url = "https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/acquireWebPlaybackLicense"
        val body =
            buildJsonObject {
                put("challenge", Base64.encode(challenge))
                put("key-system", "com.widevine.alpha")
                put("uri", uri)
                put("adamId", adamId)
                put("user-initiated", true)
            }.toString()
        return httpPost(url, body) { JsonObject(readBytes().decodeToString()) }
            ?.getStringOrNull("license")
            ?.let { Base64.decode(it) }
    }

    private suspend fun getTracksFromSong(storefront: String, id: String): List<Track> {
        val url = "https://amp-api.music.apple.com/v1/catalog/$storefront/songs/$id?l=$language"
        val json =
            httpGet(url) { JsonObject(readBytes().decodeToString()) }
                ?.getArrayOrNull("data")?.getObjectOrNull(0)
                ?: return emptyList()
        val attr = json.getObjectOrNull("attributes")
        return listOf(
            Track(
                storefront = storefront,
                id = json.getStringOrNull("id") ?: return emptyList(),
                name = attr?.getStringOrNull("name").orEmpty(),
                artistName = attr?.getStringOrNull("artistName").orEmpty(),
                artwork =
                    attr?.getObjectOrNull("artwork")
                        ?.let { resolveArtworkUrl(it) }
                        ?.let { Artwork(it) }
            )
        )
    }

    private suspend fun getTracksFromAlbum(storefront: String, id: String): List<Track> {
        val url = "https://amp-api.music.apple.com/v1/catalog/$storefront/albums/$id?l=$language"
        val json =
            httpGet(url) { JsonObject(readBytes().decodeToString()) }
                ?.getArrayOrNull("data")?.getObjectOrNull(0)
                ?: return emptyList()
        val artwork =
            json.getObjectOrNull("attributes")
                ?.getObjectOrNull("artwork")
                ?.let { resolveArtworkUrl(it) }
                ?.let { CachedArtwork(it) }
        return json.getObjectOrNull("relationships")
            ?.getObjectOrNull("tracks")
            ?.getArrayOrNull("data")?.asSequence<JsonObject>()
            ?.mapNotNull { trackJson ->
                val attr = trackJson.getObjectOrNull("attributes") ?: return@mapNotNull null
                Track(
                    storefront = storefront,
                    id = trackJson.getStringOrNull("id") ?: return@mapNotNull null,
                    name = attr.getStringOrNull("name").orEmpty(),
                    artistName = attr.getStringOrNull("artistName").orEmpty(),
                    artwork = artwork
                )
            }
            ?.toList()
            .orEmpty()
    }

    private suspend inline fun <T> httpGet(
        url: String,
        crossinline block: InputStream.() -> T
    ): T? =
        withContext(Dispatchers.IO) {
            val url = URL(url)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                setRequestProperty("Origin", "https://music.apple.com")
                setRequestProperty("Referrer", "https://music.apple.com/")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer ${tokens.devToken}")
                setRequestProperty("Cookie", "media-user-token=${tokens.mediaUserToken}")
            }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    null
                } else {
                    connection.inputStream.block()
                }
            } finally {
                connection.disconnect()
            }
        }

    private suspend inline fun <T> httpPost(
        url: String,
        body: String,
        crossinline block: InputStream.() -> T
    ): T? =
        withContext(Dispatchers.IO) {
            val url = URL(url)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Origin", "https://music.apple.com")
                setRequestProperty("Referrer", "https://music.apple.com/")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer ${tokens.devToken}")
                setRequestProperty("Cookie", "media-user-token=${tokens.mediaUserToken}")
            }

            try {
                connection.outputStream.use {
                    it.write(body.toByteArray(Charsets.UTF_8))
                }
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    null
                } else {
                    connection.inputStream.block()
                }
            } finally {
                connection.disconnect()
            }
        }
}

private fun resolveArtworkUrl(artwork: JsonObject): String? {
    var url = artwork.getStringOrNull("url") ?: return null
    val width = artwork.getStringOrNull("width")
    val height = artwork.getStringOrNull("height")
    url = url
        .replace("{c}", "bb")
        .replace("{f}", "png")
    if (width != null && height != null) {
        url = url
            .replace("{w}", width)
            .replace("{h}", height)
    }
    return url
}
