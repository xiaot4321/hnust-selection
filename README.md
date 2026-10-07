# 湖南科技大学师生互选系统

项目需求与业务基线见 [总体需求](requirements.md)，设计文档及逐角色实现状态见 [文档目录与当前实现状态](docs/README.md)。学生志愿和补选、导师轮次办理、关系与名额处理、管理员批次治理等主要互选链路均已接入前后端代码；这表示代码入口和主要业务服务存在，不表示全部验收测试或目标环境部署已经完成。

## 项目结构

```text
backend/       Spring Boot 后端（Java 8、MyBatis-Plus 3.3.1）
frontend/      Vue 3 + TypeScript + Vite 前端
docs/          需求与设计文档
sql/mysql57/   MySQL 5.7.36 初始建库脚本及说明
```

## 当前实现概况

- **学生端：** 已接入批次和进度查询、导师目录、身份确认、志愿提交/撤回/历史、补选申请、资料维护、简历上传、更正申请和站内通知。
- **导师端：** 已接入公开资料、批次招生范围、常规轮次和补选的逐项/批量办理、名额与关系查询、站内消息及受控简历访问。
- **管理端：** 已接入人员/资格/导入、账号能力授权、身份纠错、导师资料审核、批次与轮次命令、关系撤销/恢复/改派、统计、审计和异步导出。
- **尚待补齐或验证：** TODO-49 特殊业务边界仍暂缓；自助找回密码尚未实现；师生忘记密码时管理员重置的权限和 API 范围列为待确认 TODO-65；简历恶意文件扫描集成及目标环境部署运维验证仍待落实。当前仓库测试源码未覆盖核心志愿/导师决定/补选/关系调整链路，不能据此认定端到端验收完成。

详细模块入口、限制和验证状态见 [docs/README.md](docs/README.md)。业务规则以 [requirements.md](requirements.md)、[docs/business-rules.md](docs/business-rules.md) 和 [docs/todo-register.md](docs/todo-register.md) 为准；本文只作项目入口与现状摘要。

## 本机运行

### 后端

需要 Java 8 和本机 Maven 3.9.1。后端连接本机 MySQL `hnust_selection` 数据库，默认地址为 `127.0.0.1:3306`。在 PowerShell 后端终端中设置数据库账号后启动。HTTPS 部署默认使用安全 Cookie；若本机以纯 HTTP 调试，可显式设 `SESSION_COOKIE_SECURE=false`：

```powershell
$env:DB_USERNAME = "你的数据库用户名"
$env:DB_PASSWORD = "你的数据库密码"
$env:SESSION_COOKIE_SECURE = "false"
mvn -f backend/pom.xml spring-boot:run
```

可通过 `DB_URL` 覆盖默认 JDBC 地址，通过 `SERVER_PORT` 修改后端端口。应用配置关闭了 Spring SQL 初始化，不会自动执行建库脚本。
服务启动后可访问 `http://127.0.0.1:8080/actuator/health` 查看运行状态。

首次初始化总管理员时，在 PowerShell 中运行 `backend/scripts/bootstrap-admin.ps1`。该脚本默认使用已确认的登录名 `admin` 和学院名称“计算机科学与工程学院”，通过非 Web 模式启动一次性初始化流程；学院代码未提供时会生成 `TMP-` 开头的随机临时代码并在终端显示。请在受控本机终端查看不设到期时间的一次性临时凭证并立即线下交付；首次登录必须设置正式密码。初始化会在一个数据库事务中写入账号、能力授权、凭证哈希和审计记录；若槽位已被占用，脚本会拒绝重复初始化。临时代码之后应按学院正式编码维护流程更正。

### 前端

需要 Node.js 22.20.0 和 npm 10.9.3。在另一个终端运行：

```powershell
cd frontend
npm install
npm run dev
```

Vite 默认监听 `http://localhost:5173`，并将 `/api` 请求代理到 `http://127.0.0.1:8080`。可在 `frontend/.env` 中覆盖 `VITE_API_BASE_URL`。

## 当前范围

项目包含本地会话认证、人员与资格管理、批次配置/生命周期、冻结名单和导师范围、学生志愿与导师处理工作台、关系与名额事务、结果查询，以及管理员身份纠错、导师资料审核、关系撤销和受控数据导出等功能。管理员批次管理支持暂停、恢复、取消、归档、统计、轮次延期/重开和补选导师名单维护；轮次重开要求填写新的截止时间。管理员导出任务迁移已应用到本机验证库；其他环境应用前须按迁移说明核对目标库。数据库密码通过 `DB_USERNAME`/`DB_PASSWORD` 环境变量注入；应用关闭启动 SQL 初始化，不会自动建库或迁移。当前完整 DDL 与向前迁移见 [MySQL 5.7.36 脚本说明](sql/mysql57/README.md)。
