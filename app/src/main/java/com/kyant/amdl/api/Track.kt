package com.kyant.amdl.api

data class Track(
    val storefront: String,
    val id: String,
    val name: String,
    val artistName: String,
    val artwork: ArtworkProvider?
)
