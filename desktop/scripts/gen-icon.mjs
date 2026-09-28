// 生成一张纯色方形 PNG 作为图标源，交给 `tauri icon` 切出各尺寸。
// 纯 Node（zlib 内置），不引依赖、确定性输出；避免把二进制图标提交进仓库。
// 用法：node scripts/gen-icon.mjs > app-icon.png
import zlib from "node:zlib";

const SIZE = 512;
const RGBA = [0x4f, 0x46, 0xe5, 0xff]; // 品牌靛蓝

function crc32(buf) {
  let c = ~0;
  for (let i = 0; i < buf.length; i++) {
    c ^= buf[i];
    for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xedb88320 & -(c & 1));
  }
  return (~c) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const typeBuf = Buffer.from(type, "ascii");
  const body = Buffer.concat([typeBuf, data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body), 0);
  return Buffer.concat([len, body, crc]);
}

const ihdr = Buffer.alloc(13);
ihdr.writeUInt32BE(SIZE, 0);
ihdr.writeUInt32BE(SIZE, 4);
ihdr[8] = 8; // bit depth
ihdr[9] = 6; // color type RGBA
// 10,11,12 = 0 (compression, filter, interlace)

const row = Buffer.alloc(1 + SIZE * 4);
for (let x = 0; x < SIZE; x++) {
  row[1 + x * 4] = RGBA[0];
  row[1 + x * 4 + 1] = RGBA[1];
  row[1 + x * 4 + 2] = RGBA[2];
  row[1 + x * 4 + 3] = RGBA[3];
}
const raw = Buffer.concat(Array.from({ length: SIZE }, () => row));
const idat = zlib.deflateSync(raw);

const png = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  chunk("IHDR", ihdr),
  chunk("IDAT", idat),
  chunk("IEND", Buffer.alloc(0)),
]);
process.stdout.write(png);
