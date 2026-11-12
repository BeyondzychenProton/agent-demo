# 海卓大脑后端镜像

在本目录执行 `docker build -t haizhuo-ai-be .`（Java 21）。在原单仓库中从 ag-ui 目录构建。

Demo 配置集中在 src/main/resources/application.properties，随 JAR 打包。容器监听 8087，工作目录为 /data。修改配置后重新构建镜像，再更新 Kubernetes 工作负载。请限制仓库和镜像访问范围，因为配置包含凭据。

数据库为 172.31.1.10:13049/agent_demo。部署前准备应用所需表结构，参考 src/main/resources/db/mysql。技能表由部署方预先创建，脚本为 V6__agent_skill.sql。应用启动会确保登录会话表存在。登录使用 app_user 中的有效账号及 BCrypt 密码哈希。请在首次登录前准备账号。

在 Kubernetes 中将可写持久卷挂载到 /data，并为后端提供 8087 端口的 Service。
