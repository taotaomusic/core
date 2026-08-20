/** 移动端统一使用的歌曲模型。 */
export type Song = {
  id: number; title: string; artist: string; album: string; subtitle: string; time: string; duration: string;
  audioUrl?: string; coverUrl?: string; lyricUrl?: string;
};
