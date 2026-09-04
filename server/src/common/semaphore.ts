/**
 * 限制在飞任务数的信号量。
 *
 * 返回一个包装函数，超出上限的任务排队等待。用它把搜索里逐首解析播放地址
 * 从串行改成并发：总耗时取决于最慢的一批，而不是所有请求累加。
 */
export function createSemaphore(limit: number) {
  let active = 0;
  const queue: (() => void)[] = [];
  const release = () => {
    active--;
    queue.shift()?.();
  };
  return async <T>(task: () => Promise<T>): Promise<T> => {
    if (active >= limit) await new Promise<void>((resolve) => queue.push(resolve));
    active++;
    try {
      return await task();
    } finally {
      release();
    }
  };
}
