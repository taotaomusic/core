package com.taotao.music.model

data class Song(
    val title: String,
    val artist: String,
    val duration: String,
    val color: Long,
    val audioUri: String? = null,
    val remoteId: Long? = null,
    val coverUri: String? = null,
    val lyricUri: String? = null,
    val album: String = "",
    val subtitle: String = "",
    val releaseTime: String = "",
)
