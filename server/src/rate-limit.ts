const attempts = new Map<string, { count: number; resetAt: number }>();

export function allowAuthAttempt(key: string) {
  const now = Date.now(); const current = attempts.get(key);
  if (!current || current.resetAt <= now) { attempts.set(key, { count: 1, resetAt: now + 15 * 60_000 }); return true; }
  if (current.count >= 10) return false;
  current.count++; return true;
}
