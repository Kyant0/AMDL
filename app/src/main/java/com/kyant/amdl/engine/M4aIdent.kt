package com.kyant.amdl.engine

@JvmInline
value class M4aIdent(val value: Int) {

    companion object {

        val Title = M4aIdent(fourcc('©', 'n', 'a', 'm'))
        val Artist = M4aIdent(fourcc('©', 'A', 'R', 'T'))
        val Album = M4aIdent(fourcc('©', 'a', 'l', 'b'))
        val AlbumArtist = M4aIdent(fourcc('a', 'A', 'R', 'T'))
        val Composer = M4aIdent(fourcc('©', 'w', 'r', 't'))
        val Genre = M4aIdent(fourcc('©', 'g', 'e', 'n'))
        val Year = M4aIdent(fourcc('©', 'd', 'a', 'y'))
        val Copyright = M4aIdent(fourcc('c', 'p', 'r', 't'))
        val Lyrics = M4aIdent(fourcc('©', 'l', 'y', 'r'))
        val AlbumArtistSort = M4aIdent(fourcc('s', 'o', 'a', 'a'))
        val AlbumSort = M4aIdent(fourcc('s', 'o', 'a', 'l'))
        val ArtistSort = M4aIdent(fourcc('s', 'o', 'a', 'r'))
        val ComposerSort = M4aIdent(fourcc('s', 'o', 'c', 'o'))
        val TitleSort = M4aIdent(fourcc('s', 'o', 'n', 'm'))

        private fun fourcc(a: Char, b: Char, c: Char, d: Char): Int {
            return (a.code shl 24) or (b.code shl 16) or (c.code shl 8) or d.code
        }
    }
}
