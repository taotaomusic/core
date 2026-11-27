import assert from "node:assert/strict";
import { createRequire } from "node:module";

// 复用云端已经构建的服务端，验证协议层 → 适配器 → 控制器/歌曲映射器。
// 只替换 HTTP 响应，不访问真实上游、数据库或音源账号。
const require = createRequire(import.meta.url);
const { BodianClient } = require("../dist/upstream/bodian.client.js");
const { KuwoClient } = require("../dist/upstream/kuwo.client.js");
const { MusicController } = require("../dist/music/music.controller.js");
const { SongMapper } = require("../dist/music/song.mapper.js");
const bodian = new BodianClient();
const kuwo = new KuwoClient(null);
kuwo.clientOf = async () => bodian;
const mapper = new SongMapper();
const controller = new MusicController(null, null, null, { of: () => kuwo }, mapper, null);
const originalFetch = globalThis.fetch;
let requests = [];

function respond(body, status = 200) {
  requests = [];
  globalThis.fetch = async (url) => {
    requests.push(new URL(url));
    return new Response(typeof body === "string" ? body : JSON.stringify(body), { status });
  };
}

try {
  for (const [metadata, hasMv] of [
    [{ vid: 123 }, true],
    [{ vid: "123" }, true],
    [{ vid: 0, isMv: 1 }, true],
    [{ vid: 0, isMv: "1" }, true],
    [{ vid: 0, isMv: true }, true],
    [{ vid: 0, isMv: "0" }, false],
    [{ vid: -1 }, false],
    [{}, false],
  ]) {
    const raw = { id: 42, name: "测试歌曲", songName: "测试歌曲", ...metadata };
    respond({ code: 200, data: { resultList: [raw] } });
    const search = await kuwo.searchSongs("测试", 1, 20);
    assert.equal(mapper.toSong(search.list[0], "https://example.test", 8, false, "kuwo").hasMv, hasMv);
    assert.equal(requests.length, 1, "不得为歌曲列表逐首探测 MV");

    respond({ code: 200, data: { resultList: [raw], total: 1 } });
    const detail = await kuwo.getArtistSongs(1, 1, 20);
    assert.equal(mapper.toSong(detail.songs[0], "https://example.test", 8, false, "kuwo").hasMv, hasMv);

    respond({ code: 200, data: raw });
    assert.equal((await controller.info(42, undefined, "kuwo")).hasMv, hasMv);
    assert.equal((await controller.infoBatch("42", undefined, "kuwo")).songs[0].hasMv, hasMv);
  }

  const mv = { mid: 123, name: "测试 MV", highUrl: "https://example.test/mv.mp4", lowUrl: "", mvDuration: 180 };
  respond({ code: 200, data: { mv } });
  assert.deepEqual(await controller.mv(42, "kuwo"), { ...mv, hasMv: true });
  assert.equal(requests[0].searchParams.get("musicId"), "42", "必须按歌曲 ID 查询 MV");

  respond({ code: 200, data: { mv: { ...mv, highUrl: "", lowUrl: "http://example.test/low.mp4" } } });
  assert.equal((await controller.mv(42, "kuwo")).hasMv, true, "只有标清地址也应可播放");

  for (const data of [{}, { mv: null }]) {
    respond({ code: 200, data });
    assert.deepEqual(await controller.mv(42, "kuwo"), { hasMv: false });
  }

  // 任何一种故障都必须保持可重试的 502，不能误报没有 MV。
  for (const [body, status] of [
    [{ code: 500, data: {} }, 200],
    [{ code: 200, data: { mv } }, 503],
    ["<html>上游网关错误</html>", 502],
    [{}, 200],
    [{ code: 200, data: null }, 200],
    [{ code: 200, data: [] }, 200],
    [{ code: 200, data: { mv: [] } }, 200],
    [{ code: 200, data: { mv: {} } }, 200],
    [{ code: 200, data: { mv: { ...mv, highUrl: "", lowUrl: "" } } }, 200],
  ]) {
    respond(body, status);
    await assert.rejects(() => controller.mv(42, "kuwo"), (error) => error.getStatus() === 502 && error.code === 5020);
  }
  globalThis.fetch = async () => { throw new Error("网络超时"); };
  await assert.rejects(() => controller.mv(42, "kuwo"), (error) => error.getStatus() === 502);

  respond({ code: 200, data: { mv } });
  await assert.rejects(() => controller.mv(-1, "kuwo"), (error) => error.code === 4001);
  assert.equal(requests.length, 0, "非法歌曲 ID 不得访问上游");
  console.log("MV 契约通过：歌曲标记全链路、正常无 MV、可播放 MV、上游故障与歌曲身份校验");
} finally {
  globalThis.fetch = originalFetch;
}
