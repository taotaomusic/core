import { useEffect, useRef, type ReactNode } from "react";

type RevealProps = {
  children: ReactNode;
  /** 追加在 .reveal 之后的类名，用于参与布局（网格、标题区等）。 */
  className?: string;
};

/**
 * 进场动画包装：元素进入视口后加 .is-visible，位移淡入交给 CSS 完成。
 * 用户系统开启「减少动态效果」时直接显示，不注册观察器。
 */
export function Reveal({ children, className }: RevealProps) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      el.classList.add("is-visible");
      return;
    }
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            entry.target.classList.add("is-visible");
            observer.unobserve(entry.target);
          }
        }
      },
      { threshold: 0.15 },
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  return <div ref={ref} className={className ? `reveal ${className}` : "reveal"}>{children}</div>;
}
