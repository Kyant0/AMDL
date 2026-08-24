package com.kyant.amdl.engine

class Cdm : AutoCloseable {

    private val ptr: Long = RustLib.createCdm()

    fun createLicenseRequest(kid: ByteArray): CdmLicenseRequest {
        val pssh = "1210".hexToByteArray() + kid + "0801".hexToByteArray()
        val requestPtr = RustLib.createCdmLicenseRequest(ptr, pssh)
        return CdmLicenseRequest(requestPtr)
    }

    override fun close() {
        RustLib.releaseCdm(ptr)
    }
}
