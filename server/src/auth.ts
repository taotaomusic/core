import { createHash, createHmac, randomBytes, scryptSync, timingSafeEqual } from "node:crypto";
import type { IncomingMessage } from "node:http";
import { consumeRefreshToken, findUserById, findUserByUsername, revokeRefreshToken, saveRefreshToken, type UserRecord } from "./database.js";

const secret = process.env.AUTH_SECRET ?? "taotao-development-secret-change-me";
if (process.env.NODE_ENV === "production" && secret.length < 32) throw new Error("生产环境 AUTH_SECRET 至少需要 32 个字符");
const accessLifetimeSeconds = 15 * 60;
const refreshLifetimeSeconds = 60 * 60 * 24 * 30;

export function hashPassword(password: string, salt = randomBytes(16).toString("hex")) {
  return { salt, hash: scryptSync(password, salt, 64, { N: 131072, r: 8, p: 1 }).toString("hex") };
}

export function verifyPassword(password: string, salt: string, expected: string) {
  try { const actual = scryptSync(password, salt, 64, { N: 131072, r: 8, p: 1 }); const target = Buffer.from(expected, "hex"); return actual.length === target.length && timingSafeEqual(actual, target); } catch { return false; }
}

export function issueTokens(user: UserRecord) {
  const now = Math.floor(Date.now() / 1000); const payload = Buffer.from(JSON.stringify({ sub: user.id, iat: now, exp: now + accessLifetimeSeconds, typ: "access" })).toString("base64url");
  const accessToken = `${payload}.${sign(payload)}`; const refreshToken = randomBytes(48).toString("base64url");
  saveRefreshToken(user.id, hashToken(refreshToken), Date.now() + refreshLifetimeSeconds * 1000);
  return { accessToken, refreshToken, expiresIn: accessLifetimeSeconds };
}

export function refreshTokens(token: string) { const record = consumeRefreshToken(hashToken(token)); const user = record && findUserById(record.user_id); return user ? issueTokens(user) : undefined; }
export function logout(token: string) { revokeRefreshToken(hashToken(token)); }

export function authenticate(request: IncomingMessage) {
  const token = (request.headers.authorization ?? "").replace(/^Bearer\s+/i, ""); const [payload, signature] = token.split(".");
  if (!payload || !signature) return undefined; const expected = sign(payload);
  if (signature.length !== expected.length || !timingSafeEqual(Buffer.from(signature), Buffer.from(expected))) return undefined;
  try { const data = JSON.parse(Buffer.from(payload, "base64url").toString()) as { sub: number; exp: number; typ: string }; return data.typ === "access" && data.exp > Date.now() / 1000 ? findUserById(data.sub) : undefined; } catch { return undefined; }
}

function sign(payload: string) { return createHmac("sha256", secret).update(payload).digest("base64url"); }
function hashToken(token: string) { return createHash("sha256").update(token).digest("hex"); }
export function usernameOf(value: unknown) { return typeof value === "string" ? value.trim() : ""; }
export function passwordOf(value: unknown) { return typeof value === "string" ? value : ""; }
export { findUserByUsername };
