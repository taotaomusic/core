/**
 * Radix UI 适配层：把无头组件包一层本项目的珊瑚色视觉规格，
 * 让弹窗 / 下拉菜单 / 滑杆 / 复选框等交互原语统一来自组件库，
 * 而视觉继续走既有的 CSS 变量体系（--surface / --coral / --border …）。
 *
 * 原则：
 * - 只包样式与受控行为，不引入新视觉语言、不换主题；
 * - Dialog/AlertDialog 遮罩与卡片沿用原 apd-* / pd-modal-* 同款视觉；
 * - DropdownMenu 的弹出层挂在 body（Portal），z-index 高于普通浮层；
 * - Slider 的轨道/填充/滑块复用 pd-track 同款配色（复选滑杆除外）。
 */
import * as AlertDialogPrimitive from "@radix-ui/react-alert-dialog";
import * as CheckboxPrimitive from "@radix-ui/react-checkbox";
import * as DialogPrimitive from "@radix-ui/react-dialog";
import * as DropdownMenuPrimitive from "@radix-ui/react-dropdown-menu";
import * as SliderPrimitive from "@radix-ui/react-slider";
import type { ComponentPropsWithoutRef, ReactNode } from "react";

// ---- 非模态确认弹窗（Dialog）：用于分享 / 音质 / 定时关闭等可点击遮罩关闭的场景 ----

/** 弹窗根：受控 open + onOpenChange（点遮罩 / Esc 关闭都走 onOpenChange(false)）。 */
export const Dialog = DialogPrimitive.Root;
export const DialogPortal = DialogPrimitive.Portal;
/** 遮罩：与原 pd-modal-mask / apd-mask 同款半透明毛玻璃，点击事件仍由 Content 的 outside 检测承接。 */
export const DialogOverlay = (props: ComponentPropsWithoutRef<typeof DialogPrimitive.Overlay>) => (
  <DialogPrimitive.Overlay className="apd-mask" {...props} />
);
/** 弹窗卡片：与原 apd-card 同款圆角/阴影（ui-dialog-card），Radix 自动处理 Esc、焦点圈定与滚动锁定。 */
export const DialogContent = (props: ComponentPropsWithoutRef<typeof DialogPrimitive.Content>) => (
  <DialogPrimitive.Content className="ui-dialog-card" {...props} />
);
/**
 * 无障碍标题：默认视觉隐藏（视觉标题仍由业务侧文本承担）；
 * 传入 className 可改为直接渲染可见标题（如 pd-modal-title）。
 * 缺少 Title 会触发 Radix 的无障碍警告，这里由适配层兜底。
 */
export const DialogTitle = (props: ComponentPropsWithoutRef<typeof DialogPrimitive.Title>) => (
  <DialogPrimitive.Title className="ui-sr-only" {...props} />
);
/** 无障碍描述：与 Title 同理，默认隐藏，可经 className 改为可见。 */
export const DialogDescription = (props: ComponentPropsWithoutRef<typeof DialogPrimitive.Description>) => (
  <DialogPrimitive.Description className="ui-sr-only" {...props} />
);

// ---- 模态确认弹窗（AlertDialog）：用于删除歌单等破坏性操作，Esc / 点遮罩不关闭 ----

export const AlertDialog = AlertDialogPrimitive.Root;
export const AlertDialogPortal = AlertDialogPrimitive.Portal;
export const AlertDialogOverlay = (props: ComponentPropsWithoutRef<typeof AlertDialogPrimitive.Overlay>) => (
  <AlertDialogPrimitive.Overlay className="apd-mask" {...props} />
);
export const AlertDialogContent = (props: ComponentPropsWithoutRef<typeof AlertDialogPrimitive.Content>) => (
  <AlertDialogPrimitive.Content className="ui-dialog-card" {...props} />
);
export const AlertDialogTitle = (props: ComponentPropsWithoutRef<typeof AlertDialogPrimitive.Title>) => (
  <AlertDialogPrimitive.Title className="apd-title" {...props} />
);
export const AlertDialogDescription = (
  props: ComponentPropsWithoutRef<typeof AlertDialogPrimitive.Description>,
) => <AlertDialogPrimitive.Description className="apd-sub" {...props} />;
export const AlertDialogCancel = AlertDialogPrimitive.Cancel;
export const AlertDialogAction = AlertDialogPrimitive.Action;

// ---- 下拉菜单（DropdownMenu）：替代 SongRowMenu 的手写行内浮层 ----

export const DropdownMenu = DropdownMenuPrimitive.Root;
export const DropdownMenuTrigger = DropdownMenuPrimitive.Trigger;
export const DropdownMenuContent = (props: ComponentPropsWithoutRef<typeof DropdownMenuPrimitive.Content>) => (
  <DropdownMenuPrimitive.Portal>
    {/* sideOffset 对齐原 srmenu-pop 与「⋯」按钮的间距；Portal 挂 body 避免被歌曲行的 overflow 裁剪 */}
    <DropdownMenuPrimitive.Content className="ui-menu" sideOffset={4} align="end" {...props} />
  </DropdownMenuPrimitive.Portal>
);
export const DropdownMenuItem = (props: ComponentPropsWithoutRef<typeof DropdownMenuPrimitive.Item>) => (
  <DropdownMenuPrimitive.Item className="ui-menu-item" {...props} />
);

// ---- 滑杆（Slider）：音量滑杆与播放进度条共用的交互原语 ----

export const Slider = SliderPrimitive.Root;
export const SliderTrack = SliderPrimitive.Track;
export const SliderRange = SliderPrimitive.Range;
export const SliderThumb = SliderPrimitive.Thumb;

// ---- 复选框（Checkbox）：定时关闭「播完再停」与搜索历史开关 ----

export function Checkbox({
  children,
  ...props
}: ComponentPropsWithoutRef<typeof CheckboxPrimitive.Root> & { children?: ReactNode }) {
  return (
    <CheckboxPrimitive.Root className="ui-checkbox" {...props}>
      {/* 勾选态渲染珊瑚色对勾，视觉对齐原 accent-color 方案 */}
      <CheckboxPrimitive.Indicator>
        <svg width="11" height="11" viewBox="0 0 12 12" fill="none" aria-hidden>
          <path
            d="M2 6.2 4.8 9 10 3.2"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      </CheckboxPrimitive.Indicator>
      {children}
    </CheckboxPrimitive.Root>
  );
}
