# 海卓大脑前端镜像

在本目录执行 `docker build -t haizhuo-ai-fe .`（Node.js 24 构建，Nginx 提供静态文件）。在原单仓库中从 frontend 目录构建。

前端容器监听 80。将 Ingress 指向前端 Service。Nginx 在容器启动时读取 AGENT_SCOPE_BACKEND，默认 http://haizhuo-ai-be:8087；如 Service 名称、命名空间或端口不同，部署时覆盖为对应的内部地址（不附加路径）。浏览器使用同源的 /api 和 /agui，Nginx 转发到后端并关闭代理缓冲以支持 SSE。
