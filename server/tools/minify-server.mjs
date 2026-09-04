// 只压缩 tsc 已经生成的服务端 JavaScript。不能用它替代 tsc：NestJS 依赖
// emitDecoratorMetadata 生成的 design:paramtypes，直接用无类型检查器的打包器会丢失元数据。
import { readdir, readFile, stat, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { minify } from "terser";

const outputDirectory = resolve("dist");

async function javascriptFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const nested = await Promise.all(entries.map(async (entry) => {
    const path = resolve(directory, entry.name);
    if (entry.isDirectory()) return entry.name === "public" ? [] : javascriptFiles(path);
    return entry.isFile() && entry.name.endsWith(".js") ? [path] : [];
  }));
  return nested.flat();
}

const files = await javascriptFiles(outputDirectory);
let originalBytes = 0;
let compressedBytes = 0;

for (const file of files) {
  const source = await readFile(file, "utf8");
  originalBytes += Buffer.byteLength(source);
  const result = await minify(source, {
    ecma: 2022,
    module: false,
    compress: { passes: 2 },
    mangle: true,
    // Nest 的依赖注入主要依赖装饰器元数据；仍保留名称，方便生产日志定位到原类和原方法。
    keep_classnames: true,
    keep_fnames: true,
    format: { comments: false },
  });
  if (!result.code) throw new Error(`压缩结果为空：${file}`);
  await writeFile(file, result.code, "utf8");
  compressedBytes += (await stat(file)).size;
}

const savedPercent = originalBytes === 0 ? 0 : Math.round((1 - compressedBytes / originalBytes) * 1_000) / 10;
console.log(`已压缩 ${files.length} 个服务端 JavaScript：${originalBytes} → ${compressedBytes} 字节（减少 ${savedPercent}%）`);
