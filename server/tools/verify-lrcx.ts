import { BodianClient } from "../src/upstream/bodian.client";

const input = [
  "[kuwo:100]",
  "[00:00.000]<5768,-5768>后<9456,7848>来 <10582,9134>(Later) - <11664,10224>刘<12748,11300>若<13826,12394>英",
  "[00:02.364]<720,-720>词：<1804,356>玉<2886,1446>城<3960,2520>千<5120,3520>春",
].join("\r\n");

const parsed = new BodianClient().parseLrcx(input);
const texts = parsed.map((line) => line.words.map((word) => word.char).join(""));
const expected = ["后来 (Later) - 刘若英", "词：玉城千春"];
const monotonic = parsed.every((line) => line.words.every((word, index) =>
  word.endMs >= word.startMs && (index === 0 || word.startMs >= line.words[index - 1].startMs),
));

if (JSON.stringify(texts) !== JSON.stringify(expected) || !monotonic) {
  console.error(JSON.stringify({ texts, expected, monotonic }, null, 2));
  process.exit(1);
}
console.log(`LRCX 验证：2 行通过，文本=${JSON.stringify(texts)}，时间单调=${monotonic}`);
