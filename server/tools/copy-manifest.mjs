// 生成 dist/package.json：只保留生产依赖与启动脚本。
// 部署时上传 dist/ 与 package-lock.json，在 dist 同级执行 npm install --omit=dev。
import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

const root = resolve(import.meta.dirname, "..");
const manifest = JSON.parse(readFileSync(resolve(root, "package.json"), "utf8"));

const production = {
  name: manifest.name,
  private: true,
  scripts: { start: "node main.js" },
  engines: manifest.engines,
  dependencies: manifest.dependencies,
};

writeFileSync(resolve(root, "dist/package.json"), `${JSON.stringify(production, null, 2)}\n`);
console.log("已生成 dist/package.json");
