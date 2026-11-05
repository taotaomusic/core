import type { Song } from "../api";
import { useApp } from "../state/AppState";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "../ui/radix";

/**
 * 歌曲行「⋯」更多菜单：组件库（Radix DropdownMenu）实现的行内浮层，含「下一首播放」
 * 「加入歌单」以及可选的「查看歌手」「查看专辑」（仅当调用方提供对应回调且歌曲携带
 * 对应 id 才出现）。组件自身不持有打开状态：open / onOpenChange 由调用方
 * （SongList / PlaylistsPage）用「当前打开的行 key」单值 state 控制，
 * 保证同一时刻只有一行开菜单；点浮层外部或按 Esc 收起由 Radix 原生处理。
 */
export function SongRowMenu({
  song,
  open,
  onToggle,
  onClose,
  onOpenAddToPlaylist,
  onOpenArtist,
  onOpenAlbum,
}: {
  song: Song;
  /** 本行菜单是否展开 */
  open: boolean;
  /** 点击「⋯」切换展开 / 收起 */
  onToggle: () => void;
  /** 点外部 / Esc 时收起（调用方把打开的行 key 置空） */
  onClose: () => void;
  /** 点击「加入歌单」：交给调用方打开加入歌单弹窗 */
  onOpenAddToPlaylist: (song: Song) => void;
  /** 点击「查看歌手」：不提供或歌曲没有 artistId 时该项不出现 */
  onOpenArtist?: (song: Song) => void;
  /** 点击「查看专辑」：不提供或歌曲没有 albumId 时该项不出现 */
  onOpenAlbum?: (song: Song) => void;
}) {
  const { playNext } = useApp();

  return (
    <span
      className="srmenu-wrap"
      // 行内元素整体挡住歌曲行的点击冒泡，行点击播放不应被「⋯」误触
      onClick={(e) => e.stopPropagation()}
    >
      <DropdownMenu open={open} onOpenChange={(next) => (next ? onToggle() : onClose())}>
        {/* asChild：触发器保留原 srmenu-trigger 按钮外观，无障碍属性由 Radix 注入。
            开合完全交给 Radix 的 Trigger（点击会经 onOpenChange 回调），这里不再手动
            toggle，否则同一次点击会被切换两次导致菜单打不开；onClick 只挡行点击冒泡 */}
        <DropdownMenuTrigger asChild>
          <button
            type="button"
            className={`srmenu-trigger${open ? " on" : ""}`}
            title="更多操作"
            onClick={(e) => e.stopPropagation()}
          >
            ⋯
          </button>
        </DropdownMenuTrigger>
        <DropdownMenuContent
          onCloseAutoFocus={(e) => e.preventDefault()}
          onEscapeKeyDown={(e) => {
            // Esc 已由 Radix 走 onOpenChange(false)，这里只阻止焦点回到触发器
            e.preventDefault();
          }}
        >
          <DropdownMenuItem
            onSelect={() => {
              // playNext 内部已自带 toast（「已添加到下一首播放」），这里只调用并收起菜单，避免重复提示
              playNext(song);
              onClose();
            }}
          >
            下一首播放
          </DropdownMenuItem>
          <DropdownMenuItem
            onSelect={() => {
              onClose();
              onOpenAddToPlaylist(song);
            }}
          >
            加入歌单
          </DropdownMenuItem>
          {/* 跳转详情两项：回调与歌曲关联 id 都齐备才渲染，缺一不可见 */}
          {onOpenArtist && song.artistId != null && (
            <DropdownMenuItem
              onSelect={() => {
                onClose();
                onOpenArtist(song);
              }}
            >
              查看歌手
            </DropdownMenuItem>
          )}
          {onOpenAlbum && song.albumId != null && (
            <DropdownMenuItem
              onSelect={() => {
                onClose();
                onOpenAlbum(song);
              }}
            >
              查看专辑
            </DropdownMenuItem>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
    </span>
  );
}
