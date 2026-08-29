package com.kyant.amdl.engine

class Cdm : AutoCloseable {

    private val ptr: Long = RustLib.createCdm()

    fun createLicenseRequest(pssh: ByteArray): CdmLicenseRequest {
        val requestPtr = RustLib.createCdmLicenseRequest(ptr, pssh)
        return CdmLicenseRequest(requestPtr)
    }

    override fun close() {
        RustLib.releaseCdm(ptr)
    }
}
