import { createServer } from "node:http";
import { port } from "./config.js";
import { handleApi } from "./routes/api.js";
import { json } from "./utils/http.js";

/** 应用入口：只负责组装 HTTP 服务和路由。 */
createServer(async (request, response) => {
  try {
    const url = new URL(request.url ?? "/", `http://${request.headers.host ?? "localhost"}`);
    if (url.pathname === "/") return json(response, 404, { code: 4040, message: "接口不存在" });
    if (url.pathname === "/health") return json(response, 200, { code: 0, message: "success", data: { status: "up" } });
    return await handleApi(url, request, response);
  } catch (error) { return json(response, 502, { code: 5020, message: error instanceof Error ? error.message : "代理请求失败" }); }
}).listen(port, () => console.log(`桃桃音乐代理服务已启动：http://localhost:${port}`));
