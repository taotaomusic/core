import type { IncomingMessage, ServerResponse } from "node:http";
import { createUser, findUserByUsername } from "../database.js";
import { authenticate, createToken, hashPassword, passwordOf, usernameOf, verifyPassword } from "../auth.js";
import { requestJson } from "../upstream/tencent.js";
import { qualityOf, requestLyric, resolveSong } from "../services/song.js";
import { json, readJson, success } from "../utils/http.js";

/** 处理移动端 API。 */
export async function handleApi(url: URL, request: IncomingMessage, response: ServerResponse) {
  if (url.pathname === "/api/v1/auth/register" && request.method === "POST") return register(request, response);
  if (url.pathname === "/api/v1/auth/login" && request.method === "POST") return login(request, response);
  if (url.pathname === "/api/v1/auth/me" && request.method === "GET") {
    const user = authenticate(request);
    return user ? json(response, 200, success(user)) : json(response, 401, { code: 4010, message: "未登录" });
  }
  if (url.pathname === "/api/v1/search") {
    const keyword = (url.searchParams.get("keyword") ?? "").trim(); const page = Math.max(1, Number(url.searchParams.get("page") ?? 1));
    const num = Math.min(60, Math.max(1, Number(url.searchParams.get("num") ?? 10))); const quality = qualityOf(url.searchParams.get("quality"));
    if (!keyword) return json(response, 400, { code: 4001, message: "请输入搜索关键词" });
    const result = await requestJson(`?word=${encodeURIComponent(keyword)}&page=${page}&num=${num}&quality=${quality}`);
    response.writeHead(200, { "content-type": "application/x-ndjson; charset=utf-8", "cache-control": "no-cache", "transfer-encoding": "chunked", "access-control-allow-origin": "*" });
    let count = 0; for (const item of result.data ?? []) try { response.write(`${JSON.stringify({ type: "song", data: await resolveSong(item, quality) })}\n`); count++; } catch { /* 丢弃无效歌曲 */ }
    return response.end(`${JSON.stringify({ type: "end", meta: { page, num, quality, count, hasMore: (result.data ?? []).length === num } })}\n`);
  }
  const play = url.pathname.match(/^\/api\/v1\/songs\/(\d+)\/play$/); if (play) return streamMedia(response, (await resolveSong({ id: Number(play[1]) }, qualityOf(url.searchParams.get("quality")))).audioUrl!);
  const lyric = url.pathname.match(/^\/api\/v1\/songs\/(\d+)\/lyrics$/); if (lyric) { response.writeHead(200, { "content-type": "text/plain; charset=utf-8" }); return response.end(await requestLyric(Number(lyric[1]))); }
  return json(response, 404, { code: 4040, message: "接口不存在" });
}

async function register(request: IncomingMessage, response: ServerResponse) {
  const body = await readJson(request); const username = usernameOf(body.username); const password = passwordOf(body.password);
  if (!/^[\w\u4e00-\u9fa5]{3,32}$/.test(username) || password.length < 6) return json(response, 400, { code: 4003, message: "用户名为3至32位，密码至少6位" });
  if (findUserByUsername(username)) return json(response, 409, { code: 4090, message: "用户名已存在" });
  const credentials = hashPassword(password); const user = createUser(username, credentials.hash, credentials.salt);
  return json(response, 201, success({ user, token: createToken(user) }));
}

async function login(request: IncomingMessage, response: ServerResponse) {
  const body = await readJson(request); const user = findUserByUsername(usernameOf(body.username));
  if (!user || !verifyPassword(passwordOf(body.password), user.password_salt, user.password_hash)) return json(response, 401, { code: 4011, message: "用户名或密码错误" });
  return json(response, 200, success({ user: { id: user.id, username: user.username, created_at: user.created_at }, token: createToken(user) }));
}

async function streamMedia(response: ServerResponse, target: string) {
  const parsed = new URL(target); if (!allowedMediaHosts.has(parsed.hostname)) return json(response, 400, { code: 4002, message: "不允许转发此地址" });
  const upstream = await fetch(parsed); if (!upstream.ok || !upstream.body) return json(response, upstream.status, { code: 5021, message: "媒体资源获取失败" });
  response.writeHead(upstream.status, { "content-type": upstream.headers.get("content-type") ?? "application/octet-stream", "access-control-allow-origin": "*" });
  for await (const chunk of upstream.body as any) response.write(chunk); response.end();
}
import { allowedMediaHosts } from "../config.js";
