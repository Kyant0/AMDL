package com.kyant.amdl.engine

class M4aFile(private val data: ByteArray) : AutoCloseable {

    private val tagPtr: Long = RustLib.readM4aTag(data)

    fun setStringTag(ident: M4aIdent, value: String) {
        RustLib.setM4aStringTag(tagPtr, ident.value, value)
    }

    fun setArtwork(value: ByteArray) {
        RustLib.setM4aArtwork(tagPtr, value)
    }

    fun setDisc(discNumber: Int, totalDiscs: Int) {
        RustLib.setM4aDisc(tagPtr, discNumber, totalDiscs)
    }

    fun setTrack(trackNumber: Int, totalTracks: Int) {
        RustLib.setM4aTrack(tagPtr, trackNumber, totalTracks)
    }

    fun setCompilation() {
        RustLib.setM4aCompilation(tagPtr)
    }

    fun setIsrc(isrc: String) {
        RustLib.setM4aIsrc(tagPtr, isrc)
    }

    fun writeTag(): ByteArray? {
        return RustLib.writeM4aTag(tagPtr, data)
    }

    override fun close() {
        RustLib.releaseM4aTag(tagPtr)
    }
}
