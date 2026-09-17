/**
 * 图片格式嗅探。
 *
 * `multer` 给出的 `file.mimetype` 直接取自请求的 `Content-Type`，是**客户端
 * 随手填的字符串**：把 `Content-Type` 改成 `image/png` 就能让任意文件通过
 * `mimetype.startsWith("image/")` 这类校验。这类校验只挡住「无意的错文件」，
 * 挡不住任何一个会改请求的人。
 *
 * 这里只看文件头（魔数）。它同样不是内容安全的完整证明，但至少让「声明类型」
 * 和「真实类型」必须一致，并且把转存到图床的字节限定在四种已知的位图格式内
 * —— 上游图床据此决定 Content-Type 与是否内联渲染，放任上传就等于让攻击者
 * 决定别人浏览器里渲染什么。
 */

/** 允许的头像图片格式。刻意不包含 SVG：SVG 是 XML，可以内嵌脚本。 */
export type ImageKind = "png" | "jpeg" | "gif" | "webp";

/** 与 [ImageKind] 对应的标准 MIME，用于回传给上游图床。 */
export const IMAGE_MIME: Record<ImageKind, string> = {
  png: "image/png",
  jpeg: "image/jpeg",
  gif: "image/gif",
  webp: "image/webp",
};

/** 与 [ImageKind] 对应的文件扩展名，仅在客户端没给文件名时兜底。 */
export const IMAGE_EXTENSION: Record<ImageKind, string> = {
  png: "png",
  jpeg: "jpg",
  gif: "gif",
  webp: "webp",
};

const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

/**
 * 按文件头判定图片格式，无法识别时返回 `null`。
 *
 * 只读取前 12 个字节，因此对超大文件也不产生额外内存开销。
 */
export function sniffImageKind(buffer: Buffer): ImageKind | null {
  if (buffer.length >= 8 && buffer.subarray(0, 8).equals(PNG_SIGNATURE)) return "png";
  // JPEG 的起始标记是 SOI(FFD8) 加紧随其后的一个标记起始符 FF。
  if (buffer.length >= 3 && buffer[0] === 0xff && buffer[1] === 0xd8 && buffer[2] === 0xff) {
    return "jpeg";
  }
  if (buffer.length >= 6) {
    const head = buffer.subarray(0, 6).toString("latin1");
    if (head === "GIF87a" || head === "GIF89a") return "gif";
  }
  // WebP 是 RIFF 容器：前 4 字节 "RIFF"，第 8 到 12 字节 "WEBP"。
  if (buffer.length >= 12
    && buffer.subarray(0, 4).toString("latin1") === "RIFF"
    && buffer.subarray(8, 12).toString("latin1") === "WEBP") {
    return "webp";
  }
  return null;
}
