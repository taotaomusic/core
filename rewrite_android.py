import sys

path = r"F:\music\androidApp\src\main\java\com\taotao\music\ui\TaotaoMusicApp.kt"
with open(path, "r", encoding="utf-8") as f:
    content = f.read()

start_str = "Scaffold("
end_str = ") { innerPadding ->"

start_idx = content.find(start_str)
end_idx = content.find(end_str) + len(end_str)

new_block = '''com.taotao.music.playerui.layout.SharedMainLayout(
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
                    val reduceMotion = LocalReduceMotion.current
                    AnimatedVisibility(
                        visible = !showPlayerDetail && playbackSongs.isNotEmpty(),
                        enter = riseIn(reduceMotion),
                        exit = if (showPlayerDetail) ExitTransition.None else sinkOut(reduceMotion),
                    ) {
                        val current = remember(playbackSongs, selectedIndex) {
                            playbackSongs.getOrNull(selectedIndex.coerceIn(0, (playbackSongs.size - 1).coerceAtLeast(0)))
                        }
                        if (current != null) {
                            com.taotao.music.playerui.components.SharedMiniPlayer(
                                state = com.taotao.music.playerui.PlayerUiState(song = current, isPlaying = isPlaying),
                                actions = com.taotao.music.playerui.PlayerActions(
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
            ) { innerPadding ->
                Box(Modifier.fillMaxSize()) {
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = innerPadding.calculateBottomPadding())
                    ) { snackbarData ->
                        Snackbar(
                            snackbarData = snackbarData,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            actionColor = MaterialTheme.colorScheme.primary,
                        )
                    }
'''

# Replace
new_content = content[:start_idx] + new_block + content[end_idx:]

# Find the closing brace of Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
# and inject another closing brace for our Box
last_brace = new_content.rfind("}")
last_brace2 = new_content.rfind("}", 0, last_brace)
last_brace3 = new_content.rfind("}", 0, last_brace2)

new_content = new_content[:last_brace3] + "}\n" + new_content[last_brace3:]

with open(path, "w", encoding="utf-8") as f:
    f.write(new_content)
