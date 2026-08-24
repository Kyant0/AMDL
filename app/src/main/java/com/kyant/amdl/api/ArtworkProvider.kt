package com.kyant.amdl.api

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface ArtworkProvider {

    suspend fun getArtworkData(api: AmApi): ByteArray?
}

data class Artwork(val url: String) : ArtworkProvider {

    override suspend fun getArtworkData(api: AmApi): ByteArray? {
        return api.getBytes(url)
    }
}

data class CachedArtwork(val url: String) : ArtworkProvider {

    private val mutex = Mutex()

    private var cachedData: ByteArray? = null

    override suspend fun getArtworkData(api: AmApi): ByteArray? {
        mutex.withLock {
            if (cachedData == null) {
                cachedData = api.getBytes(url)
            }
            return cachedData
        }
    }
}
