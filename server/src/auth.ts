import { createHmac, randomBytes, scryptSync, timingSafeEqual } from "node:crypto";
import type { IncomingMessage } from "node:http";
import { findUserById, findUserByUsername, type UserRecord } from "./database.js";

const secret = process.env.AUTH_SECRET ?? "taotao-change-this-secret";
const tokenLifetimeSeconds = 60 * 60 * 24 * 30;

export function hashPassword(password: string, salt = randomBytes(16).toString("hex")) {
  return { salt, hash: scryptSync(password, salt, 64).toString("hex") };
}

export function verifyPassword(password: string, salt: string, expected: string) {
  const actual = scryptSync(password, salt, 64);
  const target = Buffer.from(expected, "hex");
  return actual.length === target.length && timingSafeEqual(actual, target);
}

export function createToken(user: UserRecord) {
  const payload = Buffer.from(JSON.stringify({ sub: user.id, exp: Math.floor(Date.now() / 1000) + tokenLifetimeSeconds })).toString("base64url");
  return `${payload}.${sign(payload)}`;
}

export function authenticate(request: IncomingMessage) {
  const header = request.headers.authorization ?? "";
  const token = header.startsWith("Bearer ") ? header.slice(7) : "";
  const [payload, signature] = token.split(".");
  const expectedSignature = payload ? sign(payload) : "";
  if (!payload || !signature || signature.length !== expectedSignature.length || !timingSafeEqual(Buffer.from(signature), Buffer.from(expectedSignature))) return undefined;
  try {
    const data = JSON.parse(Buffer.from(payload, "base64url").toString()) as { sub: number; exp: number };
    return data.exp > Math.floor(Date.now() / 1000) ? findUserById(data.sub) : undefined;
  } catch { return undefined; }
}

function sign(payload: string) { return createHmac("sha256", secret).update(payload).digest("base64url"); }

export function usernameOf(value: unknown) { return typeof value === "string" ? value.trim() : ""; }
export function passwordOf(value: unknown) { return typeof value === "string" ? value : ""; }
export { findUserByUsername };
