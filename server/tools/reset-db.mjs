// 清空验证库：DROP SCHEMA public CASCADE 再建回来。
//
// SQLite 时代"删掉那个文件"就够了，换成 PostgreSQL 后需要一个显式动作，
// 否则契约验证会跑在上一轮残留的发布记录和收藏上。
//
// 用法（务必指向验证库，不要指向 music）：
//   node tools/reset-db.mjs postgres://postgres:密码@localhost:5432/music_verify
import { Client } from "pg";

const url = process.argv[2] ?? process.env.DATABASE_URL;
if (!url) {
  console.error("需要传入连接串或设置 DATABASE_URL");
  process.exit(1);
}

// 防手滑：这个脚本会删掉整个 schema，不允许指向名字里没有 verify/test 的库。
const database = new URL(url).pathname.replace(/^\//, "");
if (!/verify|test/i.test(database)) {
  console.error(`拒绝清空「${database}」—— 库名里必须含 verify 或 test，防止误删正式库`);
  process.exit(1);
}

const client = new Client({ connectionString: url });
await client.connect();
await client.query("DROP SCHEMA public CASCADE");
await client.query("CREATE SCHEMA public");
await client.end();
console.log(`已清空 ${database}`);
