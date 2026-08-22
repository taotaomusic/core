import type { IncomingMessage, ServerResponse } from "node:http";
import { addFavorite, createUser, findUserByUsername, listFavorites, removeFavorite } from "../database.js";
import { authenticate, issueTokens, hashPassword, logout, passwordOf, refreshTokens, usernameOf, verifyPassword } from "../auth.js";
import { qualityOf, requestLyric, resolvePlayUrl, resolveSong, search } from "../services/song.js";
import { json, readJson, success } from "../utils/http.js";
import { allowAuthAttempt } from "../rate-limit.js";
import { handleApp } from "./app.js";
import { searchConcurrency } from "../config.js";

/** 处理移动端 API。 */
export async function handleApi(url: URL, request: IncomingMessage, response: ServerResponse) {
  const path = url.pathname.replace(/\/$/, "") || "/";
  if (path === "/api/v1/auth/register" && request.method === "POST") return register(request, response);
  if (path === "/api/v1/auth/login" && request.method === "POST") return login(request, response);
  if (path === "/api/v1/auth/refresh" && request.method === "POST") return refresh(request, response);
  if (path === "/api/v1/auth/logout" && request.method === "POST") return logoutUser(request, response);
  // 客户端引导与发布管理必须在访问令牌门禁之前：最需要强制更新的场景恰恰是
  // 上一个版本把登录搞坏了，若检查接口自己要求登录，这些客户端永远收不到升级通知。
  if (path.startsWith("/api/v1/app/")) return handleApp(url, request, response);
  if (!authenticate(request)) return json(response, 401, { code: 4010, message: "请先登录" });
  if (path === "/api/v1/auth/me" && request.method === "GET") {
    const user = authenticate(request);
    return user ? json(response, 200, success(user)) : json(response, 401, { code: 4010, message: "未登录" });
  }
  const favorite = url.pathname.match(/^\/api\/v1\/favorites\/([^/]+)\/([^/]+)$/);
  if (favorite && (request.method === "POST" || request.method === "DELETE")) return changeFavorite(request, response, favorite[1], favorite[2]);
  if (path === "/api/v1/favorites" && request.method === "GET") return getFavorites(request, response);
  if (path === "/api/v1/search") return searchSongsRoute(url, response);
  const play = url.pathname.match(/^\/api\/v1\/songs\/(\d+)\/play$/);
  if (play) {
    const type = url.searchParams.get("type");
    const target = await resolvePlayUrl(
      Number(play[1]),
      qualityOf(url.searchParams.get("quality")),
      type === null ? undefined : Number(type),
    );
    return streamMedia(response, target);
  }
  const lyric = url.pathname.match(/^\/api\/v1\/songs\/(\d+)\/lyrics$/);
  if (lyric) {
    const rich = await requestLyric(Number(lyric[1]));
    // 默认仍返回纯 LRC 文本：已安装的旧客户端把响应体直接当歌词展示，不能改成 JSON。
    // 新客户端显式带 format=json 才拿到逐字时间轴（yrc）和翻译。
    if (url.searchParams.get("format") === "json") return json(response, 200, success(rich));
    response.writeHead(200, { "content-type": "text/plain; charset=utf-8" });
    return response.end(rich.lrc || rich.yrc);
  }
  return json(response, 404, { code: 4040, message: "接口不存在" });
}

async function register(request: IncomingMessage, response: ServerResponse) {
  if (!allowAuthAttempt(`register:${request.socket.remoteAddress ?? "unknown"}`)) return json(response, 429, { code: 4290, message: "请求过于频繁，请稍后再试" });
  const body = await readJson(request); const username = usernameOf(body.username); const password = passwordOf(body.password);
  if (!/^[\w\u4e00-\u9fa5]{3,32}$/.test(username) || password.length < 6) return json(response, 400, { code: 4003, message: "用户名为3至32位，密码至少6位" });
  if (findUserByUsername(username)) return json(response, 409, { code: 4090, message: "用户名已存在" });
  const credentials = hashPassword(password); const user = createUser(username, credentials.hash, credentials.salt);
  return json(response, 201, success({ user, ...issueTokens(user) }));
}

async function login(request: IncomingMessage, response: ServerResponse) {
  if (!allowAuthAttempt(`login:${request.socket.remoteAddress ?? "unknown"}`)) return json(response, 429, { code: 4290, message: "请求过于频繁，请稍后再试" });
  const body = await readJson(request); const user = findUserByUsername(usernameOf(body.username));
  if (!user || !verifyPassword(passwordOf(body.password), user.password_salt, user.password_hash)) return json(response, 401, { code: 4011, message: "用户名或密码错误" });
  return json(response, 200, success({ user: { id: user.id, username: user.username, created_at: user.created_at }, ...issueTokens(user) }));
}

