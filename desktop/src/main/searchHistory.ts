// 搜索历史存储：只保存在本机 localStorage，不上传服务端（对标安卓端 SearchPage）。

const HISTORY_KEY = "taotao.searchHistory";
const PAUSED_KEY = "taotao.searchHistoryPaused";
const MAX_HISTORY = 20;

function safeGet(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function safeSet(key: string, value: string): void {
  try {
    localStorage.setItem(key, value);
  } catch {
    // 存储不可用（隐私模式等）时静默忽略，功能退化为内存态
  }
}

/** 清洗词列表：trim → 滤空 → 忽略大小写去重 → 截断到上限。 */
function sanitize(list: unknown[]): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const item of list) {
    if (typeof item !== "string") continue;
    const word = item.trim();
    if (!word) continue;
    const k = word.toLowerCase();
    if (seen.has(k)) continue;
    seen.add(k);
    out.push(word);
    if (out.length >= MAX_HISTORY) break;
  }
  return out;
}

function save(list: string[]): string[] {
  safeSet(HISTORY_KEY, JSON.stringify(list));
  return list;
}

/** 读取搜索历史；解析失败返回空数组。 */
export function loadHistory(): string[] {
  const raw = safeGet(HISTORY_KEY);
  if (!raw) return [];
  try {
    const parsed: unknown = JSON.parse(raw);
    return Array.isArray(parsed) ? sanitize(parsed) : [];
  } catch {
    return [];
  }
}

/** 新增一条历史：trim、忽略大小写判重、新词置顶、上限 20，返回新数组。 */
export function addHistory(kw: string): string[] {
  const word = kw.trim();
  if (!word) return loadHistory();
  const rest = loadHistory().filter((w) => w.toLowerCase() !== word.toLowerCase());
  return save([word, ...rest].slice(0, MAX_HISTORY));
}

/** 删除一条历史（忽略大小写匹配），返回新数组。 */
export function removeHistory(kw: string): string[] {
  const word = kw.trim().toLowerCase();
  return save(loadHistory().filter((w) => w.toLowerCase() !== word));
}

/** 清空全部搜索历史。 */
export function clearHistory(): void {
  try {
    localStorage.removeItem(HISTORY_KEY);
  } catch {
    // 存储不可用时静默忽略
  }
}

/** 整表替换（trim→滤空→忽略大小写去重→take(20)），可用于拖动排序等批量提交。 */
export function replaceHistory(list: string[]): string[] {
  return save(sanitize(list));
}

/** 是否暂停记录搜索历史。 */
export function isPaused(): boolean {
  return safeGet(PAUSED_KEY) === "1";
}

/** 设置暂停记录开关（暂停时旧历史保留可点，但不再新增）。 */
export function setPaused(b: boolean): void {
  safeSet(PAUSED_KEY, b ? "1" : "0");
}
