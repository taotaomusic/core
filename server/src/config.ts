/** 服务运行配置。 */
export const port = Number(process.env.PORT ?? 4500);
export const upstreamBaseUrl = "https://api.vkeys.cn/v2/music/tencent";
export const allowedMediaHosts = new Set(["ws.stream.qqmusic.qq.com", "y.qq.com"]);
