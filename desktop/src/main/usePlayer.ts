import { useRef, useState } from "react";
import { resolveLink, SessionExpired, readableError, type Song } from "../api";

/**
 * 播放队列与播放控制。队列即当前搜索结果；点歌把整列设为队列并从该曲开始，
 * 播完自动下一首。会话过期时通过 onExpired 上抛给上层登出。
 */
export function usePlayer(onExpired: () => void) {
  const audioRef = useRef<HTMLAudioElement>(null);
  const [queue, setQueue] = useState<Song[]>([]);
  const [index, setIndex] = useState(-1);
  const [error, setError] = useState("");
  const current = index >= 0 && index < queue.length ? queue[index] : null;

  async function playAt(i: number, list: Song[] = queue) {
    if (i < 0 || i >= list.length) return;
    setIndex(i);
    setError("");
    try {
      const url = await resolveLink(list[i]);
      const el = audioRef.current;
      if (el) {
        el.src = url;
        await el.play().catch(() => {});
      }
    } catch (e) {
      if (e instanceof SessionExpired) return onExpired();
      setError(readableError(e));
    }
  }

  function playFrom(list: Song[], i: number) {
    setQueue(list);
    void playAt(i, list);
  }

  return {
    audioRef,
    queue,
    index,
    current,
    error,
    playFrom,
    next: () => playAt(index + 1),
    prev: () => playAt(index - 1),
    hasNext: index >= 0 && index < queue.length - 1,
    hasPrev: index > 0,
  };
}
