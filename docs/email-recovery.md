# 学生、导师邮箱与密码找回

2026-10-06 用户要求新增；业务范围见 TODO-65。邮箱联系方式仅向本人提供，不加入导师/学生公开目录、申请快照或导出。

## 用户流程

1. 完成首次改密后，在学生或导师工作台的“邮箱与账号安全”输入邮箱和当前密码。
2. 邮件验证通过后绑定邮箱；邮箱在账号间唯一。更换邮箱同样需要当前密码和新邮箱验证码。
3. 登录页点击“忘记密码”，输入登录标识、已绑定邮箱，取得验证码并设置新密码。
4. 重置成功后重新登录，旧密码、旧验证码和旧会话不能继续使用。未绑定邮箱时联系管理员；管理员账号仍走已有管理重置流程。

## 接口

所有写请求仍需 Cookie/请求头 CSRF，公开找回入口不会绕过此检查。

| 接口 | 身份与请求 | 响应 |
|---|---|---|
| GET /api/auth/email | 已登录且已改密的学生/导师 | 本人 email（未绑定为 null）、configured |
| POST /api/auth/email/code | 已登录，email/currentPassword | challengeId/expiresInSeconds/resendAfterSeconds |
| POST /api/auth/email/confirm | 已登录，challengeId/code | true |
| POST /api/auth/recovery/code | 公开，loginIdentifier/email | 统一受理回执，响应不含验证码 |
| POST /api/auth/recovery/reset | 公开，loginIdentifier/email/challengeId/code/newPassword | true，不自动建立会话 |

验证码目的区分 BIND/RESET，并关联账号版本。服务端锁账号行后锁验证码行，密码更新、验证码失效和审计在同一事务提交。错误验证码计数持久化，不因返回业务错误回滚。有效期用数据库 UTC 时间判定；一次成功消费使其他并发重试失败。未配置 SMTP 返回 EMAIL_UNAVAILABLE，不展示假验证码。SMTP 失败不写入可用验证码。

## 数据与部署

先备份，再应用 `sql/mysql57/migrations/20261006_email_recovery_v1.sql` 一次；兼容 MySQL 5.7/8.0。本机 MySQL 8.0 已应用。新增 account_email（已验证邮箱）、email_verification（验证码哈希/期限/尝试）、email_security_event（安全审计）三个表。迁移前备份保存在本机 dev-tools/hnust-local/before-email-recovery.sql。旧的首次建库脚本不包含这些增量表，新环境建库后仍需应用本迁移。

QQ SMTP 使用 smtp.qq.com:465、SSL，发件账号及授权码通过 MAIL_USERNAME/MAIL_PASSWORD 或 app.email.username/app.email.password 配置。SSL 证书与服务器名称均检查，不打印邮件内容或凭据。验证码包含在发送邮件中，不在应用日志/API 响应中返回。

单进程来源地址限制驻留内存；多实例部署应增加网关或共享限流。账号发送限制与验证码状态由数据库提供。过期验证码记录只供有限安全追溯，运维应按学校的保留策略定期清理；不要将邮箱和验证码表导出给业务用户。
