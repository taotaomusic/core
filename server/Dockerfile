# 后端运行时镜像。
#
# 刻意**不在镜像里重新构建**：CI 的 backend job 已经跑完 `npm run build`
# （tsc + 压缩 + 管理后台 vite + 拉真实分享播放器）并通过完整契约验证，
# 镜像直接复用那份已验证的 `dist/`，避免「验证的产物」与「打进镜像的产物」是两套。
# 因此构建镜像前，构建上下文里必须已存在 `dist/`（见 .github/workflows/backend.yml）。
#
# 只装生产依赖（--omit=dev），源码、devDependencies、前端源都不进镜像。
FROM node:22-slim

WORKDIR /app
ENV NODE_ENV=production

# 先装依赖，利用层缓存：package*.json 不变时不重装。
COPY package.json package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force

# 复用 CI 已构建并验证过的产物。
COPY dist ./dist

# 传输加密的 Linux 原生扩展（若 CI 拉到）。放到 native-loader 会查的
# crypto/dist/node/<平台>/ 下（相对 WORKDIR=/app 的 cwd）。PSK 不在这里，
# 由运行时环境变量 CRYPTO_PSK_ID / CRYPTO_PSK_HEX 提供；缺失则加密自动降级明文。
COPY crypto ./crypto

# 与 backend.yml / server 默认端口一致；实际端口由运行时 PORT 环境变量决定。
EXPOSE 4720

# 生产启动入口（等价 npm run start）。
CMD ["node", "dist/main.js"]