async function refresh(request: IncomingMessage, response: ServerResponse) {
  const body = await readJson(request); const tokens = refreshTokens(passwordOf(body.refreshToken));
  return tokens ? json(response, 200, success(tokens)) : json(response, 401, { code: 4012, message: "刷新令牌无效或已过期" });
}

async function logoutUser(request: IncomingMessage, response: ServerResponse) {
  const body = await readJson(request); const token = passwordOf(body.refreshToken); if (token) logout(token);
  return json(response, 204, undefined);
}

async function changeFavorite(request: IncomingMessage, response: ServerResponse, source: string, songId: string) {
  const user = authenticate(request); if (!user) return json(response, 401, { code: 4010, message: "未登录" });
  if (!/^[a-z0-9_-]{2,32}$/i.test(source) || !/^[\w-]{1,128}$/.test(songId)) return json(response, 400, { code: 4004, message: "渠道或歌曲 ID 不合法" });
  if (request.method === "POST") return json(response, 200, success(addFavorite(user.id, source.toLowerCase(), songId)));
  return json(response, 200, success({ removed: removeFavorite(user.id, source.toLowerCase(), songId) }));
}

async function getFavorites(request: IncomingMessage, response: ServerResponse) {
  const user = authenticate(request); if (!user) return json(response, 401, { code: 4010, message: "未登录" });
  return json(response, 200, success(listFavorites(user.id)));
}

/**
 * 搜索：先打一次上游拿列表，再并发补齐播放地址，以 NDJSON 流式返回。
 *
 * 原实现在 for 循环里逐首 await，20 首要串行 60 多次上游请求（实测约 11 秒）。
 * 这里所有解析任务一次性创建、由信号量限制在飞请求数，再按原始顺序依次 await 写出：
 * 顺序保持稳定，总耗时取决于最慢的一批而不是累加。
 */
async function searchSongsRoute(url: URL, response: ServerResponse) {
  const keyword = (url.searchParams.get("keyword") ?? "").trim();
  const page = Math.max(1, Number(url.searchParams.get("page") ?? 1));
  // 客户端历史参数名是 num，v3 上游叫 limit，这里做一层兼容。
  const limit = Math.min(60, Math.max(1, Number(url.searchParams.get("num") ?? url.searchParams.get("limit") ?? 10)));
  const quality = qualityOf(url.searchParams.get("quality"));
  if (!keyword) return json(response, 400, { code: 4001, message: "请输入搜索关键词" });

  const result = await search(keyword, page, limit);
  response.writeHead(200, {
    "content-type": "application/x-ndjson; charset=utf-8",
    "cache-control": "no-cache",
    "transfer-encoding": "chunked",
    "access-control-allow-origin": "*",
  });

  const acquire = createSemaphore(searchConcurrency);
  // 必须在创建 promise 时就挂上处理器，把失败折叠成 { ok: false }。
  // 若留到下面的循环里再 try/await，先失败的那个任务在轮到它之前就是一个
  // 未处理的 rejection，Node 15 起默认直接终止进程 —— 一首拿不到地址的歌就能弄挂服务。
  const pending = result.list.map((item) =>
    acquire(() => resolveSong(item, quality)).then(
      (song) => ({ ok: true as const, song }),
      (error: unknown) => ({ ok: false as const, error }),
    ),
  );
  let count = 0;
  let dropped = 0;
  for (const task of pending) {
    const outcome = await task;
    if (!outcome.ok) {
      // 拿不到可用播放地址的歌曲不返回，但要在 end 行里报出数量，界面才能提示。
      dropped++;
      continue;
    }
    response.write(`${JSON.stringify({ type: "song", data: outcome.song })}\n`);
    count++;
  }
  const meta = { page, limit, quality, count, dropped, total: result.total, hasMore: result.nextPage !== null };
  return response.end(`${JSON.stringify({ type: "end", meta })}\n`);
}

/** 限制在飞任务数的信号量。返回一个包装函数，超出上限的任务排队等待。 */
function createSemaphore(limit: number) {
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

async function streamMedia(response: ServerResponse, target: string) {
  const parsed = new URL(target); if (!allowedMediaHosts.has(parsed.hostname)) return json(response, 400, { code: 4002, message: "不允许转发此地址" });
  const upstream = await fetch(parsed); if (!upstream.ok || !upstream.body) return json(response, upstream.status, { code: 5021, message: "媒体资源获取失败" });
  response.writeHead(upstream.status, { "content-type": upstream.headers.get("content-type") ?? "application/octet-stream", "access-control-allow-origin": "*" });
  for await (const chunk of upstream.body as any) response.write(chunk); response.end();
}
import { allowedMediaHosts } from "../config.js";
