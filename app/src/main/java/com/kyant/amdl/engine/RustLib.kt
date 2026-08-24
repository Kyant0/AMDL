package com.kyant.amdl.engine

object RustLib {

    @JvmStatic
    external fun createCdm(): Long

    @JvmStatic
    external fun releaseCdm(ptr: Long)

    @JvmStatic
    external fun createCdmLicenseRequest(cdmPtr: Long, pssh: ByteArray): Long

    @JvmStatic
    external fun releaseCdmLicenseRequest(requestPtr: Long)

    @JvmStatic
    external fun getChallenge(requestPtr: Long): ByteArray?

    @JvmStatic
    external fun getContentKey(requestPtr: Long, license: ByteArray): ByteArray?

    @JvmStatic
    external fun decryptM4a(key: ByteArray, data: ByteArray): ByteArray?

    @JvmStatic
    external fun readM4aTag(data: ByteArray): Long

    @JvmStatic
    external fun releaseM4aTag(tagPtr: Long)

    @JvmStatic
    external fun setM4aStringTag(tagPtr: Long, ident: Int, value: String)

    @JvmStatic
    external fun setM4aArtwork(tagPtr: Long, data: ByteArray)

    @JvmStatic
    external fun setM4aDisc(tagPtr: Long, discNumber: Int, totalDiscs: Int)

    @JvmStatic
    external fun setM4aTrack(tagPtr: Long, trackNumber: Int, totalTracks: Int)

    @JvmStatic
    external fun setM4aCompilation(tagPtr: Long)

    @JvmStatic
    external fun setM4aIsrc(tagPtr: Long, isrc: String)

    @JvmStatic
    external fun writeM4aTag(tagPtr: Long, data: ByteArray): ByteArray?
}
