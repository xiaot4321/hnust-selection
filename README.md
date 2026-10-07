# 湖南科技大学师生互选系统

项目需求与业务基线见 [总体需求](requirements.md)，设计文档见 [docs/README.md](docs/README.md)。当前已实现账号认证、管理员账号生命周期与业务授权，以及学院人员、专业、年度资格和名单导入；互选流程仍待实现。

## 项目结构

```text
backend/       Spring Boot 后端（Java 8、MyBatis-Plus 3.3.1）
frontend/      Vue 3 + TypeScript + Vite 前端
docs/          需求与设计文档
sql/mysql57/   MySQL 5.7.36 初始建库脚本及说明
```

## 本机运行

学生/导师可在工作台验证绑定邮箱，并在登录页通过邮箱验证码找回密码。首次部署需应用邮箱增量迁移并配置 SMTP，详情见 [邮箱绑定与密码找回](docs/email-recovery.md)。管理员账号继续走管理重置流程。

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
