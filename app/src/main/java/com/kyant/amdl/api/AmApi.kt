package com.kyant.amdl.api

import androidx.core.net.toUri
import com.kyant.amdl.engine.Cdm
import com.kyant.amdl.engine.RustLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.io.encoding.Base64
import kotlin.io.readBytes
import kotlin.use

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

    suspend fun getTrackMetadata(track: Track): TrackMetadata {
        val songData =
            httpGet("https://amp-api.music.apple.com/v1/catalog/${track.storefront}/songs/${track.id}?l=$language&include=albums,lyrics,syllable-lyrics&extend=ttmlLocalizations") {
                JsonObject(readBytes().decodeToString())
            }
                ?.getArrayOrNull("data")?.getObjectOrNull(0)

        val attr = songData?.getObjectOrNull("attributes")
        val relationships = songData?.getObjectOrNull("relationships")
        val albumAttr =
            relationships?.getObjectOrNull("albums")
                ?.getArrayOrNull("data")?.getObjectOrNull(0)
                ?.getObjectOrNull("attributes")

        val webMetadata =
            httpPost(
                "https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/webPlayback?l=$language",
                buildJsonObject {
                    put("salableAdamId", track.id)
                }.toString()
            ) { JsonObject(readBytes().decodeToString()) }
                ?.getArrayOrNull("songList")?.getObjectOrNull(0)
                ?.getArrayOrNull("assets")?.asSequence<JsonObject>()
                ?.firstOrNull()
                ?.getObjectOrNull("metadata")

        return TrackMetadata(
            name = attr?.getStringOrNull("name"),
            artistName = attr?.getStringOrNull("artistName"),
            albumName = attr?.getStringOrNull("albumName"),
            albumArtistName = attr?.getStringOrNull("albumArtistName"),
            composerName = attr?.getStringOrNull("composerName"),
            genreNames = attr?.getArrayOrNull("genreNames")?.asSequence<String>()?.toList(),
            sortName = webMetadata?.getStringOrNull("sort-name"),
            sortAlbum = webMetadata?.getStringOrNull("sort-album"),
            sortArtist = webMetadata?.getStringOrNull("sort-artist"),
            sortAlbumArtist = null,
            sortComposer = webMetadata?.getStringOrNull("sort-composer"),
            releaseDate = attr?.getStringOrNull("releaseDate"),
            discNumber = webMetadata?.getIntOrNull("discNumber"),
            discCount = webMetadata?.getIntOrNull("discCount"),
            trackNumber = webMetadata?.getIntOrNull("trackNumber"),
            trackCount = webMetadata?.getIntOrNull("trackCount"),
            isrc = attr?.getStringOrNull("isrc"),
            copyright = webMetadata?.getStringOrNull("copyright"),
            albumReleaseDate = albumAttr?.getStringOrNull("releaseDate"),
            isSingle = albumAttr?.getBooleanOrNull("isSingle"),
            isCompilation = albumAttr?.getBooleanOrNull("isCompilation"),
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

    suspend fun getM4a(id: String, cdm: Cdm, atmos: Boolean): ByteArray {
        val assetUrl =
            httpGet("https://amp-api.music.apple.com/v1/play/assets?id=$id&kind=song&includeLicenseUrls=true&hlsEncryption=CBC&hlsProfile=enhancedHls") {
                JsonObject(readBytes().decodeToString())
            }
                ?.getObjectOrNull("results")
                ?.getArrayOrNull("assets")?.getObjectOrNull(0)
                ?.getStringOrNull("url")
                ?.replace("(P\\d+)_([^/]+)(\\.m3u8)".toRegex(), "$1_default$3")
        checkNotNull(assetUrl) { "Failed to get asset URL" }
        val assetM3u8 = getText(assetUrl)
        checkNotNull(assetM3u8) { "Failed to download asset M3U8" }
        val streamHrefLineIndex = run {
            var index = -1
            if (atmos) {
                index = assetM3u8.lineSequence().indexOfFirst { it.contains("AUDIO=\"audio-atmos-") }
            }
            if (index == -1) {
                index = assetM3u8.lineSequence().indexOfFirst { it.endsWith("AUDIO=\"audio-stereo-256\"") }
            }
            if (index == -1) {
                index = assetM3u8.lineSequence().indexOfFirst { it.contains("AUDIO=\"") }
            }
            check(index != -1) { "Failed to find audio stream in asset M3U8" }
            index + 1
        }
        val streamHref = assetM3u8.lineSequence().elementAtOrNull(streamHrefLineIndex)
        checkNotNull(streamHref) { "Failed to find audio stream href in asset M3U8" }
        val m3u8Url = assetUrl.substringBeforeLast('/') + '/' + streamHref
        val m3u8 = getText(m3u8Url)
        checkNotNull(m3u8) { "Failed to download M3U8" }
        val contentUri =
            m3u8.lineSequence()
                .firstOrNull {
                    it.contains("KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"") &&
                            !it.contains("URI=\"data:text/plain;base64,AAAAOHBzc2gAAAAA7e+LqXnWSs6jyCfc1R0h7QAAABgSEAAAAAAAAAAAczEvZTEgICBI88aJmwY=\"")
                }
                ?.substringAfter("URI=\"")
                ?.substringBefore("\"")
        checkNotNull(contentUri) { "Failed to find content key URI" }
        val pssh =
            try {
                Base64.decode(contentUri.substringAfter(','))
            } catch (e: Exception) {
                throw IllegalArgumentException("Failed to decode content key URI", e)
            }
        val contentKey =
            cdm.createLicenseRequest(pssh).use { licenseRequest ->
                val challenge = licenseRequest.getChallenge()
                checkNotNull(challenge) { "Failed to get challenge" }
                val licenseResponse = getLicense(challenge, contentUri, id)
                checkNotNull(licenseResponse) { "Failed to get license response" }
                val contentKey = licenseRequest.getContentKey(licenseResponse)
                checkNotNull(contentKey) { "Failed to get content key" }
                contentKey
            }
        val songHref =
            m3u8.lineSequence()
                .firstOrNull { it.startsWith("#EXT-X-MAP:URI=\"") }
                ?.substringAfter("#EXT-X-MAP:URI=\"")
                ?.substringBefore("\"")
        checkNotNull(songHref) { "Failed to find song href in M3U8" }
        val songUrl = assetUrl.substringBeforeLast('/') + '/' + songHref
        val encryptedData = getBytes(songUrl)
        checkNotNull(encryptedData) { "Failed to download M4A" }
        val decryptedData = RustLib.decryptM4a(contentKey, encryptedData)
        checkNotNull(decryptedData) { "Failed to decrypt M4A" }
        return decryptedData
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

    private suspend fun getLicense(challenge: ByteArray, uri: String, adamId: String): ByteArray? {
        return httpPost(
            "https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/acquireWebPlaybackLicense",
            buildJsonObject {
                put("challenge", Base64.encode(challenge))
                put("key-system", "com.widevine.alpha")
                put("uri", uri)
                put("adamId", adamId)
                put("user-initiated", true)
            }.toString()
        ) { JsonObject(readBytes().decodeToString()) }
            ?.getStringOrNull("license")
            ?.let { Base64.decode(it) }
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
