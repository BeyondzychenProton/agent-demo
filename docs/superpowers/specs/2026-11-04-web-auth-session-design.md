# Web 登录鉴权与会话绑定

日期：2026-11-04  
状态：已定稿

## 目标

为浏览器提供登录 / 退出与 7 天登录保持，将会话消息绑定到真实用户；接口形态兼容未来多端（Cookie + Bearer）。本阶段不做注册、改密、用户管理、SSO。

## 决策

| 项 | 选择 |
|----|------|
| 鉴权栈 | Spring Security WebFlux（企业 Agent 平台底座取向） |
| 凭证 | 不透明 session token；库内存 `token_hash` |
| Web 携带 | HttpOnly + AES-GCM 加密 Cookie |
| 多端 | 同一明文 token 可通过 `Authorization: Bearer`（优先于 Cookie） |
| TTL | 绝对上限 7 天；期间每天最多滑动续期一次（只刷新 Cookie Max-Age / `renewed_at`，不延长 `expires_at`） |
| 种子用户 | 手机号 `13636528157` / 密码 `13636528157`，尽量保证 `app_user.id=1`，认领已有 `user_id=1` 会话 |

## 架构

```text
Browser --Cookie--> SecurityWebFilterChain --> AuthSession(MySQL)
Future  --Bearer-->         |                      |
                            v                      v
                     SecurityContext.userId --> ChatSessions --> agent_session*
```

## 数据模型

### `auth_session`

- `id`, `user_id`, `token_hash` (unique), `expires_at`, `renewed_at`, `created_at`, `updated_at`
- 明文 token 只出现在 Cookie / Bearer；库内 SHA-256

### 已有表

- `app_user`：BCrypt `password_hash`
- `agent_session` / `agent_session_object` / 读模型：按 `user_id` 隔离

## API

| 方法 | 路径 | 认证 | 说明 |
|------|------|------|------|
| POST | `/api/auth/login` | 否 | `{phone,password}` → Set-Cookie + `{userId,name,phone,expiresAt,token}` |
| POST | `/api/auth/logout` | 是 | 删会话、清 Cookie |
| GET | `/api/auth/me` | 是 | 当前用户；未登录 401 |

`/api/**`、`/agui/**` 需认证（除 login）。未登录统一 401。跨用户 session → 404。

## Cookie

- 名：`AS_SESSION`（可配）
- `HttpOnly`、`Path=/`、`SameSite=Lax`；`Secure` 可配
- 值：AES-GCM(token)；密钥 `chat.auth.cookie-secret`

## 前端

- 启动 `GET /api/auth/me`；失败只显示登录页
- 登录成功进入现有聊天 UI；提供退出
- 所有 `fetch` / CopilotKit：`credentials: 'include'`；401 回登录页

## 非目标

注册、改密、用户管理、角色权限、SSO、CSRF Token、多设备会话管理 UI。
