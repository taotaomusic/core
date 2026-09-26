import { Module } from "@nestjs/common";
import { AppConfigModule } from "../config/config.module";
import { CryptoController } from "./crypto.controller";
import { CryptoTransportService } from "./crypto-transport.service";

/**
 * 传输加密模块（`plans/009`）。
 *
 * 提供握手接口与会话引擎。解密/加密的请求处理走 `main.ts` 里的 Express 中间件
 * （必须在 body parser 之前拿原始字节），中间件通过导出的 [CryptoTransportService]
 * 复用同一个会话引擎单例。
 */
@Module({
    imports: [AppConfigModule],
    controllers: [CryptoController],
    providers: [CryptoTransportService],
    exports: [CryptoTransportService],
})
export class CryptoModule {}
