# 湖南科技大学师生互选系统

项目需求与业务基线见 [总体需求](requirements.md)，设计文档见 [docs/README.md](docs/README.md)。当前已实现账号认证、角色识别和管理员授权范围校验；其他互选业务模块仍待实现。

## 项目结构

```text
backend/       Spring Boot 后端（Java 8、MyBatis-Plus 3.3.1）
frontend/      Vue 3 + TypeScript + Vite 前端
docs/          需求与设计文档
sql/mysql57/   MySQL 5.7.36 初始建库脚本及说明
```

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

### 前端

需要 Node.js 22.20.0 和 npm 10.9.3。在另一个终端运行：

```powershell
cd frontend
npm install
npm run dev
```

Vite 默认监听 `http://localhost:5173`，并将 `/api` 请求代理到 `http://127.0.0.1:8080`。可在 `frontend/.env` 中覆盖 `VITE_API_BASE_URL`。

## 当前范围

项目包含应用入口、统一 API 响应类型、会话/CSRF 安全配置、MySQL 连接配置、前端 API 请求封装和 Vite 开发代理。已实现本地账号登录、登出、当前账号查询、首次/常规改密、一次性临时凭证消费、角色端点限制及管理员能力范围授权校验。TODO-52 定案的管理员业务能力授予/撤销接口尚待实现；其余业务 API、页面路由及业务工作台也待实现。接口与业务行为以定案文档为准；本机数据库已建立，运行项目不会重建表。
