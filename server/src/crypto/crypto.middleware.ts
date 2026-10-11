import { Logger } from "@nestjs/common";
import type { NextFunction, Request, Response } from "express";
import { PassThrough } from "stream";
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
 * @param isMandatoryEncrypted 判定路径是否**强制加密**：命中且链路已启用时，
 *   无加密头的明文请求会被拒绝（防降级）。仅覆盖已接入加密的白名单接口，
 *   其余接口仍对明文透明，不影响 Web / 旧客户端 / 未接入的接口。
 */
export function createCryptoMiddleware(
    transport: CryptoTransportService,
    isExcludedPath: (request: Request) => boolean,
    isMandatoryEncrypted: (request: Request) => boolean = () => false,
) {
    const logger = new Logger("CryptoMiddleware");

    return (request: Request, response: Response, next: NextFunction): void => {
        const header = request.headers[CRYPTO_HEADER];
        const hasHeader = typeof header === "string" && header.length > 0;

        // 请求加密可观测性：对 /api/v1 请求打一行「明文 / 加密」，便于确认客户端是否
        // 真的走了加密链路。默认开启；量大时可设 CRYPTO_REQUEST_LOG=off 关闭。
        if (process.env.CRYPTO_REQUEST_LOG !== "off" && request.path.startsWith("/api/v1/")) {
            const tag = !hasHeader
                ? "明文"
                : !transport.enabled
                  ? "带加密头但服务端未启用加密（按明文处理）"
                  : isExcludedPath(request)
                    ? "带加密头但路径在排除表（按明文处理）"
                    : "加密";
            // 客户端以明文头上报机型/系统版本（X-Taotao-Device），便于审计看出是什么设备。
            const dev = request.headers["x-taotao-device"];
            const devTag = typeof dev === "string" && dev.length > 0 ? ` [${dev}]` : "";
            logger.log(`${request.method} ${request.originalUrl} → ${tag}${devTag}`);
        }

        if (!header || typeof header !== "string") {
            // 无头 = 明文请求。强制加密白名单内的接口（且链路已启用）直接拒绝，
            // 防止降级攻击；其余接口透明放行。
            if (transport.enabled && !isExcludedPath(request) && isMandatoryEncrypted(request)) {
                response.status(426).json({ code: 4007, message: "此接口要求加密访问，请升级客户端" });
                return;
            }
            return next();
        }
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
    // 原 IncomingMessage 已被 collectRawBody 读到 end（readableEnded=true），对已结束
    // 的流 push/unshift 都不会再吐数据——此前用 _read 覆盖的写法对带体的 POST 全部
    // 失效（下游拿到空 body）。做法是造一条装好明文的 PassThrough，把**读侧**方法
    // 以 own property 遮到请求对象上；headers/method/url/socket 保持原样，
    // 守卫、限流、审计日志读到的请求上下文不受影响。
    const body = new PassThrough();
    body.end(plaintext);
    for (const name of [
        "on", "once", "off", "removeListener", "removeAllListeners",
        "read", "pause", "resume", "pipe", "unpipe",
    ] as const) {
        Object.defineProperty(request, name, {
            value: (body as unknown as Record<string, (...args: unknown[]) => unknown>)[name].bind(body),
            configurable: true,
        });
    }
    for (const [prop, getter] of [
        ["readable", (): boolean => body.readable],
        ["readableEnded", (): boolean => body.readableEnded],
        ["readableFlowing", (): boolean | null => body.readableFlowing],
        ["readableLength", (): number => body.readableLength],
    ] as const) {
        Object.defineProperty(request, prop, { get: getter, configurable: true });
    }
    // 移除加密头，避免下游误判仍是密文；补正 content-length。
    delete request.headers[CRYPTO_HEADER];
    request.headers["content-length"] = String(plaintext.length);
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
    // @RawResponse 路由可能用 writeHead 直接发头（歌词接口就是这么写的）：只记录
    // 不发送，真实头推迟到 end() 封帧后一起出，否则 content-length 先按明文发出去，
    // 封帧后再改就是「Cannot set headers after they are sent」。
    let pendingStatus: number | undefined;
    let pendingStatusMessage: string | undefined;
    let pendingHeaders: Record<string, string> | undefined;
    const originalWrite = response.write.bind(response);
    const originalEnd = response.end.bind(response);
    const originalWriteHead = response.writeHead.bind(response);

    (response as unknown as { writeHead: (...args: unknown[]) => unknown }).writeHead = (
        ...args: unknown[]
    ): unknown => {
        const [status, second, third] = args as [number, unknown, unknown];
        pendingStatus = status;
        if (typeof second === "string") pendingStatusMessage = second;
        pendingHeaders = (typeof second === "object" && second !== null ? second : third) as
            | Record<string, string>
            | undefined;
        return response;
    };

    (response as unknown as { write: (chunk: unknown) => boolean }).write = (chunk: unknown): boolean => {
        if (chunk) chunks.push(Buffer.from(chunk as Buffer));
        return true;
    };
    (response as unknown as { end: (chunk?: unknown) => void }).end = (chunk?: unknown): void => {
        if (chunk) chunks.push(Buffer.from(chunk as Buffer));
        const plaintext = Buffer.concat(chunks);
        // 摘掉影子方法再落盘：originalWrite/originalEnd 内部会经 this.writeHead /
        // this.write 重新派发，影子不摘会把状态行和封好的帧再次吞进缓冲，
        // socket 上就只剩没有状态行的裸帧字节。
        const shadows = response as unknown as Record<string, unknown>;
        delete shadows.writeHead;
        delete shadows.write;
        delete shadows.end;
        if (response.headersSent) {
            // 头已经出去了（flushHeaders 之类的旁路）：封帧已不可能，退回明文保住响应。
            logger.warn("响应头在封帧前已被发出，回退明文");
            originalWrite(plaintext);
            originalEnd();
            return;
        }
        const sealed = transport.seal(sessionId, aad, plaintext);
        if (sealed === null) {
            logger.error("响应加密失败，回退明文");
            originalWrite(plaintext);
            originalEnd();
            return;
        }
        const framed = Buffer.from(sealed);
        const contentLength = String(framed.length);
        if (pendingStatus !== undefined) {
            const headers = { ...(pendingHeaders ?? {}), "content-length": contentLength };
            if (pendingStatusMessage !== undefined) {
                originalWriteHead(pendingStatus, pendingStatusMessage, headers);
            } else {
                originalWriteHead(pendingStatus, headers);
            }
        } else {
            response.setHeader("content-length", contentLength);
        }
        originalWrite(framed);
        originalEnd();
    };
}
