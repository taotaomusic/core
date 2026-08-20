# 桃桃音乐后端服务

Node.js 20 + TypeScript 实现。媒体、图片和歌词仍然只做实时转发；SQLite 仅保存用户账号、密码哈希和基础账号信息，不保存媒体内容。

## 启动

```powershell
npm install
npm run dev
```

生产构建：

```powershell
npm run build
npm start
```

构建后的 `dist/app.js` 会打包、压缩并移除注释；`src/` 源码保持正常可读格式。

开发模式使用 `tsx watch`，修改 `src/` 下的 TypeScript 文件后会自动重启服务；如果修改了 `package.json`，需要手动重新运行一次命令。

## 接口

- `GET /health`
- `GET /api/search?word=歌曲名`
- `GET /api/song/{id}`
- `GET /api/media?url=媒体地址`

默认端口为 `4500`。

数据库默认保存到 `data/music.sqlite`，可通过环境变量 `DATABASE_PATH` 修改。生产环境必须设置随机的 `AUTH_SECRET`。

用户接口：

- `POST /api/v1/auth/register`，JSON：`{"username":"用户名","password":"至少6位密码"}`
- `POST /api/v1/auth/login`，JSON：`{"username":"用户名","password":"密码"}`
- `GET /api/v1/auth/me`，请求头：`Authorization: Bearer <token>`

密码使用随机盐和 scrypt 哈希，不保存明文密码。登录令牌有效期为 30 天。

搜索适配接口：`GET /api/v1/search?keyword=歌曲名&page=1&num=10&quality=10`，其中 `page` 默认 1，`num` 默认 10，范围为 1–60，`quality` 默认 10，范围为 0–16。接口返回 NDJSON 流，每行一个 `{ "type": "song" }`，最后一行为 `{ "type": "end" }`。

搜索结果会遍历每首歌曲的 ID，调用 `geturl` 接口补齐指定品质的真实播放地址。播放流接口：`GET /api/v1/songs/{id}/play?quality=10`。

歌词接口：`GET /api/v1/songs/{id}/lyrics`，返回 LRC 文本；没有歌词时返回错误，不保存歌词内容。
