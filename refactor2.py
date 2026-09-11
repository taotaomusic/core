import os
import re

# 替换 Android 的 TaotaoMusicApp.kt，引入 SharedMainLayout
android_app_path = r"F:\music\androidApp\src\main\java\com\taotao\music\ui\TaotaoMusicApp.kt"
with open(android_app_path, "r", encoding="utf-8") as f:
    content = f.read()

# 替换现有的 NavigationBar 为 SharedMainLayout 的结构调用
# 这里采用正则或者大规模替换，由于代码极其复杂，我将使用高级替换确保不破坏逻辑
# 先插入导入
if "import com.taotao.music.playerui.layout.SharedMainLayout" not in content:
    content = content.replace("import com.taotao.music.playerui.PlayerActions", "import com.taotao.music.playerui.layout.SharedMainLayout\nimport com.taotao.music.playerui.PlayerActions")

# 寻找现有的 Scaffold 并替换底部
pattern_scaffold = re.compile(r'Scaffold\([\s\S]*?bottomBar = \{[\s\S]*?NavigationBar\([\s\S]*?\}\s*\}\s*\},?', re.MULTILINE)

# 我们直接写一个简单的替换方案，将其转为 SharedMainLayout
replacement = '''SharedMainLayout(
        bottomTab = bottomTab,
        onTabSelected = { target ->
            bottomTab = target
            showPlayerDetail = false
            showSearchPage = false
            showSettingsPage = false
            showProfilePage = false
            mineLibrarySection = null
            selectedPlaylist = null
            showPlaylistPicker = false
            playlistPickerSong = null
            showPlaylistSongPicker = false
            playlistSongPickerTarget = null
            playlistEditorMode = null
            pendingPlaylistSongAfterCreate = null
        },
        miniPlayerContent = {
            AnimatedVisibility(
                visible = !showPlayerDetail && playbackSongs.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                val current = remember(playbackSongs, selectedIndex) {
                    playbackSongs.getOrNull(selectedIndex.coerceIn(0, (playbackSongs.size - 1).coerceAtLeast(0)))
                }
                if (current != null) {
                    com.taotao.music.playerui.components.SharedMiniPlayer(
                        state = PlayerUiState(song = current, isPlaying = isPlaying),
                        actions = PlayerActions(
                            onTogglePlaying = { togglePlayback() },
                            onPrevious = { playAdjacentSong(-1) },
                            onNext = { playAdjacentSong(1) },
                            onSeek = {},
                            onToggleRepeat = {}
                        ),
                        onClick = { showPlayerDetail = true },
                        artworkContent = {
                            AlbumArt(Color(current.color), 48.dp, 24.sp, current.coverUri)
                        }
                    )
                }
            }
        }
    )'''

# 因为正则表达式容易出大问题，安全起见我们直接重写 Android 主入口的 Scaffold 闭包
# 这里只为了快速实现，我将在下一个 shell 脚本里进行精确的 AST 级别或手动的字符串切片替换
