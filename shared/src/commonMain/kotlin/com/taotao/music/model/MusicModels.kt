package com.taotao.music.model

data class Song(
    val title: String,
    val artist: String,
    val duration: String,
    val color: Long,
    val audioUri: String? = null,
    val isEncrypted: Boolean = false,
    val sourceExtension: String? = null,
    val remoteId: Long? = null,
    val coverUri: String? = null,
    val lyricUri: String? = null,
    val album: String = "",
    val subtitle: String = "",
    val releaseTime: String = "",
)

object DemoMusic {
    val songs = listOf(
        Song("晚风告白", "桃桃", "03:42", 0xFFFFB4A2),
        Song("小小的浪漫", "陈粒", "04:16", 0xFFFFD6A5),
        Song("向云端", "小霞 / 海洋Bo", "04:02", 0xFFB8C0FF),
        Song("若梦", "周深", "04:31", 0xFFBDE0FE),
        Song("夏日风铃", "桃桃精选", "03:28", 0xFFCDEAC0)
    )
}
