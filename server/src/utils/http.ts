import type { ServerResponse } from "node:http";
import type { IncomingMessage } from "node:http";

/** 输出统一 JSON 响应。 */
export function json(res: ServerResponse, status: number, value: unknown) {
  res.writeHead(status, { "content-type": "application/json; charset=utf-8", "access-control-allow-origin": "*" });
  res.end(JSON.stringify(value));
}

export function success<T>(data: T, meta?: unknown) { return { code: 0, message: "success", data, ...(meta ? { meta } : {}) }; }

export async function readJson(request: IncomingMessage) {
  let body = "";
  for await (const chunk of request) body += chunk;
  if (body.length > 16_384) throw new Error("请求体过大");
  return body ? JSON.parse(body) as Record<string, unknown> : {};
}
