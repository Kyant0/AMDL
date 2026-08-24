package com.kyant.amdl.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64

data class AmApi(
    private val tokens: AmTokens,
    private val language: String
) {

    private val client = createAuthedClient()

    suspend fun getBytes(url: String): ByteArray? {
        return client.get(url).bodyOrNull()
    }

    suspend fun getText(url: String): String? {
        return client.get(url).bodyAsTextOrNull()
    }

    suspend fun getTracks(url: String): List<Track> {
        val url = runCatching { Url(url) }.getOrNull() ?: return emptyList()
        // "https://music.apple.com/cn/song/you/1559745848"
        // "https://music.apple.com/cn/album/too-fast-to-live-too-young-to-die-alts-ep/6797664550"
        if (url.host != "music.apple.com" && url.host != "beta.music.apple.com") {
            return emptyList()
        }

        val segments = url.segments
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
                val songId = url.parameters["i"]
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
        val url = "https://amp-api.music.apple.com/v1/catalog/${track.storefront}/songs/${track.id}"
        val json =
            client
                .get(url) {
                    parameter("extend", "ttmlLocalizations")
                    parameter("include", "lyrics,syllable-lyrics")
                    parameter("l", language)
                }
                .bodyOrNull<JsonObject>()
                ?.get("data")?.jsonArray?.firstOrNull()?.jsonObject
                ?: return null

        val attr = json["attributes"]?.jsonObject
        val relationships = json["relationships"]?.jsonObject
        return TrackMetadata(
            name = attr?.get("name")?.jsonPrimitive?.content,
            artistName = attr?.get("artistName")?.jsonPrimitive?.content,
            albumName = attr?.get("albumName")?.jsonPrimitive?.content,
            albumArtistName = attr?.get("albumArtistName")?.jsonPrimitive?.content,
            composerName = attr?.get("composerName")?.jsonPrimitive?.content,
            genreNames = attr?.get("genreNames")?.jsonArray?.mapNotNull { it.jsonPrimitive.content },
            releaseDate = attr?.get("releaseDate")?.jsonPrimitive?.content,
            isrc = attr?.get("isrc")?.jsonPrimitive?.content,
            lyrics = relationships?.get("lyrics")?.jsonObject?.get("data")?.jsonArray?.firstOrNull()?.jsonObject["attributes"]
                ?.jsonObject?.get("ttml")?.jsonPrimitive?.content,
            syllableLyrics = relationships?.get("syllable-lyrics")?.jsonObject?.get("data")?.jsonArray?.firstOrNull()?.jsonObject["attributes"]
                ?.jsonObject?.get("ttmlLocalizations")?.jsonPrimitive?.content
        )
    }

    suspend fun getSongAsset(id: String): JsonObject? {
        return client
            .post("https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/webPlayback") {
                header(HttpHeaders.ContentType, "application/json")
                setBody(buildJsonObject {
                    put("salableAdamId", id)
                    parameter("l", language)
                })
            }
            .bodyOrNull<JsonObject>()
            ?.get("songList")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("assets")?.jsonArray
            ?.find { it.jsonObject["flavor"]?.jsonPrimitive?.content == "28:ctrp256" }?.jsonObject
    }

    suspend fun getLicense(challenge: ByteArray, uri: String, adamId: String): ByteArray? {
        val response =
            client
                .post("https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/acquireWebPlaybackLicense") {
                    header(HttpHeaders.ContentType, "application/json")
                    setBody(
                        buildJsonObject {
                            put("challenge", Base64.encode(challenge))
                            put("key-system", "com.widevine.alpha")
                            put("uri", uri)
                            put("adamId", adamId)
                            put("user-initiated", true)
                        }
                    )
                }
                .bodyOrNull<JsonObject>()
                ?: return null
        val license = response["license"]?.jsonPrimitive?.content ?: return null
        return Base64.decode(license)
    }

    private fun createAuthedClient(): HttpClient {
        return HttpClient(Android) {
            defaultRequest {
                header(HttpHeaders.Origin, "https://music.apple.com")
                header(HttpHeaders.Referrer, "https://music.apple.com/")
                header(HttpHeaders.Accept, "application/json")
                header(HttpHeaders.Authorization, "Bearer ${tokens.devToken}")
                header(HttpHeaders.Cookie, "media-user-token=${tokens.mediaUserToken}")
            }

            install(ContentNegotiation) {
                json()
            }
        }
    }

    private suspend fun getTracksFromSong(storefront: String, id: String): List<Track> {
        val url = "https://amp-api.music.apple.com/v1/catalog/$storefront/songs/$id"
        val json =
            client
                .get(url) {
                    parameter("l", language)
                }
                .bodyOrNull<JsonObject>()
                ?.get("data")?.jsonArray?.firstOrNull()?.jsonObject
                ?: return emptyList()
        val attr = json["attributes"]?.jsonObject
        return listOf(
            Track(
                storefront = storefront,
                id = json["id"]?.jsonPrimitive?.content.orEmpty(),
                name = attr?.get("name")?.jsonPrimitive?.content.orEmpty(),
                artistName = attr?.get("artistName")?.jsonPrimitive?.content.orEmpty(),
                artwork = attr?.get("artwork")?.jsonObject?.let { resolveArtworkUrl(it) }?.let { Artwork(it) }
            )
        )
    }

    private suspend fun getTracksFromAlbum(storefront: String, id: String): List<Track> {
        val url = "https://amp-api.music.apple.com/v1/catalog/$storefront/albums/$id"
        val json =
            client
                .get(url) {
                    parameter("l", language)
                }
                .bodyOrNull<JsonObject>()
                ?.get("data")?.jsonArray?.firstOrNull()?.jsonObject
                ?: return emptyList()
        val artwork =
            json["attributes"]?.jsonObject?.get("artwork")?.jsonObject?.let { resolveArtworkUrl(it) }
                ?.let { CachedArtwork(it) }
        return json["relationships"]?.jsonObject?.get("tracks")?.jsonObject?.get("data")?.jsonArray?.mapNotNull { trackJson ->
            val attr = trackJson.jsonObject["attributes"]?.jsonObject
            Track(
                storefront = storefront,
                id = trackJson.jsonObject["id"]?.jsonPrimitive?.content.orEmpty(),
                name = attr?.get("name")?.jsonPrimitive?.content.orEmpty(),
                artistName = attr?.get("artistName")?.jsonPrimitive?.content.orEmpty(),
                artwork = artwork
            )
        }.orEmpty()
    }
}

private fun resolveArtworkUrl(artwork: JsonObject): String? {
    var url = artwork["url"]?.jsonPrimitive?.content ?: return null
    val width = artwork["width"]?.jsonPrimitive?.content
    val height = artwork["height"]?.jsonPrimitive?.content
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

private suspend inline fun <reified T> HttpResponse.bodyOrNull(): T? {
    return if (status.isSuccess()) {
        body()
    } else {
        null
    }
}

private suspend fun HttpResponse.bodyAsTextOrNull(): String? {
    return if (status.isSuccess()) {
        bodyAsText()
    } else {
        null
    }
}
