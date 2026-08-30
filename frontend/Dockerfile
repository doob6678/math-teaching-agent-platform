# 本机 WSL 对 registry.npmjs.org 直连长期超时（NAT 出网），npmmirror 实测 0.7s 可达；
# 仅在镜像构建期使用镜像源安装依赖，不改变运行时行为。
FROM node:22-alpine AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm ci --registry=https://registry.npmmirror.com
COPY index.html tsconfig.json vite.config.ts ./
COPY src ./src
RUN npm run build

FROM nginx:1.29-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=12 \
    CMD wget -q -O /dev/null http://127.0.0.1/healthz || exit 1
