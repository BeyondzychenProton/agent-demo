# 海卓大脑后端镜像

在本目录执行 `docker build -t haizhuo-ai-be .`（Java 21），容器默认监听 8087。在原单仓库中从 ag-ui 目录构建。

Kubernetes 将以下变量注入后端容器：

| 变量 | 值或用途 |
| --- | --- |
| MYSQL_HOST / MYSQL_PORT / MYSQL_USER | 默认 172.31.1.10 / 13049 / root，库名 agent_demo |
| MYSQL_PASSWORD | 通过 Secret 注入 |
| CHAT_API_KEY | 模型 API 凭据，通过 Secret 注入 |
| CHAT_AUTH_COOKIE_SECRET | Cookie 加密密钥，至少 16 字节，通过 Secret 注入 |
| CHAT_AUTH_SEED_PHONE | 种子用户手机号 |
| CHAT_AUTH_SEED_PASSWORD | 种子用户密码，通过 Secret 注入 |
| MCP_ENDPOINT / MCP_APP_KEY / MCP_SECRET / MCP_DISCOVERY_PHONE | 启用会议 MCP 时配置，MCP_SECRET 通过 Secret 注入 |
| CHAT_AUTH_COOKIE_SECURE | 对外使用 HTTPS 时设为 true |

部署前准备 agent_demo 数据库及应用所需表结构，参考 src/main/resources/db/mysql。启动会按应用原有逻辑初始化认证、种子用户及技能表；镜像构建不连接数据库。将可写持久卷挂载到 /data 保存会话文件，运行 UID/GID 为 10001。为后端提供 8087 端口的 Service。
