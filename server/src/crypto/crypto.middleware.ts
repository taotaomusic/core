import { Logger } from "@nestjs/common";
import type { NextFunction, Request, Response } from "express";
import { CryptoTransportService } from "./crypto-transport.service";

/** 携带加密头且路径受保护时，把解出的明文挂到这里供后续 body parser 读取。 */
const CRYPTO_HEADER = "x-taotao-crypto";

/**
 * 传输加密的请求/响应中间件（`plans/009`）。
 *
 * 必须挂在 `main.ts` 的 body parser **之前**：要先拿原始字节 `open()` 出明文，
 * 再让下游 JSON 解析器和路由看到与明文请求一字不差的内容；响应侧 `seal()` 回帧。
 *
 * **对未加密请求完全透明**：没有 `X-Taotao-Crypto` 头、链路未启用、或路径在排除列表里，
 * 一律 `next()` 放行，不改动任何字节——保证既有契约（`/search` 裸 NDJSON、
 * 401 不变 403 等）不因加密层变形。
 *
 * @param isExcludedPath 判定路径是否绕开加密（静态资源、音频流、大文件上传）。
 */
export function createCryptoMiddleware(
    transport: CryptoTransportService,
    isExcludedPath: (request: Request) => boolean,
) {
    const logger = new Logger("CryptoMiddleware");

    return (request: Request, response: Response, next: NextFunction): void => {
        const header = request.headers[CRYPTO_HEADER];
        // 无头 = 明文客户端，直接透明放行。
        if (!header || typeof header !== "string") return next();
        if (!transport.enabled || isExcludedPath(request)) return next();

        const parsed = transport.parseHeader(header);
        if (!parsed) {
            response.status(400).json({ code: 4006, message: "加密头格式错误" });
            return;
        }
        const [sessionId] = parsed;
        if (!transport.hasSession(sessionId)) {
            // 会话不存在或已过期：让客户端重新握手。
            response.status(409).json({ code: 4091, message: "加密会话失效，请重新握手" });
            return;
        }

        const method = request.method;
        const pathAndQuery = request.originalUrl;
        const aad = transport.aad(method, pathAndQuery);
        if (aad === null) {
            response.status(400).json({ code: 4006, message: "加密上下文构造失败" });
            return;
        }

        collectRawBody(request)
            .then((frame) => {
                const plaintext = frame.length > 0 ? transport.open(sessionId, aad, frame) : Buffer.alloc(0);
                if (plaintext === null) {
                    response.status(400).json({ code: 4006, message: "请求体解密失败" });
                    return;
                }
                // 把解出的明文重新塞回请求流，交给下游 body parser。
                replaceRequestBody(request, Buffer.from(plaintext));
                interceptResponse(response, transport, sessionId, aad, logger);
                next();
            })
            .catch((error: Error) => {
                logger.error(`读取加密请求体失败：${error.message}`);
                response.status(400).json({ code: 4006, message: "请求体读取失败" });
            });
    };
}

/** 读完请求的原始字节。 */
function collectRawBody(request: Request): Promise<Buffer> {
    return new Promise((resolve, reject) => {
        const chunks: Buffer[] = [];
        request.on("data", (chunk: Buffer) => chunks.push(chunk));
        request.on("end", () => resolve(Buffer.concat(chunks)));
        request.on("error", reject);
    });
}

/** 用明文字节重建请求的可读流，让后续 `express.json()` 能正常解析。 */
function replaceRequestBody(request: Request, plaintext: Buffer): void {
    // 移除加密头，避免下游误判仍是密文；补正 content-length。
    delete request.headers[CRYPTO_HEADER];
    request.headers["content-length"] = String(plaintext.length);
    let emitted = false;
    // 覆盖流读取：一次性吐出明文再 end。
    (request as unknown as { _read: () => void })._read = function read(): void {
        if (!emitted) {
            emitted = true;
            (request as unknown as { push: (chunk: Buffer | null) => void }).push(plaintext);
            (request as unknown as { push: (chunk: Buffer | null) => void }).push(null);
        }
    };
}

/** 包装响应：把下游写出的明文 body `seal()` 成密文帧再发出。 */
function interceptResponse(
    response: Response,
    transport: CryptoTransportService,
    sessionId: string,
    aad: Uint8Array,
    logger: Logger,
): void {
    const chunks: Buffer[] = [];
    const originalWrite = response.write.bind(response);
    const originalEnd = response.end.bind(response);

    (response as unknown as { write: (chunk: unknown) => boolean }).write = (chunk: unknown): boolean => {
        if (chunk) chunks.push(Buffer.from(chunk as Buffer));
        return true;
    };
    (response as unknown as { end: (chunk?: unknown) => void }).end = (chunk?: unknown): void => {
        if (chunk) chunks.push(Buffer.from(chunk as Buffer));
        const plaintext = Buffer.concat(chunks);
        const sealed = transport.seal(sessionId, aad, plaintext);
        if (sealed === null) {
            logger.error("响应加密失败，回退明文");
            originalWrite(plaintext);
            originalEnd();
            return;
        }
        const framed = Buffer.from(sealed);
        response.setHeader("content-length", String(framed.length));
        originalWrite(framed);
        originalEnd();
    };
}
