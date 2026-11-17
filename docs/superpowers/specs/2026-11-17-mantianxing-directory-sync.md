# 满天星组织通讯录同步接口 v1

海卓大脑接收满天星主动推送，并在「用户与权限」展示同步后的组织架构和成员。满天星维护源数据及记录版本，海卓大脑自动同步本地登录账号并维护登录密码与平台授权。

## 数据映射

| 满天星数据 | 海卓大脑落库 | 推送字段 |
| --- | --- | --- |
| `sys_dept` 中 `type=0` 的部门 | `app_directory_department` | `deptId`, `parentId`, `deptName`, `orderNum`, `status`, `delFlag`, `version` |
| `sys_user` 的账号 | `app_directory_user` | `userId`, `deptId`, `userName`, `nickName`, `email`, `phonenumber`, `status`, `delFlag`, `version` |

部门与用户各用源 ID 做主键。部门父子关系取 `parent_id`，用户归属取 `sys_user.dept_id`。发送方筛选掉 `sys_dept.type=1` 的岗位节点。若已同步部门改为岗位节点，发送该部门的删除记录。岗位、员工档案以及本地平台权限属于后续接入范围。发送方按上表组装负载，源密码、证件、薪酬等信息留在满天星。

## 推送

`POST /api/integrations/mantianxing/v1/directory/changes`

使用 HTTPS、`Content-Type: application/json`，并通过 `X-MTX-Sync-Token` 传递双方配置的服务令牌。接收端通过环境变量 `MTX_DIRECTORY_SYNC_TOKEN` 注入令牌，空值时返回 503。使用海卓大脑部署环境的后端地址。

```json
{
  "departments": [
    {
      "deptId": "200",
      "parentId": "100",
      "deptName": "研发部",
      "orderNum": 10,
      "status": "0",
      "delFlag": "0",
      "version": "1"
    }
  ],
  "users": [
    {
      "userId": "9007199254740993",
      "deptId": "200",
      "userName": "example",
      "nickName": "示例成员",
      "email": "example@example.com",
      "phonenumber": "19900000301",
      "status": "0",
      "delFlag": "0",
      "version": "1"
    }
  ]
}
```

- 两个数组可按需提供，合计每批 1–500 条。创建、修改和恢复时发送当前完整记录。ID 和版本推荐使用十进制字符串，取值范围为正的有符号 64 位整数。部门的根节点 `parentId` 用 `null` 或 `0`，未分配部门的用户 `deptId` 用 `null` 或 `0`。
- 存续记录 `delFlag="0"`。部门需提供 `deptName` 和 `status`，`orderNum` 省略时为 0。用户需提供 `userName`、`nickName`、`status` 以及来自 `sys_user.phonenumber` 的 `phonenumber`，邮箱可空。手机号接受 7–20 位数字及可选的前导加号。按来源字段长度传输，源 `nick_name` 为空时可用 `user_name` 补齐。源状态为 0 时启用，为 1 时停用，源状态为空的映射需双方确认。
- 删除使用 `delFlag="2"`，仅需该实体的 ID、`version` 和 `delFlag`。接收端保留源 ID 和删除版本，并清理该记录的展示字段。删除部门按单条记录处理，所属用户及子部门的变更由发送方分别推送。
- 每个源 ID 的 `version` 在每次业务变更时严格递增，初始最小为 1。重试沿用原版本和负载。同版本及更旧版本会被忽略，保证重复或乱序到达时数据不会回退。版本由发送方在提交变更时可靠生成，可以使用持久化事件序号。源表的秒级 `update_time` 可能重复或为空。
- 初次上线用此接口按批发送全量基线，之后发送增量。切换时保留基线水位之后的变更，基线记录的版本需低于该记录的后续变更。请求中缺省的 ID 不触发删除。
- 同批写入使用一个数据库事务。提交成功返回 204，已处理版本或过期版本同样返回 204。400 表示负载无效，401 表示令牌校验失败，409 表示登录手机号冲突，415 表示请求格式错误。遇到超时或服务端 5xx，用原批次重试。部门和用户允许跨批次乱序到达，等相关部门到达后刷新即可看到归属。

## 登录账号同步

- 用户记录首次写入时，系统以满天星的 `userId` 创建 `app_user` 登录账号，用户可用 `phonenumber` 和初始密码 `nonProdPassword` 登录。系统用 BCrypt 按账号生成哈希。使用初始密码登录后必须先修改密码，再进入系统。
- 姓名、手机号和账号状态随来源记录更新。本地账号主键、聊天记录及用户已设置的密码保留。停用或删除会阻止登录并撤销已有会话，恢复后使用原密码登录。
- 活跃手机号已被其他本地账号占用，或属于平台管理员配置时，整批返回 409 并回滚。发送方需解决冲突后再投递。已有本地账号的身份和会话通过原账号保留。
- 管理服务重启时自动执行 `V8__app_user_mantianxing.sql` 和 `V9__app_user_password_change.sql`。已同步账号仍在使用初始密码的，也会被要求改密。若此前已按旧版契约导入通讯录，满天星需补发包含手机号且版本更高的用户记录，以创建对应登录账号。
- 登录和 `GET /api/auth/me` 返回 `mustChangePassword`。为 true 时，前端显示强制改密页，业务接口返回 403 和 `PASSWORD_CHANGE_REQUIRED`。改密接口为 `POST /api/auth/password`，JSON 字段是 `currentPassword` 和 `newPassword`。成功返回 204，当前会话恢复业务访问，其他已有登录会话失效。


## 推送历史

`app_directory_push_history` 按认证后的推送请求记账，重试单独留一条历史。来源列记录 `MANTIANXING`，计数列记录请求中的部门数和用户数，包含重复或过期记录。

- `ACCEPTED` 表示批次已处理，历史与通讯录变更在同一事务提交。重复或过期请求也会留历史。
- `REJECTED` 表示认证通过但请求体无效，保存结果和时间，原始请求体不入库。
- `FAILED` 表示通过校验但批次写入失败，回滚后尝试单独保存历史，数据库不可用时可能无法落库。

历史保存通过校验的通讯录字段，包含账号和邮箱。用于登录的 `phonenumber` 只写入 `app_user`。服务令牌和负载中未定义的字段不写入历史。未通过令牌校验的请求不入库。

## 管理端读取

`GET /api/admin/directory` 使用现有本地登录会话，并按平台管理员配置授权。返回 `departments` 与 `users`，各 ID 以字符串返回。前端按部门树展示成员，父部门计数包含可见子部门成员。已删除记录从返回结果移除。查到成员而暂未收到其部门时，该成员仍可在「全部成员」查看。

建表脚本为 `ag-ui/src/main/resources/db/mysql/V7__app_directory.sql`，后端启动时执行。数据库需支持 MySQL 8.0.19 或更新版本的 INSERT 行别名语法。
