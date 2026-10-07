# 师生互选系统 API 设计

> 版本：1.7（师生凭证重置接口缺口登记）
> 状态：补齐身份纠错、导师公开资料审核、关系调整、批次轮次命令、补选导师名单及管理员数据导出的 API 契约；TODO-59 至 TODO-64 已确认。当前关系调整只支持常规恢复/改派，TODO-49 特殊边界继续拒绝。管理员账户端点只支持总管理员重置其他普通 ADMIN；用户故事中的师生忘记密码重置尚无接口，权限和 API 范围待 TODO-65 确认。轮次重开请求须提交 `newEndAt` 和原因。本文件定义契约，不单独作为实现完成或验收通过的证明；当前代码状态见[文档目录](README.md#当前代码实现状态)。
> 更新日期：2026-10-07（API 端点契约按已确认范围维护；TODO-65 尚未定案）
> 基线：[总体需求](../requirements.md) 0.29、[业务规则](business-rules.md) 0.18、[状态机](state-machine.md) 0.14、[权限矩阵](permission-matrix.md) 1.7、[用户故事](user-stories.md) 2.0、[功能模块设计](functional-modules.md) 0.16、[物理数据库设计](database-design.md) 0.8。

## 1. 设计范围与原则

本文把已确认的业务能力映射为 HTTP API，并定义请求/响应、错误、授权、幂等和并发契约。端点路径和字段以本文现有内容定案，实施不得自行改变业务语义；需要调整时同步更新 API、业务规则或权限设计并记录新版本。

- API 使用 `/api` 前缀，JSON 属性采用 lower camelCase；Controller 接收独立 Request，返回角色化 DTO/VO，不返回数据库 Entity。
- 资源查询使用 GET；修改资源使用 PUT/PATCH；发布、启动、录取、关闭等状态迁移使用明确的命令端点，不允许客户端直接提交任意状态值。
- 所有写操作由服务端重新验证身份、授权范围、对象归属、状态、时间、资格和范围。客户端传入的对象 ID 仅用于定位，不构成授权证明。
- 一个关键业务命令的校验、状态变化、关系/名额变动、业务事件和审计须按对应事务边界提交。Mapper/数据库约束是并发保护的一部分；前端禁用按钮不构成防重。
- 首期只设计站内通知；不设计系统外公示或邮件/短信接口。关系调整端点支持普通恢复/改派；TODO-49 特殊边界请求通过 `BUSINESS_RULE_DEFERRED` 拒绝，不新增相应办理路径。

## 2. 通用 HTTP 契约

### 2.1 请求和成功响应

除登录、受控文件上传/下载等特殊传输外，请求和响应使用 `application/json; charset=utf-8`。成功响应统一使用下列结构；创建资源可返回 HTTP `201`，查询和同步命令成功通常返回 `200`，已持久接收的异步命令返回 `202`。为了保持统一信封，不使用无响应体的 `204`。

```json
{
  "code": "OK",
  "message": "操作成功",
  "data": {
    "id": 123
  }
}
```

列表统一返回 `data.items`、`data.total`、`data.pageNo`、`data.pageSize`；分页参数为 `pageNo`、`pageSize`，默认页长 20、最大 100。普通列表使用明确、稳定的默认排序（时间字段加唯一 ID 作次级排序）；业务队列使用已确认的业务排序。服务端对筛选字段和排序字段使用白名单，不接受任意 SQL 字段名。

时间字段统一采用带时区偏移的 RFC 3339/ISO 8601 字符串，例如 `2026-10-02T09:00:00+08:00`。服务端和数据库以 UTC 瞬时进行保存/比较（MySQL `DATETIME(3)` 按 UTC 解释），界面按 `Asia/Shanghai` 展示。填报、轮次和补选窗口采用左闭右开区间 `[startAt, endAt)`：服务端先锁定阶段记录，再读取数据库 UTC 时间作权威校验；客户端时间不参与，恰好等于 `endAt` 时拒绝。若事务内校验时刻早于 `endAt` 且通过，即使事务在截止后提交仍然有效。异步批量操作逐项按各自事务的阶段校验时刻判定；仅收到批量请求不代表其中项目已在截止前办理（TODO-50）。

### 2.2 错误响应与 HTTP 状态

错误也使用 `code`、`message`、`data` 信封。`data` 可包含字段错误或操作状态；对权限外对象可按防止资源枚举的策略返回 `404`。

```json
{
  "code": "PREFERENCE_COUNT_INVALID",
  "message": "志愿数量必须为 1 至 3 项",
  "data": {
    "fieldErrors": [
      { "field": "items", "reason": "SIZE_OUT_OF_RANGE" }
    ]
  }
}
```

| HTTP 状态 | 使用情形 |
|---|---|
| `400` | JSON/参数格式错误、字段校验失败；未执行任何业务写入。 |
| `401` | 未认证、登录凭证无效或会话失效。 |
| `403` | 已认证但缺少该类操作权限；不泄露对象内容。 |
| `404` | 资源不存在，或按对象范围隐藏策略不应向调用者暴露。 |
| `202` | 请求已持久接收为异步操作；响应包含 `operationId`，客户端通过操作查询端点读取进度和结果。 |
| `409` | 业务状态不允许、唯一约束冲突、幂等键被不同请求复用等状态冲突。 |
| `412` | `If-Match` 版本前置条件已过期，资源在客户端读取后已被他人修改。 |
| `413` | 上传超过大小限制。 |
| `415` | 上传媒体类型不受支持。 |
| `428` | 资源要求版本前置条件，但请求未提供。 |
| `500` | 未预期服务端错误；响应不得包含堆栈、SQL 或敏感字段。 |
| `503` | 操作暂未完成且可查询/重试，例如幂等操作仍在处理中；须返回可追踪的 `operationId`（若已建立）。 |

业务冲突不以 HTTP `200` 伪装成功。批量命令请求被持久接收后返回 `202` 和操作 ID，完成后通过操作查询端点以 `200` 返回逐项结果；若请求整体格式无效，则返回 `400` 且不处理任何项目。

### 2.3 文件传输

简历限 PDF、单文件不超过 10 MB。应用端接收 `multipart/form-data`，校验扩展名、媒体类型、文件签名和大小；文件先隔离，完成恶意文件扫描并通过后才标记可用。文件以随机内部键存到 Web 根目录之外，计算 SHA-256 摘要。下载须逐次鉴权并记入敏感数据访问审计，返回受控二进制流及安全的 `Content-Disposition`；不得返回免鉴权的永久公开链接。扫描服务和私有存储介质须在部署前落实；若学校后续提供对象存储，可在不改变授权契约的前提下替换底层存储适配器。

## 3. 认证、角色与对象授权

首期采用 Spring Security 本地账号认证和服务端 Session，浏览器使用 `HttpOnly`、`Secure`、`SameSite=Lax` Cookie，并为 Cookie 会话启用 CSRF 防护。当前需求明确了本地账号、密码重置和一次性临时凭证流程，且首期是 Web 系统，没有已确认的移动端/第三方客户端需求。若学校明确要求统一身份认证，再接入 OIDC/SAML 适配层并映射到系统账号，不改变领域授权规则。请求进入时必须形成可信登录主体；凭证不得由业务 Request 里的 `accountId` 或角色字段替代。

1. **学生本人：** `/me` 类 API 从认证主体解析学生 ID。志愿、身份确认、补选、资料和结果接口不接受客户端指定 `studentId`；写入只能作用于本人。
2. **导师本人：** 导师资料和可报范围只可维护本人；申请队列和决定接口要验证申请确实投向该导师、属于当前可办理阶段，并过滤到授权资料字段。不能查询学生其他志愿、联系方式或成绩。
3. **管理员：** 同时校验管理员能力、学院/批次授权范围、对象状态和具体操作权限。查询、敏感字段读取、关系例外和导出均使用相同数据范围过滤。
4. **总管理员：** 仍是 `ADMIN` 业务主体，但隐式拥有全部已登记管理员业务能力并覆盖全系统启用学院/批次；管理员账户创建/重置及其他管理员账号的业务能力授权/撤销仍为其专属操作。该能力不授予 `ROLE_STUDENT` / `ROLE_TEACHER`，也不跳过业务对象状态、名额、时间或审计检查。常规 API 不得授予、转移或撤销 `ADMIN_ACCOUNT_MANAGER`；仅允许系统初始化设置或按 TODO-24 应急恢复流程处理。
5. **普通管理员业务能力：** `COLLEGE_ADMIN` 限完整学院范围；`BATCH_MANAGER` 可限完整学院或指定批次，负责批次管理和名额配置，指定批次授权不能新建批次；`BATCH_AUDIT` 只读。导师招生范围由导师本人配置，不由管理员代设（TODO-57）。
6. **文件和导出：** 每次下载/导出独立授权；字段由服务端白名单生成，不能信任客户端提交的字段集合。访问简历、联系方式或导出数据时写入访问记录。
7. **状态门禁：** 权限通过不等于操作可用。领域服务还须检查批次、阶段、申请、学生匹配状态、冻结范围及截止时间；不得通过换 URL 或伪造对象 ID 绕过。

调用者无权访问或不应获知存在性的对象统一返回 `404 NOT_FOUND` 以降低 ID 枚举风险；调用者可见资源上的动作权限不足时返回 `403 FORBIDDEN`。

## 4. 接口目录（已确认契约）

下表列出首期已确认的主要端点；本版本列出的路径、方法和主要字段作为实现契约冻结。未列出的 CRUD 字段或路径不视为已开放；后续增补或变更须发布 API 新版本。除 GET/HEAD 外，凡创建记录或触发状态变化的请求都应遵守第 7 节幂等要求。

### 4.1 登录与通用查询

| 方法与路径 | 权限 | 主要请求/响应 | 说明 |
|---|---|---|---|
| `POST /api/auth/login` | 未认证 | 请求：登录标识、密码；响应：服务端会话结果及角色化用户概要 | 错误不得区分可枚举的账号细节。 |
| `POST /api/auth/logout` | 已认证 | 无业务字段；响应：登出结果 | 使服务端 Session 失效并清理会话 Cookie。 |
| `GET /api/auth/me` | 已认证 | 响应：当前账号、角色、账号状态、有限授权概要 | 不返回密码哈希、临时凭证明文或不必要的全量授权数据。 |
| `POST /api/auth/password-change` | 已认证/首次改密 | 请求：当前或临时凭证、新密码；响应：完成状态 | 各角色临时凭证不设到期时间，但只能成功消费一次；首次登录强制改密。管理员凭证随机生成、仅展示一次并线下交付。 |
| `GET /api/operations/{operationId}` | 发起者本人或有范围权限的管理员 | 响应：操作状态、逐项结果摘要和可否重试 | 仅查询授权范围内的业务操作；不可借此读取敏感请求原文。 |
| `GET /api/majors` | 已认证 | 查询：学院/启用状态；响应：授权可见专业目录 | 仅提供目录数据，不授予读取学生身份信息的权限。 |
| `GET /api/teachers`、`GET /api/teachers/{teacherId}` | 已认证 | 查询：batchId、majorId、degreeType、keyword（姓名/工号）、researchDirection、canApply 及分页；响应：已审核公开资料、允许类型/专业及可填报布尔值 | 不返回剩余名额数；学生的可报布尔值只按当前主体及批次计算。 |
| `GET /api/files/{fileId}/content` | 文件所有者、当前获授权导师或管理员 | 受控文件流 | 每次请求检查当前对象/字段权限并写访问审计；禁止公开永久链接。 |
| `GET /api/me/notices`、`POST /api/me/notices/{noticeId}/read` | 已认证收件人 | 分页通知/已读结果 | 由服务端校验收件人账号。 |

登录请求 JSON 字段为 `loginIdentifier`、`password`；成功响应的 `data` 包含 `authenticated` 和角色化 `user`。`GET /api/auth/me` 返回相同用户概要：`accountId`、`loginIdentifier`、`role`、`accountStatus`、`mustChangePassword`、本人身份概要及当前管理员能力/学院/批次授权摘要。身份概要仅含本人业务标识、姓名和学院；授权摘要不包含授权依据或操作者资料。

密码修改请求字段为 `currentCredential`、`newPassword`；`currentCredential` 在普通改密时是当前密码，首次改密时是一次性临时凭证。学生和导师新建账号的初始密码分别取学号/工号末尾六位，编号不足六位时使用完整编号；管理员账号创建/重置仍使用随机临时凭证。所有临时凭证均按 BCrypt 安全哈希保存，正式密码最多 72 个 UTF-8 字节，避免 BCrypt 对长输入截断；不额外设置未确认的字符组成规则。首次改密成功后临时凭证在同一事务内标记已使用、密码更新并清除强制改密标记。

浏览器使用 `XSRF-TOKEN` Cookie 和 `X-XSRF-TOKEN` 请求头完成 CSRF 校验。页面启动时的 `GET /api/auth/me` 会建立 CSRF Cookie；成功登录和改密后服务端轮换该令牌。会话 Cookie 使用 `HttpOnly`、`Secure`、`SameSite=Lax`；部署在 HTTPS 环境时必须启用 `Secure`。

### 4.2 学生端

| 方法与路径 | 权限 | 主要请求/响应 | 关键限制 |
|---|---|---|---|
| `GET /api/students/me` | STUDENT 本人 | 当前身份分类、资料、账号状态 | 专业/学位类型只读。 |
| `GET /api/students/me/batches` | STUDENT 本人 | 分页返回本人可参与的已发布批次及本人参与过的历史批次摘要 | 不接受 studentId；不返回草稿批次或他人数据。 |
| `PATCH /api/students/me/profile` | STUDENT 本人 | 可编辑简介、联系方式及资料版本 | 只接受白名单字段；必须携带 `If-Match`。 |
| `PUT /api/students/me/resume` | STUDENT 本人 | PDF 文件；响应文件元数据和版本 ID | 不允许覆盖历史版本；扫描通过后才可用，使用私有存储；具体存储介质部署前核定。 |
| `POST /api/students/me/identity-correction-requests` | STUDENT 本人 | 申请更正字段及说明；响应申请 ID/状态 | 不直接修改学生专业或学位类型。 |
| `GET /api/students/me/identity-correction-requests` | STUDENT 本人 | 分页返回本人身份更正申请及处理状态 | 仅返回学生本人提交字段和处理结果，不返回处理人账号资料。 |
| `POST /api/students/me/batches/{batchId}/identity-confirmation` | STUDENT 本人 | 请求：确认的分类版本；响应：确认记录/是否可进入填报 | 仅确认当前展示版本；分类变化后旧确认失效。 |
| `GET /api/students/me/batches/{batchId}/preferences` | STUDENT 本人 | 当前锁定/提交状态和当前版本 | 只看本人数据。 |
| `GET /api/students/me/batches/{batchId}/preference-submissions` | STUDENT 本人 | 分页历史版本 | 版本只增不删。 |
| `POST /api/students/me/batches/{batchId}/preferences` | STUDENT 本人 | 完整有序志愿项；响应新版本号和状态 | 填报窗口内提交 1–3 项；一次请求为完整版本。 |
| `POST /api/students/me/batches/{batchId}/preferences/withdraw` | STUDENT 本人 | 请求原因可选/响应撤回状态 | 仅截止前允许；不得物理删除历史版本。 |
| `GET /api/students/me/batches/{batchId}/progress` | STUDENT 本人 | 当前阶段、本人轮次状态、匹配状态 | 轮次关闭前不得泄露导师决定，只显示待处理/已处理。 |
| `POST /api/students/me/batches/{batchId}/supplement-applications` | STUDENT 本人 | 请求：导师 ID；响应申请 ID、待处理状态 | 仅窗口内 `UNMATCHED` 学生；冻结类型/专业范围和导师许可均须满足。 |
| `GET /api/students/me/batches/{batchId}/supplement-applications` | STUDENT 本人 | 本人补选历史 | 当前最多一条待处理由数据库唯一槽位保证。 |

### 4.3 导师端

| 方法与路径 | 权限 | 主要请求/响应 | 关键限制 |
|---|---|---|---|
| `GET /api/teachers/me/profile`、`PATCH /api/teachers/me/profile` | TEACHER 本人 | 资料当前版本/可编辑字段和提交结果 | 公开新稿须审核后才进入目录。 |
| `GET /api/teachers/me/batches/{batchId}/application-scope` | TEACHER 本人 | 当前范围版本、类型、专业、冻结状态 | 不包含无关导师配置。 |
| `PUT /api/teachers/me/batches/{batchId}/application-scope` | TEACHER 本人 | 完整范围；响应范围版本 | 填报开始前可改；空/缺省范围按已确认规则默认全选；冻结后拒绝。 |
| `GET /api/teachers/me/application-scopes/batches` | TEACHER 本人 | 本导师名额所关联批次的范围配置选项 | 仅返回本人导师身份关联批次、学院、状态和填报开始时间；历史批次只读，不得用于查询其他导师名额。 |
| `GET /api/teachers/me/batches/{batchId}/rounds/{roundNo}/applications` | TEACHER 本人 | 分页当前轮申请队列及授权快照字段 | 仅真实投向本人且当前轮可办理的记录；不返回其他志愿和敏感字段。 |
| `POST /api/teachers/me/batches/{batchId}/round-applications/{applicationId}/decision` | TEACHER 本人 | 请求：`ADMIT` 或 `NOT_ADMITTED`；响应处理结果/关系 ID | 仅当前轮待处理申请；不要求导师填写不录取原因。 |
| `POST /api/teachers/me/batches/{batchId}/rounds/{roundNo}/decisions:batch` | TEACHER 本人 | 最多 500 个申请 ID 和逐项决定；响应操作 ID | 按最终锁定志愿提交时间、再按学号排序，不按客户端数组顺序录取；以 `202` 接收并逐项处理。 |
| `GET /api/teachers/me/batches/{batchId}/supplement-applications` | TEACHER 本人 | 分页本人补选申请 | 只含已获准参加的窗口及当前处理范围。 |
| `POST /api/teachers/me/batches/{batchId}/supplement-applications/{applicationId}/decision` | TEACHER 本人 | 请求：`ADMIT` 或 `NOT_ADMITTED`；响应处理结果 | 再校验学生状态、冻结范围、导师许可和剩余名额；不录取后申请状态记为 `REJECTED`。 |
| `POST /api/teachers/me/batches/{batchId}/supplement-decisions:batch` | TEACHER 本人 | 最多 500 个申请 ID 和决定；响应操作 ID | 补选按申请提交时间、再按学号排序；以 `202` 接收并逐项处理。 |
| `POST /api/teachers/me/batches/{batchId}/application-notices` | TEACHER 本人 | 请求：消息内容和申请引用；响应通知 ID/收件人数 | 收件范围由服务端从当前投给该导师的申请确定，不接受任意账号列表。 |
| `GET /api/teachers/me/batches/{batchId}/summary` | TEACHER 本人 | 本人关系和名额汇总 | 不扩大到其他导师数据；名额修改不由导师端提供。 |

### 4.4 管理端

| 方法与路径 | 权限 | 主要请求/响应 | 关键限制 |
|---|---|---|---|
| `GET /api/admin/personnel/colleges`、`GET /api/admin/personnel/academic-years?collegeId={id}` | ADMIN + `COLLEGE_ADMIN`（总管理员隐含） | 当前授权学院和学年目录 | 普通管理员只返回已授权范围；总管理员的学院目录包含全部启用学院。 |
| `GET /api/admin/students?collegeId={id}&pageNo=1&pageSize=20&identifier={studentNo}`、`GET /api/admin/teachers?...` | ADMIN + `COLLEGE_ADMIN` | 分页人员概要；可按学号/工号精确查询 | 只返回当前学院、必要身份字段，不返回联系方式/凭证。 |
| `POST /api/admin/students`、`POST /api/admin/teachers` | ADMIN + `COLLEGE_ADMIN` | 创建账号与初始档案；响应含一次性初始密码 | 学生/导师初始密码分别取学号/工号末尾六位，较短编号完整使用；不设到期时间、仅首次成功响应展示、首次登录强制改密；必须带 `Idempotency-Key`。 |
| `GET /api/admin/majors?collegeId={id}`、`POST /api/admin/majors`、`PATCH /api/admin/majors/{majorId}` | ADMIN + `COLLEGE_ADMIN` | 查询、新建、修改/停用专业目录 | 专业按学院和代码唯一；已被人员档案引用的专业不物理删除。 |
| `POST /api/admin/annual-eligibilities`、`GET /api/admin/annual-eligibilities?...` | ADMIN + `COLLEGE_ADMIN` | 更新资格并查询当前/历史记录 | 更新追加历史；同一人员每学年最多一条当前资格。状态 `ELIGIBLE` / `INELIGIBLE`。 |
| `GET /api/admin/personnel-imports/template?collegeId={id}&personType=STUDENT\|TEACHER` | ADMIN + `COLLEGE_ADMIN` | 下载 UTF-8 CSV 模板 | 学生/导师列头按固定模板版本区分。 |
| `POST /api/admin/personnel-imports`、`GET /api/admin/personnel-imports/{importId}` | ADMIN + `COLLEGE_ADMIN` | 上传 CSV/XLSX、任务概要及首次逐行结果 | 文件 ≤10 MB；每次最多 2000 数据行；表头错误拒绝整份，行错误不中断其他行。 |
| `GET /api/admin/personnel-imports/{importId}/rows` | ADMIN + `COLLEGE_ADMIN` | 历史逐行结果/错误定位 | 按任务学院范围检查；查询响应永不返回已展示凭证。 |
| `PATCH /api/admin/students/{studentId}/classification` | ADMIN 授权范围 | 专业、学位类型、变更依据及预期版本 | 追加分类修订；填报开始后的影响由身份纠错用例联动处理。 |
| `GET /api/admin/identity-correction-requests?collegeId={id}&status=PENDING&pageNo=1&pageSize=20`、`POST /api/admin/identity-correction-requests/{requestId}/decision` | ADMIN + `COLLEGE_ADMIN`（总管理员隐含） | 学生身份更正队列/批准或驳回、处理意见 | 只可访问授权学院；批准追加分类修订并按 TODO-48 同步受影响批次；TODO-49 边界不扩展。决策需 `Idempotency-Key`。 |
| `GET /api/admin/teacher-profile-versions?collegeId={id}&status=PENDING_REVIEW&pageNo=1&pageSize=20`、`GET /api/admin/teacher-profile-versions/{versionId}` | ADMIN + `COLLEGE_ADMIN`（总管理员隐含） | 授权学院待审队列和单版本详情 | 返回导师概要、提交字段、版本号、提交时间和已有公开版本；不返回联系方式等无关字段。 |
| `POST /api/admin/teacher-profile-versions/{versionId}/review` | ADMIN + `COLLEGE_ADMIN`（总管理员隐含） | `decision=APPROVE\|REJECT`、驳回意见及版本 ETag | 通过后切换导师当前公开版本；驳回保留既有公开版本；需 `If-Match` 和 `Idempotency-Key`。不审核导师可报范围。 |
| `GET /api/admin/selection-batches/colleges`、`GET /api/admin/selection-batches/academic-years?collegeId={id}` | ADMIN + `BATCH_MANAGER` 或 `BATCH_AUDIT`（总管理员隐含） | 授权学院与学年目录；学院项含 `canCreateBatch` | 指定批次授权仅列对应学院且 `canCreateBatch=false`；不扩大到其他批次。 |
| `GET /api/admin/selection-batches?collegeId={id}` | ADMIN + `BATCH_MANAGER` 或 `BATCH_AUDIT` | 授权范围内批次概要 | 学院级授权列出该学院批次；指定批次授权只返回获授批次。 |
| `POST /api/admin/selection-batches`、`PATCH /api/admin/selection-batches/{batchId}` | ADMIN + `BATCH_MANAGER`（总管理员隐含） | 草稿创建、草稿字段修改 | 创建要求学院级授权；指定批次授权不可创建新批次。 |
| `GET /api/admin/selection-batches/{batchId}`、`GET .../{batchId}/majors`、`GET .../{batchId}/teachers` | ADMIN + `BATCH_MANAGER` 或 `BATCH_AUDIT` | 批次详情、启用专业、符合条件导师/名额/范围配置状态 | 仅暴露授权批次范围内数据；`BATCH_AUDIT` 只读；导师范围本身由导师维护。 |
| `POST /api/admin/selection-batches/{batchId}/publish` | ADMIN 授权范围 | 发布命令；响应批次状态/运行槽位结果 | 先校验全部必需配置和补选计划，再原子占用同学院同学年槽位。 |
| `POST /api/admin/selection-batches/{batchId}/start`、`pause`、`resume`、`cancel`、`archive`、`unarchive` | ADMIN + `BATCH_MANAGER`（总管理员隐含） | 各自明确的状态命令 | 均携带 `Idempotency-Key`；服务端按状态机检查前置条件；不能用 PATCH 任意设置状态。 |
| `PUT /api/admin/selection-batches/{batchId}/schedule` | ADMIN + `BATCH_MANAGER` | 阶段/补选完整计划及 `If-Match` | 仅允许规则指定时点调整；记录时间修订；不得重开已关闭补选。 |
| `PUT /api/admin/selection-batches/{batchId}/teachers/{teacherId}/quota` | ADMIN + `BATCH_MANAGER` | 名额上限及 `If-Match` | 不得低于已锁定人数；并发变更需版本/条件更新，且须带 `Idempotency-Key`。 |
| `PUT /api/admin/selection-batches/{batchId}/supplement-teachers` | ADMIN + `BATCH_MANAGER`（总管理员隐含） | 完整 `teacherIds`、变更原因及当前批次 ETag | 仅补选窗口关闭前调整；只影响新申请，既有待处理申请和锁定关系不变；需 `If-Match` 和 `Idempotency-Key`。 |
| `POST /api/admin/selection-batches/{batchId}/rounds/{roundNo}/extend`、`POST .../{roundNo}/reopen` | ADMIN + `BATCH_MANAGER`（总管理员隐含） | `{newEndAt, reason}` | 使用批次 `If-Match` 和 `Idempotency-Key`；重开要求批次已暂停、目标轮次因截止关闭、下一轮无导师处理动作且 `newEndAt` 晚于数据库当前时间；按 BR-03/TODO-38/63 恢复自动结案申请和重排后续阶段，不回滚导师决定。 |
| `POST /api/admin/matching-relations/{relationId}/adjustments` | ADMIN + `BATCH_MANAGER`（总管理员隐含） | `adjustmentType=REVOKE\|RESTORE\|REASSIGN`、新导师（仅改派）、原因及当前关系 ETag | 按学院/批次授权范围；归档批次须先解除归档；常规改派校验目标导师的冻结范围和名额，恢复仅处理 `RELATION_REVOKED` 来源。关系、学生状态、名额、流水、历史、通知和审计同事务；TODO-49 特殊边界继续拒绝。 |
| `GET /api/admin/selection-batches/{batchId}/statistics` | ADMIN + `BATCH_MANAGER` 或 `BATCH_AUDIT` 授权范围 | 冻结分母、未匹配来源拆分、逐轮结果、补选和名额统计 | 按已确认统计口径；冻结前分母为空；不允许通过筛选改变冻结分母。 |
| `POST /api/admin/selection-batches/{batchId}/exports`、`GET /api/admin/exports/{exportId}`、`GET /api/admin/exports/{exportId}/download` | ADMIN + `COLLEGE_ADMIN` 或 `BATCH_MANAGER`（总管理员隐含；`BATCH_AUDIT` 不可导出） | 创建统计/匹配结果 CSV 任务；查询任务；受控下载 | 固定服务端字段白名单；创建和下载均重新授权并审计；文件 24 小时后失效。异步创建返回 `202`。 |
| `POST /api/admin/admin-accounts`、`POST /api/admin/admin-accounts/{accountId}/temporary-credential-reset` | 总管理员专属能力 | 创建请求：登录标识；重置请求：目标账号路径参数；响应账号概要和一次性临时凭证 | 只创建普通 ADMIN，不自动授予业务能力；临时凭证不设到期时间、只展示一次、线下交付并审计；不得邮件或短信发送；总管理员应急恢复走 TODO-24。 |
| `GET /api/admin/admin-accounts` | 总管理员专属能力 | 分页查询现有 `ADMIN` 账号；返回账号编号、登录标识、账号状态、首次改密状态和创建时间 | 包含调用者本人；不返回密码、凭证明文/哈希或人员资料；用于确认账号并选择授权或重置目标。 |
| `GET /api/admin/admin-accounts/{accountId}/authorizations` | 总管理员 | 查询目标管理员的能力授权及授权范围；支持学院、状态筛选 | 仅目标为 ADMIN 账号时返回；该只读接口也可显示初始化/应急流程设置的 `ADMIN_ACCOUNT_MANAGER` 状态。 |
| `POST /api/admin/admin-accounts/{accountId}/authorizations` | 总管理员 | 授予一项业务能力及学院/可选批次范围 | 请求带 `Idempotency-Key`；支持 `COLLEGE_ADMIN`、`BATCH_MANAGER`、`BATCH_AUDIT`，按 TODO-52/53/57 校验范围及依据；不可授予 `ADMIN_ACCOUNT_MANAGER`；`COLLEGE_ADMIN` 必须覆盖完整学院。 |
| `POST /api/admin/admin-accounts/{accountId}/authorizations/{authorizationId}/revoke` | 总管理员 | 撤销一项业务能力；请求提供撤销原因 | 请求带 `Idempotency-Key`；只允许撤销该账号、该授权记录上的普通业务能力；保留授权历史并记录撤销人、时间和审计。 |

上表列出的路径按本版本实施。学生停用、导师停用和其他未列管理接口仍未纳入本版本；实现这些接口前须补充并确认 API 新版本，不得自行推定路径、字段或扩大权限。管理员能力代码须来自服务端支持的授权目录；当前服务端目录登记 `COLLEGE_ADMIN`、`BATCH_MANAGER`、`BATCH_AUDIT`。`ADMIN_ACCOUNT_MANAGER` 是保留能力，只读可见，不属于可授予目录。

### 4.5 管理审核与身份纠错

- 身份纠错队列仅接受 `status=PENDING|APPROVED|REJECTED`，不传状态时默认 `PENDING`；按授权学院和提交时间升序分页。列表仅返回申请所需身份字段、学生说明、当前/申请分类和提交时间。
- 身份纠错决策请求：`{ "decision": "APPROVE|REJECT", "handlingComment": "..." }`。驳回必须提供非空意见；批准时意见可选。已处理申请不可重复改变结果；批准须使用申请提交时的身份分类版本，版本已变化则返回 `STATE_CONFLICT`，要求管理员按最新资料重新核对后由学生发起新申请。
- 导师审核列表以 `PENDING_REVIEW` 为默认筛选。详情响应包含导师 ID、工号、姓名、所属学院、资料版本、研究方向、简介、提交时间、当前公开版本摘要及 ETag。审核请求：`{ "decision": "APPROVE|REJECT", "comment": "..." }`。驳回意见必填，通过意见可空；请求使用 `If-Match` 与 `Idempotency-Key`。通过后原子更新审核记录和 `teacher.current_public_profile_version_id`。

### 4.6 轮次命令与补选导师名单

- 延期请求：`POST /api/admin/selection-batches/{batchId}/rounds/{roundNo}/extend`，请求体 `{ "newEndAt": "...UTC...", "reason": "..." }`，必须晚于当前截止且提交时轮次尚未关闭。阶段截止时间及所有后续阶段按既有规则顺延，写入 `schedule_revision` 与审计。
- 重开请求：`POST /api/admin/selection-batches/{batchId}/rounds/{roundNo}/reopen`，请求体 `{ "newEndAt": "...UTC...", "reason": "..." }`。批次必须处于 `PAUSED`，目标轮次因截止关闭，`newEndAt` 晚于数据库当前时间；下一轮须未开始，或已打开但当前执行周期全部仍为 `IN_REVIEW`。第三轮重开要求补选尚未开放且没有申请。只恢复仍有效的系统自动结案申请；下游无人处理申请标为 `SUPERSEDED_BY_REOPEN`，以后重新进入待处理时产生新周期与资料快照。新轮次从重开时刻起至 `newEndAt`，后续阶段按新旧截止时间差顺延；重开后剩余暂停时长在恢复批次时顺延。导师决定、名额不足结案、已锁定关系和其他来源的学生状态均不回滚。
- 两项轮次命令均需 `If-Match`（批次/阶段 ETag）及 UUID v4 `Idempotency-Key`，成功返回批次详情和受影响申请/阶段摘要。
- 补选导师名单 PUT 请求：`{ "teacherIds": [901, 917], "reason": "..." }`，完整替换当前名单，要求 `If-Match` 和 UUID v4 `Idempotency-Key`。只允许补选窗口关闭前更新；名单撤销仅阻止新申请，已提交待处理申请仍由原导师处理，已锁定关系不变。响应返回当前允许导师及名单版本。

### 4.7 关系例外与管理端导出

- 关系调整请求：`{ "adjustmentType": "REVOKE|RESTORE|REASSIGN", "newTeacherId": 917, "reason": "...", "approvalComment": "..." }`。`newTeacherId` 仅 `REASSIGN` 必填；原因必填；审批意见可选。请求使用关系 ETag 与 UUID v4 `Idempotency-Key`。匹配关系、学生批次状态、跨批次学年槽位、旧/新导师名额、名额流水、`relation_adjustment`、业务操作、事件和审计在一个事务内提交。
- 导出创建请求：`POST /api/admin/selection-batches/{batchId}/exports`，请求体 `{ "exportType": "BATCH_STATISTICS|BATCH_MATCH_RESULTS", "format": "CSV" }`。不接受客户端字段列表或筛选条件。固定字段包括统计模板的指标/轮次/数值，或匹配结果模板的姓名、学号、专业、匹配状态/原因、导师和关系来源；不含学位类型、联系方式、简介、简历或成绩。
- 导出任务响应 `202`，返回 `exportId`、`status=QUEUED|READY|FAILED|EXPIRED`、`createdAt`、`expiresAt`、`rowCount`、`failureCode`。GET 查询只返回任务元数据；下载端点逐次重验创建者或当前同范围管理员授权，过期返回 `410`，权限失效返回 `404`。CSV 使用 UTF-8 BOM；下载审计记录操作者、批次、导出类型、记录数、时间和结果。

## 5. 关键请求与响应定义

### 5.1 提交完整志愿版本

```http
POST /api/students/me/batches/2026/preferences
Idempotency-Key: 8f2c...
Content-Type: application/json
```

```json
{
  "identityClassificationVersion": 4,
  "items": [
    { "teacherId": 901, "preferenceOrder": 1 },
    { "teacherId": 917, "preferenceOrder": 2 }
  ]
}
```

学生 ID、提交时间、志愿版本号、状态和范围校验结论由服务器生成。一个请求创建一个完整新版本，不提供逐项 PATCH；数组顺序必须与 `preferenceOrder` 一致且值连续为 1…N。成功响应包含新 `submissionId`、版本号、当前状态和服务器提交时间。重复导师、缺顺位、类型/专业不符或身份版本变化时整次请求拒绝，不留下半个志愿版本。

### 5.2 导师决定单条申请

```json
{
  "decision": "ADMIT"
}
```

调用者、导师 ID、处理时间、申请当前状态和批次轮次由服务端解析/校验。`ADMIT` 的成功响应包含申请处理结果、匹配关系 ID 和名额结果；匹配、关系、名额、学生状态、事件、业务操作、审计和站内收件记录在一个数据库事务内提交。`NOT_ADMITTED` 不要求原因；请求不得携带客户端指定的 `relationStatus`、`occupiedCount` 或操作者 ID。

### 5.3 导师批量决定

```json
{
  "items": [
    { "applicationId": 2001, "decision": "ADMIT" },
    { "applicationId": 2002, "decision": "ADMIT" },
    { "applicationId": 2003, "decision": "NOT_ADMITTED" }
  ]
}
```

服务端校验整个请求的格式和重复 ID 后，按已确认排序规则排序并持久化操作及逐项清单，立即返回 HTTP `202` 和 `operationId`；客户端查询 `GET /api/operations/{operationId}` 获取进度和最终逐项结果。每项包含 `applicationId`、`executionOrder`、`itemResult`、`code`、`message`，录取成功项可返回 `relationId`。名额耗尽后的录取项按规则结案并返回相应错误码，不影响此前已成功的原子录取。超过当前 500 项上限时在创建业务操作前整单拒绝；上线压测后可通过部署配置调整上限。

每项录取使用独立原子事务、同一批量操作内顺序执行；这样允许逐项成功/失败并限制长事务。不同批量操作之间不建立全局优先级或 FIFO 队列；仅保证每个操作内部按业务键排序。不同操作竞争最后名额时，由先取得名额行锁并成功提交的操作获得，不承诺跨请求公平顺序（TODO-51）。

### 5.4 提交补选申请

```json
{
  "teacherId": 901
}
```

学生身份从认证主体获取。服务端检查窗口开放、学生为 `UNMATCHED`、不存在同学年有效关系、导师获得补选许可、分类符合冻结范围且导师当前存在可用名额，并原子占用唯一待处理槽位、创建申请及资料快照。提交申请不预扣导师名额；名额在录取事务中再次校验和扣减。

### 5.5 关系例外调整

```json
{
  "adjustmentType": "REASSIGN",
  "newTeacherId": 917,
  "reason": "身份信息更正后的关系调整",
  "approvalComment": "已核对学校正式名单"
}
```

`actorAccountId`、旧导师、旧关系状态和名额数从服务端读取。撤销、恢复、改派执行前校验授权范围、批次可操作状态和学生关系状态。恢复要求学生因 `RELATION_REVOKED` 处于 `UNMATCHED`，并重新取得原关系的学年唯一槽位及原导师名额；身份纠错导致的 `UNMATCHED` 返回 `BUSINESS_RULE_DEFERRED`。改派要求学生仍持有该 `LOCKED` 关系，目标导师存在本批次已冻结且同时覆盖学生当前专业与学位类型的范围，并有剩余名额；缺少可验证冻结范围时返回 `BUSINESS_RULE_DEFERRED`，范围不符或名额不足返回 `STATE_CONFLICT`。普通批次 `COMPLETED` 可纠错，`ARCHIVED` 须先解除归档。关系、学生状态、年度唯一槽、旧/新名额、流水、关系调整记录、业务操作、通知和审计同事务提交或回滚；不恢复学生办理或补选窗口。

### 5.6 管理员业务能力授权与撤销

查询：

```http
GET /api/admin/admin-accounts/7301/authorizations?collegeId=1&status=ACTIVE
```

`collegeId` 为必填范围筛选；`status` 可取 `ACTIVE`、`REVOKED` 或 `ALL`，省略时默认为 `ACTIVE`。响应字段包括 `authorizationId`、`capabilityCode`、`collegeId`、`batchId`、`basis`、`grantedBy`、`grantedAt`、`status`、`revokedBy`、`revokedAt` 和 `revocationReason`；有效授权的撤销字段为空，已撤销授权的理由从对应审计事件读取。仅总管理员可调用，且目标账号必须是 ADMIN；总管理员可查询任一启用学院。若目标账号持有 `ADMIN_ACCOUNT_MANAGER`，该接口可只读展示其状态，但不能据此修改该能力。

授予：

```http
POST /api/admin/admin-accounts/7301/authorizations
Idempotency-Key: 15c0...
Content-Type: application/json
```

```json
{
  "capabilityCode": "BATCH_AUDIT",
  "collegeId": 1,
  "batchId": 2026,
  "basis": "学院授权审批记录 AUTH-2026-018"
}
```

`batchId` 可省略，表示该能力覆盖指定学院内所有符合其业务定义的批次；提供时服务端检查批次属于同一学院。`capabilityCode` 必须属于服务端支持的业务能力目录（当前为 `COLLEGE_ADMIN`、`BATCH_MANAGER`、`BATCH_AUDIT`）；`COLLEGE_ADMIN` 必须省略 `batchId`，覆盖整个学院；`BATCH_MANAGER` 和 `BATCH_AUDIT` 可按能力含义限定至单批次；`basis` 必须填写。`grantedBy`、`grantedAt`、授权状态及审计主体由服务端生成。不能授予 `ADMIN_ACCOUNT_MANAGER`；目标账号保持 `ADMIN` 角色，权限仅在请求声明的数据范围内生效。

授予成功的 `data` 为 `{"authorizationId": 8802, "result": "GRANTED"}`。撤销成功的 `data` 为 `{"authorizationId": 8802, "result": "REVOKED"}`；同幂等键重放时返回相同回执。

撤销：

```http
POST /api/admin/admin-accounts/7301/authorizations/8802/revoke
Idempotency-Key: 50ad...
Content-Type: application/json
```

```json
{
  "reason": "原授权期限届满"
}
```

撤销原因必填。服务端确认授权记录属于路径中的目标账号、处于有效状态且不是 `ADMIN_ACCOUNT_MANAGER`，然后追加撤销信息 `revokedBy`、`revokedAt` 并记录审计；不物理删除授权历史。授权变更在事务提交后立即影响该账号的后续请求，认证过滤器/授权服务必须读取已提交的最新授权状态。重复授予有效的同一能力与同一范围、或再次撤销已撤销记录，返回 `STATE_CONFLICT`；带相同幂等键和相同请求摘要的重试返回原操作结果。

### 5.7 管理员账号创建与临时凭证重置

创建普通管理员：

```http
POST /api/admin/admin-accounts
Idempotency-Key: 4b33...
Content-Type: application/json
```

```json
{
  "loginIdentifier": "admin-2026-02"
}
```

服务端创建 `ACTIVE` 的 `ADMIN` 账号，设置 `mustChangePassword=true`，不设置正式密码，也不自动授予任何业务能力。登录标识在全部角色账号间唯一；已有标识返回 `STATE_CONFLICT`。普通业务能力随后通过本节前述授权接口单独配置。

创建成功的 `data` 包含 `accountId`、`loginIdentifier`、`result: "CREATED"`、`temporaryCredential`、`expiresAt` 和 `credentialShownNow`。临时凭证由安全随机源生成，不设到期时间，故 `expiresAt` 为 `null`；账号记录中只存安全哈希，首次成功使用后必须改密。明文仅在首次成功响应中返回，管理员须线下转交，不通过邮件或短信发送。

重置其他普通管理员的临时凭证：

```http
POST /api/admin/admin-accounts/7301/temporary-credential-reset
Idempotency-Key: 193e...
```

服务端锁定目标账号，确认其为普通 `ADMIN` 后，撤销其未消费的旧临时凭证，清除正式密码并将 `mustChangePassword` 设为 `true`，然后签发不设到期时间的新凭证（`expiresAt: null`）。账号版本递增使其既有 Session 失效。不能通过普通接口重置总管理员本人；总管理员应急恢复按 TODO-24 线下核验、双人复核和留痕。

创建/重置、凭证生成及一次性展示预留分别写入关联操作与审计；审计不保存凭证明文或哈希。重复的同幂等键和同请求返回原账号回执，但 `temporaryCredential` 为 `null`、`credentialShownNow` 为 `false`，不会再次展示秘密；如首次响应未能保存，须发起一次新的重置命令签发新凭证。同键不同请求返回 `IDEMPOTENCY_KEY_REUSED`。账号创建与权限授予分开操作，便于先核对账号后再设置最小业务范围。

### 5.8 管理员账号目录

```http
GET /api/admin/admin-accounts?pageNo=1&pageSize=20
```

仅持有当前有效 `ADMIN_ACCOUNT_MANAGER` 能力的管理员可查询。响应使用通用分页结构：

```json
{
  "items": [
    {
      "accountId": 7301,
      "loginIdentifier": "admin-2026-02",
      "accountStatus": "ACTIVE",
      "mustChangePassword": false,
      "createdAt": "2026-10-03T08:00:00.000Z"
    }
  ],
  "total": 2,
  "pageNo": 1,
  "pageSize": 20
}
```

默认 `pageNo=1`、`pageSize=20`，`pageSize` 范围为 1–100。只列出 `role_code='ADMIN'` 的账号，包含调用者本人；按 `created_at` 倒序、`id` 倒序稳定排序。`accountStatus` 返回账号状态，`mustChangePassword` 表示首次登录或凭证重置后尚须改密。接口只返回账号目录所需的最小信息，不含密码哈希、临时凭证明文/哈希、联系方式或人员资料。总管理员可从管理端小窗将其他管理员选为授权或凭证重置目标；本人账号仅展示，不开放自我授权或常规凭证重置。

### 5.9 人员、专业、年度资格与名单导入

学院人员管理 API 均要求当前 `ADMIN` 主体具有 `COLLEGE_ADMIN`，且请求中的 `collegeId` 与该能力绑定学院一致。此能力只能覆盖完整学院；`BATCH_MANAGER`、`BATCH_AUDIT` 等批次授权不能替代它。服务端每次请求都从数据库刷新当前账号和授权，不信任页面隐藏状态或会话旧授权摘要。

学生创建请求：

```json
{
  "loginIdentifier": "20260001",
  "studentNo": "20260001",
  "fullName": "张同学",
  "collegeId": 1,
  "majorCode": "CS-01",
  "degreeType": "ACADEMIC_MASTER",
  "enrollmentYearCode": "2026",
  "classificationBasis": "2026 级录取名单",
  "classificationReason": "导入初始学生身份分类"
}
```

`loginIdentifier` 必须与 `studentNo` 相同；登录名由学号确定，不能为同一学生另设不同登录标识。`degreeType` 仅接受 `ACADEMIC_MASTER`（学硕）或 `PROFESSIONAL_MASTER`（专硕）。服务端创建 `STUDENT` 账号、初始资料版本和 `classification_version=1` 的分类修订记录；专业必须是同学院启用目录项。响应 `credential.temporaryCredential` 返回学号末尾六位作为初始密码，学号不足六位时返回完整学号；凭证不设到期时间、仅能成功使用一次、仅首次响应展示且首次登录强制改密，重复同幂等键不会再次给出明文。

导师创建请求包含 `loginIdentifier`、`employeeNo`、`fullName`、`collegeId`，且 `loginIdentifier` 必须等于 `employeeNo`。服务端创建 `TEACHER` 账号、基本导师档案及空白 `DRAFT` 资料版本；响应 `credential.temporaryCredential` 返回工号末尾六位作为初始密码，工号不足六位时返回完整工号；凭证不设到期时间、仅能成功使用一次、仅首次响应展示和首次登录强制改密。导师须自行完善资料，审核通过前不对学生公开。学生与导师列表仅包含其登录标识、学号/工号、姓名、学院和必要档案概要。

专业新增请求字段：`collegeId`、`majorCode`、`name`、可选 `validFrom`/`validTo`（`YYYY-MM-DD`）和必填 `changeBasis`。修改请求字段：`name`、`active`、可选有效期、`changeBasis`。专业代码创建后不可修改；停用替代物理删除，保留现有人员与历史批次引用。

年度资格命令字段为 `personType`（`STUDENT`/`TEACHER`）、`personId`、`academicYearId`、`collegeId`、`eligibilityStatus`（`ELIGIBLE`/`INELIGIBLE`）、`evidenceType`、可选 `evidenceReference` 和 `sourceName`。服务端锁定人员行，校验人员学院后结束旧当前记录、追加新历史行并切换唯一当前资格槽位。当前资格查询默认只返回 `validTo=null` 的行；`history=true` 返回所有版本。状态更新、依据、来源、修改人和有效期均保留。

固定模板为 UTF-8 CSV；Excel 用户可直接打开并另存为 XLSX。模板版本为 `1.0`，表头和列顺序固定：

```text
学生：loginIdentifier,studentNo,fullName,majorCode,degreeType,enrollmentYearCode,eligibilityStatus,evidenceType,evidenceReference
导师：loginIdentifier,employeeNo,fullName,eligibilityStatus,evidenceType,evidenceReference
```

模板中的 `loginIdentifier` 与对应学生学号/导师工号必须相同；系统保留两列是为了让登录标识在导入结果中清晰可见，二者不一致的行会拒绝创建。

学年和学院由上传请求参数指定，不可从表格扩展范围。上传接受 `.csv`、`.xlsx`，最大 10 MB、最多 2000 条数据行。表头不完全匹配时拒绝整份文件且不创建人员；通过表头校验后按物理行号逐行处理。每个成功行的账号、人员资料、学生初始分类（学生）和年度资格在一个独立事务提交；同一文件其他行失败不回滚已成功行。每行返回行号、学号/工号、状态、错误码/说明、人员 ID、资格 ID 和首次创建的登录标识/初始密码（分别取学号/工号末尾六位，较短编号完整使用）。凭证不设到期时间、仅能成功使用一次、首次登录强制改密，明文仅在创建成功的首次响应返回；导入源文件以随机存储键写入 Web 根目录外私有目录，并在 `managed_file` 保存文件摘要；历史导入行不保存密码或临时凭证明文。重复上传的同幂等键返回原任务和行状态，所有凭证字段为空。

### 5.10 批次管理、导师名额与招生范围

`BATCH_MANAGER` 可获授学院级或指定批次范围。学院级授权允许创建批次；仅有指定批次授权的管理员只可读取和维护获授批次。总管理员隐含拥有该能力。批次目录/学年查询须按授权范围过滤，学院选项中的 `canCreateBatch` 仅用于页面呈现，不代替服务端授权。

创建草稿：

```http
POST /api/admin/selection-batches
Idempotency-Key: 8f2c...
Content-Type: application/json
```

```json
{
  "collegeId": 1,
  "academicYearId": 12,
  "batchCode": "2026-01",
  "name": "2026 年秋季互选",
  "supplementPlanned": true,
  "appendReason": null
}
```

排期使用完整替换请求 `PUT /api/admin/selection-batches/{batchId}/schedule`，携带 `If-Match: "batch-{rowVersion}"`。`stages` 按 `FILLING`、`ROUND_1`、`ROUND_2`、`ROUND_3`、可选 `SUPPLEMENT` 提交，每项包含 `stageCode`、带时区的 `plannedStartAt`、`plannedEndAt`；另含必填变更原因 `reason`。时间须顺序排列且不得重叠。草稿仅在所有必需阶段排期完整、至少一位有效导师已配置名额、学院有启用专业目录时可发布。发布和启动分别调用 `POST .../{batchId}/publish` 与 `POST .../{batchId}/start`，都携带 `Idempotency-Key`；发布在事务内占用同学院同学年运行槽位。

批次状态命令分别调用 `POST .../{batchId}/pause`、`resume`、`cancel`、`archive` 和 `unarchive`，请求体为空并携带 UUID v4 `Idempotency-Key`。暂停只允许 `ACTIVE → PAUSED`；恢复只允许 `PAUSED → ACTIVE`，按数据库记录的暂停区间整体平移所有未关闭阶段和计划中/开放中的补选窗口时间，已关闭阶段不变。取消只允许 `DRAFT`、`SCHEDULED`、`ACTIVE`、`PAUSED`；取消时仍待处理的常规/补选申请转为 `CANCELLED_BY_BATCH`、释放补选待处理槽位、将未匹配参与者记录为 `UNMATCHED` 且原因 `BATCH_CANCELLED`，已锁定关系和名额不变，并释放学院/学年运行槽位。归档仅允许 `COMPLETED → ARCHIVED`，解除归档仅允许 `ARCHIVED → COMPLETED`。每项状态命令都在事务中写入业务操作、生命周期事件和审计；审计原因使用服务端固定动作说明，不接受客户端任意设置状态或原因。成功响应返回批次详情及新的强 ETag。

导师名额通过 `PUT /api/admin/selection-batches/{batchId}/teachers/{teacherId}/quota` 设置，携带 `If-Match: "quota-{rowVersion}"` 和 `Idempotency-Key`，请求体为 `{"quotaLimit": 5}`。尚未配置的名额版本为 `0`。不得将名额调低至已锁定关系人数以下；成功后响应导师、名额上限、已占用数、剩余数、版本和招生范围配置/冻结状态。`BATCH_AUDIT` 可读取批次、专业、导师名额、配置状态和统计，但不能改名额、排期或生命周期状态。

统计响应使用批次范围内冻结名单作为分母，冻结前 `frozenDenominator` 为 `null`。响应分别给出当前 `MATCHED` / `UNMATCHED`、正常志愿流程未匹配（仅 `ROUND3_EXHAUSTED` 与 `PREFERENCE_EXHAUSTED`）、未提交、所有剩余志愿被时间跳过、身份纠错、关系撤销、批次取消、补选录取及补选关闭后仍未匹配人数；并按第一至第三轮返回阶段状态、待处理、录取、不录取、按时间跳过和批次取消数量，以及总名额、已占用和剩余名额。补选尚未关闭时 `supplementStillUnmatchedCount` 为 `null`。名单资格/当前账号状态变化不回算冻结分母。

导师本人先查询 `GET /api/teachers/me/application-scopes/batches`，再读取 `GET /api/teachers/me/batches/{batchId}/application-scope`，并用 `PUT` 完整替换招生范围。更新必须携带 `If-Match: "scope-{versionNo}"`，请求体包含 `allowedDegreeTypes`（`ACADEMIC_MASTER`、`PROFESSIONAL_MASTER`）与 `majorIds`。任一集合为空或缺省时服务端按已确认规则默认全选；有效配置于学生填报窗口开始时冻结。范围版本、招生名单冻结在同一批次启动/定时开窗事务内完成；冻结后普通更新拒绝。批次或导师无权访问的资源不泄露其存在性。

批次详情的强 ETag 格式为 `"batch-{rowVersion}"`，名额为 `"quota-{rowVersion}"`，导师范围为 `"scope-{versionNo}"`。缺少 `If-Match` 返回 `428 PRECONDITION_REQUIRED`，过期返回 `412 PRECONDITION_FAILED`；弱 ETag 不接受。

### 5.11 学生批次摘要与身份更正申请查询

#### 查询本人可参与批次

GET /api/students/me/batches?pageNo=1&pageSize=20

响应使用通用分页结构。items 只包含当前学生组织、年度资格匹配的已发布批次，以及其已有参与记录的历史批次；不返回 DRAFT 批次、与该学生无关的批次或其他学生信息。查询范围由认证主体确定，不接受 studentId。默认排序将当前可办理/等待中的批次置前，其余按学年倒序。

每项摘要字段：

| 字段 | 类型 / 取值 | 说明 |
|---|---|---|
| batchId、batchName | number、string | 批次标识和显示名称。 |
| academicYearId、academicYearName | number、string | 批次所属学年标识和显示名称。 |
| batchStatus | SCHEDULED / ACTIVE / PAUSED / COMPLETED / ARCHIVED / CANCELLED | 批次生命周期状态。 |
| supplementPlanned | boolean | 发布时是否安排独立补选窗口；仅为计划标记，办理权限仍以 actions 和写接口校验为准。 |
| currentStage | object 或 null | 当前/最近阶段；含 stageCode（FILLING / ROUND_1 / ROUND_2 / ROUND_3 / SUPPLEMENT）、stageStatus（NOT_STARTED / WAITING_FILLING / OPEN / PROCESSING / CLOSED）、startAt、endAt。时间为 RFC 3339。无阶段时为 null。 |
| preferenceStatus | NOT_SUBMITTED / SUBMITTED / LOCKED / WITHDRAWN | 本批次志愿状态；撤回后、重新提交前为 WITHDRAWN。 |
| matchStatus | PENDING_ROUND_1 / PENDING_ROUND_2 / PENDING_ROUND_3 / MATCHED / UNMATCHED 或 null | 本批次匹配状态；尚无批次参与记录时为 null。 |
| matchReason | string 或 null | 当前未匹配原因；不是 UNMATCHED 时为 null。 |
| identityClassificationVersion、confirmedClassificationVersion | number 或 null | 当前身份分类版本与本批次已确认版本。 |
| actions | object | 当前主体可办理状态提示：canConfirmIdentity、canSubmitPreferences、canWithdrawPreferences、canApplySupplement。 |

actions 只用于界面展示按钮状态，不构成授权或并发保证。每次写请求仍由服务端重新检查当前账号、资格、批次/阶段状态、身份版本、导师范围和数据库时间；暂停、截止、关系变化等情况可能使此前为 true 的提示失效。已完成/已归档历史批次仍可用于只读查询本人结果，不能据此办理业务。

#### 查询本人身份更正申请

GET /api/students/me/identity-correction-requests?pageNo=1&pageSize=20

响应使用通用分页结构，按 submittedAt 倒序、requestId 倒序稳定排序。每项包含 requestId、submittedAt、currentClassificationVersion、requestedMajor（majorId/majorCode/majorName 或 null）、requestedDegreeType（ACADEMIC_MASTER / PROFESSIONAL_MASTER 或 null）、studentExplanation、status、handledAt、handlingComment 和 resultingClassificationVersion。status 仅为 PENDING、APPROVED 或 REJECTED；handledAt、handlingComment、resultingClassificationVersion 在待处理时为 null。不得返回 handledBy、管理员账号资料、内部审计字段或其他学生的申请。

创建申请仍使用 POST /api/students/me/identity-correction-requests，请求包含至少一个更正字段（requestedMajorId、requestedDegreeType）及 studentExplanation，并携带 Idempotency-Key。成功响应返回 requestId、status 和 submittedAt。申请只供管理员核验身份依据，不会直接更改当前专业或学位类型；处理结果可通过本节 GET 接口查询。

### 5.12 学生端资料、导师目录、志愿与结果响应

#### 学生本人资料

GET /api/students/me 返回本人资料 DTO：

| 字段 | 类型 / 取值 | 说明 |
|---|---|---|
| studentId、studentNo、fullName | number、string、string | 本人学生 ID、学号和姓名。 |
| collegeId、collegeName | number、string | 所属学院。 |
| major | object | majorId、majorCode、majorName。 |
| degreeType | ACADEMIC_MASTER / PROFESSIONAL_MASTER | 学位类型。 |
| classificationVersion | number | 当前专业/学位分类版本；写入确认和志愿时作为版本依据。 |
| accountStatus | string | 账号当前状态。 |
| profileVersion、profileEtag | number、string | 可编辑资料版本及对应强 ETag；profileEtag 在响应体和 ETag 响应头中一致返回，客户端将响应体值原样放入 If-Match。 |
| biography、contact | string | 学生可编辑的个人简介和联系方式。 |
| resume | object 或 null | 简历元数据：fileId、fileName、sizeBytes、uploadedAt、scanStatus。scanStatus 为 PENDING、AVAILABLE、REJECTED；仅 AVAILABLE 文件可下载。 |

PATCH /api/students/me/profile 请求体只接受 biography、contact 两个白名单字段，并必须携带 If-Match。响应返回更新后的本人资料 DTO 和新 profileVersion/profileEtag。身份字段、账号状态及服务端版本字段不得由客户端修改。未提供 If-Match 返回 PRECONDITION_REQUIRED，版本过期返回 PRECONDITION_FAILED。

PUT /api/students/me/resume 使用 multipart/form-data，文件字段名为 file；单文件为 PDF 且不超过 10 MB。请求携带 Idempotency-Key。每次成功上传创建新文件版本，不覆盖旧附件；响应为简历元数据对象，扫描中为 PENDING，扫描通过后为 AVAILABLE，未通过为 REJECTED。下载统一走 GET /api/files/{fileId}/content 并逐次授权。

身份确认 POST /api/students/me/batches/{batchId}/identity-confirmation 请求体为 classificationVersion；成功响应为 confirmedClassificationVersion、confirmedAt。服务端要求所确认版本仍是学生当前分类版本。GET /api/majors?collegeId=...&active=true 返回分页 items，每项为 majorId、majorCode、majorName、active。

#### 导师目录与详情

GET /api/teachers 必须提供 batchId；可选 keyword（姓名或工号）、researchDirection、canApply、majorId、degreeType、pageNo、pageSize。列表每项包含 teacherId、employeeNo、displayName、researchDirections、profileSummary、allowedDegreeTypes、allowedMajors 和 canApply。allowedMajors 每项为 majorId、majorCode、majorName。GET /api/teachers/{teacherId} 使用同一 batchId 查询参数，返回上述字段及 biography。

目录仅展示审核通过的公开导师资料及该批次允许的招生范围。canApply 是按当前学生身份、批次窗口、导师许可和当前业务状态即时计算的展示提示，不是授权凭证；不得返回导师剩余名额数量。提交常规志愿或补选时，服务端重新校验全部条件。目录与详情仅返回学生端获准查看的导师公开字段。

#### 当前志愿与志愿历史

GET /api/students/me/batches/{batchId}/preferences 返回 preferenceStatus、submissionId、versionNo、submittedAt、lockedAt 和 items。状态为 NOT_SUBMITTED、SUBMITTED、LOCKED 或 WITHDRAWN；无当前有效志愿时 submissionId、versionNo、submittedAt、lockedAt 为空且 items 为空。每个 item 包含 teacherId、preferenceOrder、teacherName、employeeNo、researchDirections。

GET /api/students/me/batches/{batchId}/preference-submissions 使用通用分页结构，按 versionNo 倒序。每项包含 submissionId、versionNo、status、submittedAt、lockedAt 和完整 items；status 为 SUBMITTED、WITHDRAWN 或 LOCKED。历史版本只读，不提供物理删除或修改接口。

POST /api/students/me/batches/{batchId}/preferences 请求体为 identityClassificationVersion 和 items；每项仅含 teacherId、preferenceOrder。成功响应包含 submissionId、versionNo、preferenceStatus、submittedAt。撤回接口 POST /api/students/me/batches/{batchId}/preferences/withdraw 可接受可选 reason；成功响应包含 preferenceStatus。提交和撤回均需 Idempotency-Key，具体状态、窗口和版本校验遵循第 5.1 节及通用并发契约。

#### 进度、轮次和匹配结果

GET /api/students/me/batches/{batchId}/progress 返回 batchStatus、currentStage、preferenceStatus、matchStatus、matchReason、rounds 和 currentRelation。rounds 按 roundNo 升序；每项包含 roundNo、preferenceOrder、teacherId、teacherName、state、resultPublished、processedAt。常规轮次 state 可为 NO_PREFERENCE、WAITING、PENDING、PROCESSED、SKIPPED_BY_SCHEDULE、ADMITTED、NOT_ADMITTED 或 CANCELLED_BY_BATCH。

resultPublished=false 时，服务端不得用 ADMITTED/NOT_ADMITTED 暴露导师决定；处理中的学生只见 PENDING 或 PROCESSED。轮次结果发布后才返回该轮最终录取结论。currentRelation 无关系时为 null；有关系时包含 teacherId、teacherName、source（ROUND_1 / ROUND_2 / ROUND_3 / SUPPLEMENT）和 lockedAt。匹配状态和原因使用已确认状态机中的取值；查询只返回当前学生本人数据。

#### 补选记录与站内通知

GET /api/students/me/batches/{batchId}/supplement-applications 返回本人申请的分页历史，按 submittedAt 倒序。每项包含 applicationId、teacherId、teacherName、submittedAt、status、processedAt；status 为 IN_REVIEW、ADMITTED、REJECTED 或 CANCELLED_BY_BATCH。补选拒绝后的状态为 REJECTED，学生仍为 UNMATCHED，窗口开放时可再次申请其他合格导师。

POST /api/students/me/batches/{batchId}/supplement-applications 请求体只含 teacherId，携带 Idempotency-Key；成功响应含 applicationId、teacherId、teacherName、submittedAt、status（IN_REVIEW）和 processedAt（null）。接口不得接受学生 ID、志愿顺位或导师名额字段。

GET /api/me/notices 返回通用分页 items，每项包含 noticeId、title、content、sentAt、readAt；未读时 readAt 为 null。POST /api/me/notices/{noticeId}/read 成功响应包含 noticeId、readAt。收件人始终从已认证账号解析，只能读取和更新本人的通知。

### 5.13 导师工作台响应字段

`GET /api/teachers/me/profile` 返回本人标识、已公开资料、最新提交资料、审核状态/意见、资料版本号和强 ETag；PATCH 只接受 `researchDirections`、`biography`，必须把响应体中的 `etag` 原样放入 `If-Match`。资料修改不另要求幂等键。待审核稿不替换学生目录中的已公开版本。

常规申请分页每项字段为 `applicationId`、`batchId`、`batchName`、`roundNo`、`preferenceOrder`、`studentNo`、`fullName`、`majorName`、`degreeType`、`biography`、`resumeFileId`、`status`、`enteredReviewAt`、`processedAt`。补选分页每项字段为 `applicationId`、`batchId`、`batchName`、`studentNo`、`fullName`、`majorName`、`degreeType`、`biography`、`resumeFileId`、`status`、`submittedAt`、`processedAt`。两类资料只来自申请时快照，不含学生联系方式或成绩；简历通过本节 4.1 的受控文件接口读取。

单项决定响应字段为 `applicationId`、`status`、`relationId`、`quotaLimit`、`occupiedCount`、`remainingCount`、`processedAt`。不录取时 `relationId` 为空。补选不录取的申请状态为 `REJECTED`，常规申请状态为 `NOT_ADMITTED`。

批量决定端点立即返回 `202`，回执包含 `operationId` 和 `status`。后台按静态授权时已确定的稳定顺序逐项处理；状态可为 `PROCESSING`、`COMPLETED` 或 `FAILED`。`GET /api/operations/{operationId}` 的导师批量结果含 `items`，每项含 `applicationId`、`executionOrder`、`itemResult`、`code`、`message`、`relationId`。权限不足或账号停用后，尚未处理的项目失败；已提交项目不回滚。

批次汇总字段为 `batchId`、`batchName`、`batchStatus`、`currentStage`、`quotaLimit`、`occupiedCount`、`remainingCount` 和 `matchedStudents`。锁定关系项仅包含 `relationId`、`studentNo`、`fullName`、`majorName`、`degreeType`、`source`、`lockedAt`。申请消息请求字段为 `title`、`content`、`applicationReferences`；每个引用只包含 `applicationType`（`ROUND` 或 `SUPPLEMENT`）及 `applicationId`。收件人由服务端从当前导师与批次的申请记录解析，响应字段为 `noticeId`、`recipientCount`、`sentAt`。

## 6. 错误码目录（技术基线）

错误码为稳定机器可读字符串；前端按 `code` 分支，`message` 仅供展示/辅助，不能作为业务判断条件。字段错误通过 `data.fieldErrors` 给出，不在错误文本中返回 SQL、堆栈、密码或其他敏感信息。

| 错误码 | HTTP | 含义/适用场景 |
|---|---:|---|
| `INVALID_ARGUMENT` | 400 | JSON 格式、必填字段、类型或字段范围不合法。 |
| `UNAUTHENTICATED` | 401 | 未登录或凭证失效。 |
| `INVALID_CREDENTIALS` | 401 | 登录标识/密码组合无效；不区分账号是否存在。 |
| `ACCOUNT_DISABLED` | 403 | 账号已停用。 |
| `PASSWORD_CHANGE_REQUIRED` | 403 | 首次登录须先设置正式密码。 |
| `TEMP_CREDENTIAL_EXPIRED` | 401 | 临时凭证无效、已撤销或仍有历史到期状态。 |
| `TEMP_CREDENTIAL_ALREADY_USED` | 409 | 临时凭证已消费。 |
| `INTERNAL_ERROR` | 500 | 未预期服务端错误；响应仅给出通用提示，详细异常只记录在服务端。 |
| `FORBIDDEN` | 403 | 缺少端点/操作权限。 |
| `SCOPE_FORBIDDEN` | 403 | 调用者可见的学院、批次或对象上缺少授权；不应暴露存在性的对象返回 `NOT_FOUND`。 |
| `NOT_FOUND` | 404 | 资源不存在或对调用者不可见。 |
| `IDEMPOTENCY_KEY_REUSED` | 409 | 同一操作者/动作/幂等键对应不同请求摘要。 |
| `REQUEST_IN_PROGRESS` | 409 | 同一幂等操作仍在执行；响应包含 `operationId` 或查询地址。 |
| `STATE_CONFLICT` | 409 | 对象状态已变化或不允许该动作。 |
| `BUSINESS_RULE_DEFERRED` | 409 | 操作落在 TODO-49 暂缓边界，例如身份纠错来源的关系恢复或缺少已冻结导师范围的改派。 |
| `PRECONDITION_REQUIRED` | 428 | 该资源已采用必需版本前置条件，但请求未携带版本。 |
| `PRECONDITION_FAILED` | 412 | `If-Match` 所带资源版本已过期。 |
| `BATCH_NOT_OPEN` | 409 | 批次未处于此动作允许的状态/时间窗口。 |
| `BATCH_RUNNING_SLOT_OCCUPIED` | 409 | 同学院同学年已存在运行批次。 |
| `BATCH_CONFIGURATION_INCOMPLETE` | 409 | 排期、有效导师名额或专业目录等发布必需配置缺失。 |
| `BATCH_START_WINDOW_SKIPPED` | 409 | 当前时间已超过填报窗口；TODO-49 暂缓此时名单与范围冻结行为。 |
| `PREFERENCE_COUNT_INVALID` | 400 | 志愿数量不在 1–3 项内。 |
| `PREFERENCE_DUPLICATE_TEACHER` | 400 | 同一志愿版本导师重复。 |
| `IDENTITY_CONFIRMATION_REQUIRED` | 409 | 当前分类版本尚未由学生核对确认。 |
| `STUDENT_CLASSIFICATION_CHANGED` | 409 | 请求使用的身份分类版本已过期。 |
| `TEACHER_SCOPE_MISMATCH` | 409 | 学位类型或专业不在冻结范围内。 |
| `APPLICATION_NOT_PENDING` | 409 | 申请已处理、被关闭或不属于当前可处理范围。 |
| `STUDENT_ALREADY_MATCHED` | 409 | 学生已建立有效关系，不能重复录取/补选。 |
| `SUPPLEMENT_NOT_OPEN` | 409 | 补选窗口未开放或已关闭。 |
| `SUPPLEMENT_PENDING_EXISTS` | 409 | 学生已有待处理补选申请。 |
| `SUPPLEMENT_TEACHER_NOT_ALLOWED` | 409 | 导师未获准参与此补选窗口。 |
| `QUOTA_EXHAUSTED` | 409 | 导师名额已用尽或并发扣减失败。 |
| `QUOTA_BELOW_OCCUPIED` | 409 | 新名额上限低于当前锁定关系数。 |
| `REOPEN_PRECONDITION_FAILED` | 409 | 常规轮次不满足暂停、下一轮无处理动作等重开条件。 |
| `FILE_TYPE_NOT_ALLOWED` | 415 | 文件不是允许的简历格式。 |
| `FILE_TOO_LARGE` | 413 | 文件超过 10 MB 限制。 |
| `EXPORT_FIELD_FORBIDDEN` | 403 | 导出字段不在授权白名单中。 |
| `OPERATION_INCOMPLETE` | 503 | 幂等操作部分完成后暂时中断；响应提供 `operationId` 和可恢复状态。 |
| `BATCH_SIZE_LIMIT` | 400 | 批量项目超过当前端点配置上限；整单拒绝且未处理任何项目。 |
| `INTERNAL_ERROR` | 500 | 未预期服务端错误。 |

端点级错误码可以继续细分，但同一业务原因必须使用稳定编码。日志记录内部异常和操作 ID；面向调用者的消息不暴露数据库错误、约束名称或敏感对象存在性。

## 7. 幂等语义

对创建记录、录取/不录取决定、补选申请、志愿提交/撤回、身份确认、简历上传、批次状态命令、名额调整、关系调整、批量办理、导入提交、导出创建和临时凭证重置等可能被网络重试重复执行的命令，客户端必须发送 `Idempotency-Key`。只读 GET 不需要此头。普通资料 PUT/PATCH 必须使用 `If-Match` 防止覆盖并发修改，不额外强制幂等键；若某个更新端点带有独立副作用，则按具体副作用纳入幂等命令。

服务端按认证主体、动作和 `Idempotency-Key` 建立业务操作标识，并保存规范化请求摘要：

1. 首次请求校验通过后创建操作记录；操作结果、业务状态和必须的事件/审计按事务提交。
2. 同主体、同动作、同键、同请求摘要的重复请求返回原操作结果或当前操作状态，不重复录取、扣减名额、写关系或通知。
3. 同主体、同动作、同键但请求摘要不同，拒绝并返回 `IDEMPOTENCY_KEY_REUSED`。
4. 同键请求仍在处理中，返回 `REQUEST_IN_PROGRESS` 和 `operationId`；客户端通过 `GET /api/operations/{operationId}` 查询状态，不能自行创建新键绕过。
5. 批量操作可逐项恢复：已成功项目返回既有结果，未完成项目按原排序继续；同一个批量操作不会因重试重复创建关系或占用名额。

`Idempotency-Key` 使用 UUID v4。幂等标识与对应 `business_operation`/审计记录采用相同保留期限；在项目没有历史数据清理期限前不设置自动过期，避免迟到的重试再次产生业务副作用。只保留请求摘要和结果引用，不保存敏感请求原文。用户端只有在前一操作明确完成且发起了新的业务意图时才生成新键。

## 8. 并发、版本和事务契约

所有关键写入在同一个 MySQL/InnoDB 数据源事务中执行。Service 校验业务语义并组织事务；Mapper 使用条件更新、版本条件或 `SELECT ... FOR UPDATE`；唯一约束和外键作为最后保护。不得使用 Java `synchronized`、事务外“先查再写”或仅靠前端/Redis 锁保证正确性。

管理员业务能力授予与撤销须在同一事务中锁定目标账号和相关授权记录，校验目标角色、能力代码、学院/批次归属及当前授权状态，再写入或追加撤销字段与审计事件。对相同账号、能力和范围的重复有效授权返回 `STATE_CONFLICT`；所有授权写操作先锁定同一目标账号行，以数据库事务串行化检查和写入。权限读取必须反映最近一次已提交的变更，使撤销后该账号的下一次请求立即按新授权判定。`ADMIN_ACCOUNT_MANAGER` 不进入这些 API 的授权/撤销路径。

| 竞争操作 | 服务端处理 | 竞争结果 |
|---|---|---|
| 两个管理员同时发布同学院同学年批次 | 插入唯一 `batch_running_slot` 与批次状态变更同事务 | 一个成功；另一请求返回 `BATCH_RUNNING_SLOT_OCCUPIED`。 |
| 志愿提交与填报截止并发 | 同事务锁定阶段和志愿版本，锁定阶段记录后读取数据库 UTC 时间校验窗口 | 窗口采用 `[startAt,endAt)`；校验通过的事务即使在截止后提交仍有效，恰好到截止时刻的请求拒绝。客户端时间无效（TODO-50）。 |
| 学生身份在提交志愿期间更正 | 比对客户端确认版本与当前分类版本，并在写入前锁定/条件检查分类版本 | 过期确认返回 `STUDENT_CLASSIFICATION_CHANGED`；不产生半份志愿。 |
| 两位导师/重复请求同时录取同一学生 | 锁定学生/批次行；插入 `student_year_match_slot` 唯一槽位；所有录取共用事务 | 仅一个可提交；其他返回 `STUDENT_ALREADY_MATCHED` 或 `APPLICATION_NOT_PENDING`。 |
| 多个请求同时占用同导师最后名额 | 条件更新名额行（`occupied_count < quota_limit`）并处理版本/行锁；同事务写关系及名额流水 | 不超额；先取得行锁并成功提交者取得最后名额，其他请求返回 `QUOTA_EXHAUSTED`；跨批量操作不保证全局排序（TODO-51）。 |
| 学生并发提交两条补选申请 | 创建申请时竞争 `student_pending_supplement_slot` 主键 | 一条成功；另一条返回 `SUPPLEMENT_PENDING_EXISTS`。 |
| 范围编辑与填报开始同时发生 | 冻结范围和范围写入竞争同一批次/导师范围槽位及状态版本 | 冻结完成后编辑返回 `STATE_CONFLICT`；志愿和补选引用冻结版本。 |
| 管理员并发调低名额与导师录取 | 锁定名额账户并条件检查新上限不低于当前占用；录取也更新同一名额行 | 串行化后只提交满足 `quotaLimit >= occupiedCount` 的操作。 |
| 同一操作因超时重试 | 校验 `Idempotency-Key` 和请求摘要 | 返回原业务结果，不产生第二次副作用。 |

可编辑资源返回强 `ETag`，修改请求必须带 `If-Match`；缺少版本返回 `428 PRECONDITION_REQUIRED`，版本过期返回 `412 PRECONDITION_FAILED`。资料局部修改使用 PATCH；可报范围这类完整集合替换使用 PUT；志愿提交仍用 POST 创建完整新版本。状态命令使用 POST 并由服务端按当前状态条件迁移，不接受客户端设置状态字段。

多行锁按固定顺序获取；死锁仅对有幂等键、可完整重放且已明确事务结果的命令做有限重试。遇到唯一键冲突、条件更新影响行数为零或版本变化时，Service 转换为稳定业务错误码，不把 SQL 异常文本返回客户端。

## 9. 批量操作与通知的返回约定

批量操作请求先进行整体结构和静态授权验证：项目数量限制、ID 格式、重复 ID、调用者权限和对象范围。结构无效时整个请求以 `400` 拒绝且不执行。通过验证后，操作清单按稳定顺序持久化，端点以 `202` 返回操作 ID；服务端随后按顺序逐项处理，每项执行前在自身事务内重新校验对象状态、阶段窗口和授权。客户端轮询读取逐项结果。超限整单拒绝，不自动拆成多个幂等操作；接收请求不会冻结每项开始执行时的阶段状态。

常规批量录取按最终锁定志愿提交时间升序，同一时间按学号升序；补选批量录取按补选申请提交时间升序，同一时间按学号升序。批量结果可包含 `ADMITTED`、`NOT_ADMITTED`、`FAILED` 及稳定原因码。名额用尽后的余项依业务规则结案为不录取，响应提示部分未录取。单项录取提交时关系、名额、申请状态等原子一致。

业务结果站内通知由服务端根据最终事务结果生成。通知收件记录应随业务事务写入；投递/可见状态更新失败可重试，不回滚已提交匹配。轮次关闭前不得通过 API 或通知提前泄露导师录取决定。首期没有外部消息通道。

## 10. 已确认的技术方案与部署验证项

以下十项及本文件端点路径和字段均由业务方按当前内容确认定案。学校身份认证要求、恶意文件扫描和私有存储服务须在部署前落实；批量上限须经目标环境压测验证。

| 项目 | 已确认方案 | 状态 |
|---|---|---|
| 认证与学校统一认证 | 首期用 Spring Security 本地账号 + 服务端 Session，浏览器 Cookie 设 `HttpOnly`/`Secure`/`SameSite=Lax` 并启用 CSRF。只有学校明确要求统一认证时再接 OIDC/SAML 适配，不让外部认证改变系统角色/数据范围。 | 已确认；部署前核对学校要求 |
| API 版本 | 维持 `/api` 前缀，暂不增加 `/v1`；未来出现外部消费者或破坏性版本需求时再单独版本化。 | 已确认 |
| 时间和阶段边界 | API 时间带偏移；服务端/`DATETIME(3)` 统一按 UTC 处理，界面以 `Asia/Shanghai` 展示；窗口用 `[startAt,endAt)`，锁定阶段记录后读取数据库 UTC 时间判断。恰好等于截止时刻时拒绝；事务内已通过的校验即使在截止后提交仍有效。异步批量操作逐项校验。 | 已确认（TODO-50） |
| 分页、排序和导出 | 默认 20 条、最大 100 条；普通列表排序稳定，业务队列按确认规则排序；筛选/排序字段白名单。导出创建异步任务，按权限和字段白名单生成；最大数据量用部署配置和压测结果控制。 | 已确认 |
| 版本条件与更新语义 | 可编辑资源返回强 ETag，PATCH/PUT 必须带 `If-Match`；缺少返回 428，过期返回 412。PATCH 表达局部字段，PUT 表达完整集合替换，状态迁移使用 POST 命令。 | 已确认 |
| 文件上传与下载 | 应用接收 multipart；限制 PDF/10 MB，检查文件签名并完成恶意文件扫描后才标记可用；随机内部键存私有目录/存储，SHA-256 校验，下载逐次鉴权、审计并流式返回。 | 已确认；部署前落实扫描和私有存储 |
| 幂等键与保留 | `Idempotency-Key` 使用 UUID v4；按账号 + 动作 + 键唯一，保存规范化请求摘要。幂等标识随业务操作/审计保留；没有历史清理期限前不设置自动过期，不保存敏感原文。 | 已确认；与审计保留政策对齐 |
| 批量请求规模、逐项事务 | 批量端点上限 500 项，超限整单拒绝；返回 202 和操作 ID，服务端按操作内排序顺序执行、逐项持久化；每条录取各自一个原子事务，失败项不回滚已成功项。 | 已确认；压测后可调整部署配置 |
| 不同批量操作间的排序 | 不同操作不引入全局候选人排序/FIFO 排队；数据库行锁保证名额不超额，竞争最后名额时先取得行锁并成功提交的操作获得。 | 已确认（TODO-51） |
| 错误码和消息 | 错误码用稳定字符串大写下划线格式，HTTP 状态表达大类，客户端按 code 分支；`message` 使用简明中文展示，字段错误单独返回。内部异常只进日志，不泄露 SQL/堆栈。 | 已确认 |

`TODO-50` 至 `TODO-64` 已按业务方确认结果写入登记表及规则文档。本版本明确学生、导师与管理员初始/重置临时凭证均不设到期时间，仍为一次性消费并要求首次登录改密；学生/导师初始密码仍取学号/工号末尾六位，较短编号完整使用。管理员轮次重开请求包含管理员指定的 `newEndAt` 与原因。TODO-65 记录用户故事提出师生忘记密码时由管理员重置，但具体执行能力/数据范围及端点尚待确认，因此本版只包含总管理员重置其他普通 ADMIN 的 API。端点路径与请求/响应字段是实施契约；页面按契约细化按钮可用条件、字段和错误展示。仍需补充 OpenAPI 文档与接口级验收记录，并验证目标部署环境要求。本 API 定稿不构成生产部署批准。
