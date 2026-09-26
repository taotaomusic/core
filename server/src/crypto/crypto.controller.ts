import { Body, Controller, HttpCode, HttpStatus, Post } from "@nestjs/common";
import { ApiErrors } from "../common/api.exception";
import { Public } from "../common/decorators/public.decorator";
import { CryptoTransportService } from "./crypto-transport.service";

/**
 * 传输加密握手接口（`plans/009`）。
 *
 * 客户端把 ClientHello（含 device_id）以 base64 送来，服务端 accept 后返回
 * base64 的 ServerHello。走 JSON 而不是裸字节：握手请求本身不加密，量也小，
 * base64 让它在既有 JSON 中间件里通行无阻，不必额外开原始字节路由。
 *
 * 公开路由：握手时客户端尚无会话，不能要求登录。加密链路未启用（产物缺失）时
 * 返回 503，客户端据此回退明文——不阻断功能。
 */
@Controller("crypto")
export class CryptoController {
    constructor(private readonly transport: CryptoTransportService) {}

    @Public()
    @Post("handshake")
    @HttpCode(HttpStatus.OK)
    handshake(@Body() body: Record<string, unknown>): { serverHello: string } {
        if (!this.transport.enabled) {
            throw ApiErrors.serviceUnavailable(5031, "传输加密未启用");
        }
        const clientHello = decodeBase64(body?.clientHello);
        if (!clientHello) {
            throw ApiErrors.badRequest(4006, "clientHello 缺失或格式错误");
        }
        // device_id 由握手请求携带（Android ANDROID_ID / Windows MachineGuid），
        // 折进握手密钥；缺失时按空串处理（等价不绑定，握手仍可能因客户端也用空串而成立）。
        const deviceId = typeof body?.deviceId === "string" ? body.deviceId : "";
        const serverHello = this.transport.handshake(clientHello, deviceId);
        if (!serverHello) {
            // 握手失败通常是 device_id / PSK 不匹配或报文非法，统一按拒绝处理。
            throw ApiErrors.badRequest(4013, "握手被拒绝");
        }
        return { serverHello: Buffer.from(serverHello).toString("base64") };
    }
}

/** 把 base64 字段解码成字节；缺失或非字符串返回 null。 */
function decodeBase64(value: unknown): Uint8Array | null {
    if (typeof value !== "string" || value.length === 0) return null;
    try {
        return new Uint8Array(Buffer.from(value, "base64"));
    } catch {
        return null;
    }
}
