# Docker 单机部署

本方案按 Linux、服务器 IP `118.25.40.159`、新建数据库部署。前端 Nginx 提供页面和同源 `/api` 代理，后端运行 Java 8，数据库固定为项目基线 MySQL 5.7.36。数据库与私有文件使用独立持久卷；数据库、后端端口不发布到公网。当前会话、部分限流和后台任务按单实例运行，不要直接增加后端副本。

仓库提供的是部署配置，尚未在目标服务器执行。以下命令均在服务器 Bash、项目根目录运行。需要 Docker Engine 与 Compose v2（`docker compose version`）、OpenSSL。安装 Docker 时按[服务器发行版官方指引](https://docs.docker.com/engine/install/)操作。先运行 `uname -m` 核对架构；本方案面向 x86_64，ARM 服务器须先核对 MySQL 5.7.36 镜像兼容性。服务器建议至少 2 核、4GB 内存并预留构建空间。

## 1. 上传代码和配置

将当前项目源码上传到服务器，例如 `/opt/hnust-selection`。构建只需源码；不必上传 `.git`、`node_modules`、`target`、`dist`、本机 `.env` 或 `backend/var`。本次新库无需迁移本机业务数据。

```bash
cd /opt/hnust-selection
cp .env.example .env
chmod 600 .env
nano .env
```

必须替换 `MYSQL_ROOT_PASSWORD`、`DB_PASSWORD` 为两个不同的随机长密码；不要使用示例值。密码包含 `$`、`#` 时用单引号包裹，避免 Compose 插值。不要把 `.env` 提交或发到聊天中。应用使用 `selection` 账号，root 仅用于数据库初始化/维护；初始化后修改 `.env` 的密码不会自动修改数据库已有账号密码。

配置 OSS 凭证后简历/头像上传才能工作；配置 `MAIL_USERNAME` 和 `MAIL_PASSWORD`（QQ 邮箱授权码）后邮箱验证/找回才能工作。不要复用曾出现在源码里的 OSS 密钥，应先在云控制台轮换。基础登录、总管理员初始化不依赖 OSS/SMTP。

## 2. IP 地址 HTTPS

页面调用 `crypto.randomUUID()` 等浏览器安全上下文功能，直接使用公网 HTTP 可能无法办理业务；同时默认 Cookie 只通过 HTTPS 发送。没有域名时可先生成包含服务器 IP 的自签名证书：

```bash
mkdir -p deploy/certs
chmod 700 deploy/certs
openssl req -x509 -nodes -newkey rsa:3072 -days 365 \
  -keyout deploy/certs/server.key -out deploy/certs/server.crt \
  -subj '/CN=118.25.40.159' \
  -addext 'subjectAltName=IP:118.25.40.159'
chmod 600 deploy/certs/server.key
```

自签名证书不会被浏览器自动信任。用于验证的设备应通过受控渠道核对并信任 `server.crt`，再使用 HTTPS；仅绕过浏览器提示不保证所有安全上下文功能可用。正式面向师生时替换为受信任的 IP 证书，或申请域名及对应证书。私钥仅留在服务器。证书更新后执行 `docker compose -f compose.yaml -f compose.tls.yaml exec web nginx -s reload`。

云服务器安全组和服务器防火墙放行 TCP 443，SSH 端口按你的管理要求开放；无需开放 3306、8080 或 8088。确认服务器 443 未被已有服务占用。默认 HTTP 端口仅绑定服务器回环地址，TLS 配置直接提供 443。

## 3. 构建镜像，启动空数据库

```bash
docker compose config --quiet
docker compose -f compose.yaml -f compose.tls.yaml config --quiet
docker compose build
docker compose up -d db
docker compose ps
```

等待 `db` 为 healthy。可用 `docker compose logs --tail=100 db` 排查。容器启动只创建空数据库和数据库账号，**不自动运行 DDL、迁移或创建总管理员**。镜像构建会编译后端和构建前端，后端打包跳过测试；构建成功不等于业务验收完成。

## 4. 仅对本次全新空库建表

先核对版本和空库状态：

```bash
docker compose exec db sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -u root -e "SELECT VERSION(); SELECT COUNT(*) AS tables_count FROM information_schema.tables WHERE table_schema=\"hnust_selection\" AND table_type=\"BASE TABLE\";"'
```

版本应为 5.7.36，表数应为 0。只有该条件满足时，执行以下两条命令；任何一步报错都先停止检查，不要重复运行或继续启动应用：

```bash
docker compose exec -T db sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -u root' < sql/mysql57/schema.sql
docker compose exec -T db sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -u root' < sql/mysql57/migrations/20261006_email_recovery_v1.sql
```

完整 DDL 已包含人员管理、导出、导师缓存、栏目和头像等结构，无须重跑这些历史迁移；邮箱三表尚在增量脚本中，因此新库还需单独应用邮箱迁移。详细依据见 [SQL 说明](../sql/mysql57/README.md)和[邮箱功能说明](email-recovery.md)。不要导入测试数据或运行回滚脚本。

再次核对表数应为 **57**，并确认邮箱三表存在：

```bash
docker compose exec db sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -u root hnust_selection -e "SELECT COUNT(*) AS tables_count FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type=\"BASE TABLE\"; SHOW TABLES LIKE \"%email%\";"'
```

## 5. 初始化首个总管理员

表结构就绪后，在受控终端执行现有系统初始化流程：

```bash
docker compose run --rm --no-deps backend \
  --spring.main.web-application-type=none \
  --app.bootstrap.admin-enabled=true \
  --app.bootstrap.admin-login-identifier=admin \
  --app.bootstrap.admin-college-name='计算机科学与工程学院'
```

终端将显示 `admin` 的一次性随机临时凭证，请立即通过受控渠道保存/交付，不要转存容器日志或聊天。若学院不是该名称，执行前替换；有正式学院代码时追加 `--app.bootstrap.admin-college-code=正式代码`，否则现有流程会生成 `TMP-` 临时代码。该流程在同一事务内创建账号、系统管理员能力、凭证哈希和审计记录，已有总管理员时拒绝重复初始化。若输出完成后进程仍因调度线程驻留，可按 Ctrl+C 结束一次性进程；不要再次初始化。

不要使用 SQL 手工插入明文密码。常规启动不传初始化参数。临时凭证不设到期时间、成功使用一次后失效，首次登录必须改密。

## 6. 启动并访问

```bash
docker compose -f compose.yaml -f compose.tls.yaml up -d
docker compose -f compose.yaml -f compose.tls.yaml ps
docker compose logs --tail=100 backend
docker compose exec backend curl --fail --silent http://127.0.0.1:8080/actuator/health
curl --cacert deploy/certs/server.crt https://118.25.40.159/healthz
```

健康状态就绪后，浏览器打开 **https://118.25.40.159/**，使用 `admin` 和刚生成的临时凭证登录并改密。初始化只创建总管理员及其学院，学年、专业、师生、年度资格和批次由管理员后续维护。

实际验收至少覆盖：登录/退出与刷新、首次改密、管理员人员/学年维护、授权范围、PDF 上传下载、头像访问、邮件验证和找回。检查 Cookie 的 Secure/HttpOnly 及 CSRF 写请求；健康检查不能代替业务验收。

## 7. 备份、更新与回退

数据位于 `hnust-selection_mysql-data`、`hnust-selection_private-files` 两个持久卷，OSS 对象另在云端。普通 `docker compose down` 保留卷；**不要执行 `down -v`**，它会删除数据库和私有文件。

以下备份在维护窗口停止应用写入，备份数据库和本地私有文件；不要在备份失败时开始更新。SQL 和文件含敏感资料，仅保存在受控目录，并按学校策略异地备份和验证恢复。

```bash
mkdir -p deploy/backups
chmod 700 deploy/backups
umask 077
stamp=$(date -u +%Y%m%dT%H%M%SZ)
docker compose -f compose.yaml -f compose.tls.yaml stop web backend
docker compose exec -T db sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -u root --single-transaction --routines --triggers --events --hex-blob --default-character-set=utf8mb4 --set-gtid-purged=OFF hnust_selection' > "deploy/backups/database-$stamp.sql"
docker compose run --rm --no-deps --entrypoint tar backend -C /app/var/private-files -czf - . > "deploy/backups/private-files-$stamp.tar.gz"
```

保留上一版代码和镜像，在 `.env` 中将 `APP_VERSION` 设为本次发布的唯一标签（如 `20261025-1`），再构建启动：

```bash
docker compose build
docker compose -f compose.yaml -f compose.tls.yaml up -d
docker compose -f compose.yaml -f compose.tls.yaml ps
```

若没有数据库结构变更，可恢复上版配置/源码，将 `APP_VERSION` 改回旧标签，执行 `docker compose -f compose.yaml -f compose.tls.yaml up -d --no-build`。如果发生数据库迁移，必须按该迁移的兼容性和恢复方案处理，不能仅回退镜像。恢复数据库备份只应在已确认的空替代库上验证后执行，避免覆盖当前数据。修改配置后用 `up -d` 重建容器，单独 `restart` 不会更新容器环境变量。

Compose 使用 `service_healthy` 等待依赖健康；运行中的服务异常仍需监控，健康检查本身不会自动重启所有 unhealthy 容器。参见 [Docker 启动顺序文档](https://docs.docker.com/compose/how-tos/startup-order/)。MySQL 版本按当前项目基线固定，升级须另行完成数据库兼容性验证。

## 8. 后续构建的依赖缓存

后端 Dockerfile 使用 BuildKit 缓存挂载 `/root/.m2`，前端挂载 `/root/.npm`。缓存按项目分别命名并使用 `sharing=locked`，同一缓存的并发使用会串行等待。仅持久化下载的依赖，不缓存编译后的 `target` 或 `node_modules`，最终运行镜像仍仅包含应用运行所需文件。

继续使用 `docker compose build backend web` 即可。前端仍先复制 package.json/锁文件、运行 npm ci，再复制源码；只改源码时通常能复用安装依赖的整层。锁文件变化导致 npm ci 重跑时，下载缓存可复用未变的包。后端源码变化时仍执行 Maven 编译和打包，但可复用本地仓库中已下载的依赖。npm 使用 `--prefer-offline`，缺失的包仍会联网获取；镜像构建跳过 npm 审计和资助提示，依赖安全审计应单独执行。

首次采用该配置时缓存可能为空，需要先填充；旧构建层中的 Maven 仓库和 npm 缓存不会自动搬入新缓存。缓存属于服务器当前的 BuildKit builder，不会随源码压缩包迁移；切换 builder、自动垃圾回收或主动清理构建缓存后，后续可能需要重新下载。缓存可丢弃，不属于业务数据备份。

此配置面向启用 BuildKit 的 Docker；本次服务器为 Docker 28.1.0，构建日志已显示 BuildKit。构建时仍可能联网读取基础镜像信息，依赖缓存不能消除该步骤的网络延迟。参见 [Docker 缓存优化文档](https://docs.docker.com/build/cache/optimize/)。仅修改 Dockerfile 不会影响运行中的容器；如果当前镜像已经构建完成，可留到下次构建时再使用新缓存，无须为启用缓存立即重复构建。
