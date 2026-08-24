package com.kyant.amdl.engine

class CdmLicenseRequest(private val ptr: Long) : AutoCloseable {

    fun getChallenge(): ByteArray? {
        return RustLib.getChallenge(ptr)
    }

    fun getContentKey(license: ByteArray): ByteArray? {
        return RustLib.getContentKey(ptr, license)
    }

    override fun close() {
        RustLib.releaseCdmLicenseRequest(ptr)
    }
}
