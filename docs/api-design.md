# 师生互选系统 API 设计

> 版本：0.7（总管理员系统级权限补充）
> 状态：业务方于 2026-10-03 确认总管理员拥有全部已登记管理员业务能力和全系统学院/批次范围；既有端点路径、请求/响应字段和第 10 节技术方案继续构成实施契约。
> 更新日期：2026-10-03  
> 基线：[总体需求](../requirements.md) 0.23、[业务规则](business-rules.md) 0.14、[状态机](state-machine.md) 0.12、[权限矩阵](permission-matrix.md) 1.3、[用户故事](user-stories.md) 1.3、[功能模块设计](functional-modules.md) 0.8、[物理数据库设计](database-design.md) 0.6。

## 1. 设计范围与原则

本文把已确认的业务能力映射为 HTTP API，并定义请求/响应、错误、授权、幂等和并发契约。端点路径和字段以本文现有内容定案，实施不得自行改变业务语义；需要调整时同步更新 API、业务规则或权限设计并记录新版本。

- API 使用 `/api` 前缀，JSON 属性采用 lower camelCase；Controller 接收独立 Request，返回角色化 DTO/VO，不返回数据库 Entity。
- 资源查询使用 GET；修改资源使用 PUT/PATCH；发布、启动、录取、关闭等状态迁移使用明确的命令端点，不允许客户端直接提交任意状态值。
- 所有写操作由服务端重新验证身份、授权范围、对象归属、状态、时间、资格和范围。客户端传入的对象 ID 仅用于定位，不构成授权证明。
- 一个关键业务命令的校验、状态变化、关系/名额变动、业务事件和审计须按对应事务边界提交。Mapper/数据库约束是并发保护的一部分；前端禁用按钮不构成防重。
- 首期只设计站内通知；不设计系统外公示或邮件/短信接口。TODO-49 暂缓范围不暴露为本期 API。

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
5. **文件和导出：** 每次下载/导出独立授权；字段由服务端白名单生成，不能信任客户端提交的字段集合。访问简历、联系方式或导出数据时写入访问记录。
6. **状态门禁：** 权限通过不等于操作可用。领域服务还须检查批次、阶段、申请、学生匹配状态、冻结范围及截止时间；不得通过换 URL 或伪造对象 ID 绕过。

调用者无权访问或不应获知存在性的对象统一返回 `404 NOT_FOUND` 以降低 ID 枚举风险；调用者可见资源上的动作权限不足时返回 `403 FORBIDDEN`。

## 4. 接口目录（已确认契约）

下表列出首期已确认的主要端点；本版本列出的路径、方法和主要字段作为实现契约冻结。未列出的 CRUD 字段或路径不视为已开放；后续增补或变更须发布 API 新版本。除 GET/HEAD 外，凡创建记录或触发状态变化的请求都应遵守第 7 节幂等要求。

### 4.1 登录与通用查询

| 方法与路径 | 权限 | 主要请求/响应 | 说明 |
|---|---|---|---|
| `POST /api/auth/login` | 未认证 | 请求：登录标识、密码；响应：服务端会话结果及角色化用户概要 | 错误不得区分可枚举的账号细节。 |
| `POST /api/auth/logout` | 已认证 | 无业务字段；响应：登出结果 | 使服务端 Session 失效并清理会话 Cookie。 |
| `GET /api/auth/me` | 已认证 | 响应：当前账号、角色、账号状态、有限授权概要 | 不返回密码哈希、临时凭证明文或不必要的全量授权数据。 |
| `POST /api/auth/password-change` | 已认证/首次改密 | 请求：当前或临时凭证、新密码；响应：完成状态 | 临时凭证需一次性消费；管理员重置凭证 72 小时有效、仅展示一次、线下交付，首次登录强制改密。 |
| `GET /api/operations/{operationId}` | 发起者本人或有范围权限的管理员 | 响应：操作状态、逐项结果摘要和可否重试 | 仅查询授权范围内的业务操作；不可借此读取敏感请求原文。 |
| `GET /api/majors` | 已认证 | 查询：学院/启用状态；响应：授权可见专业目录 | 仅提供目录数据，不授予读取学生身份信息的权限。 |
| `GET /api/teachers`、`GET /api/teachers/{teacherId}` | 已认证 | 查询：批次、专业、学位类型、研究方向等允许筛选项；响应：已审核公开资料和可填报布尔值 | 不返回剩余名额数；学生的可报布尔值只按当前主体计算。 |
| `GET /api/files/{fileId}/content` | 文件所有者、当前获授权导师或管理员 | 受控文件流 | 每次请求检查当前对象/字段权限并写访问审计；禁止公开永久链接。 |
| `GET /api/me/notices`、`POST /api/me/notices/{noticeId}/read` | 已认证收件人 | 分页通知/已读结果 | 由服务端校验收件人账号。 |

登录请求 JSON 字段为 `loginIdentifier`、`password`；成功响应的 `data` 包含 `authenticated` 和角色化 `user`。`GET /api/auth/me` 返回相同用户概要：`accountId`、`loginIdentifier`、`role`、`accountStatus`、`mustChangePassword`、本人身份概要及当前管理员能力/学院/批次授权摘要。身份概要仅含本人业务标识、姓名和学院；授权摘要不包含授权依据或操作者资料。

密码修改请求字段为 `currentCredential`、`newPassword`；`currentCredential` 在普通改密时是当前密码，首次改密时是一次性临时凭证。密码按 BCrypt 安全哈希保存，修改后的密码最多 72 个 UTF-8 字节，避免 BCrypt 对长输入截断；不额外设置未确认的字符组成规则。首次改密成功后临时凭证在同一事务内标记已使用、密码更新并清除强制改密标记。

浏览器使用 `XSRF-TOKEN` Cookie 和 `X-XSRF-TOKEN` 请求头完成 CSRF 校验。页面启动时的 `GET /api/auth/me` 会建立 CSRF Cookie；成功登录和改密后服务端轮换该令牌。会话 Cookie 使用 `HttpOnly`、`Secure`、`SameSite=Lax`；部署在 HTTPS 环境时必须启用 `Secure`。

### 4.2 学生端

| 方法与路径 | 权限 | 主要请求/响应 | 关键限制 |
|---|---|---|---|
| `GET /api/students/me` | STUDENT 本人 | 当前身份分类、资料、账号状态 | 专业/学位类型只读。 |
| `PATCH /api/students/me/profile` | STUDENT 本人 | 可编辑简介、联系方式及资料版本 | 只接受白名单字段；必须携带 `If-Match`。 |
| `PUT /api/students/me/resume` | STUDENT 本人 | PDF 文件；响应文件元数据和版本 ID | 不允许覆盖历史版本；扫描通过后才可用，使用私有存储；具体存储介质部署前核定。 |
| `POST /api/students/me/identity-correction-requests` | STUDENT 本人 | 申请更正字段及说明；响应申请 ID/状态 | 不直接修改学生专业或学位类型。 |
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
| `POST /api/admin/students`、`POST /api/admin/teachers` | ADMIN + `COLLEGE_ADMIN` | 创建账号与初始档案；响应含一次性临时凭证 | 必须带 `Idempotency-Key`；人员与账号唯一性由数据库约束保护。 |
| `GET /api/admin/majors?collegeId={id}`、`POST /api/admin/majors`、`PATCH /api/admin/majors/{majorId}` | ADMIN + `COLLEGE_ADMIN` | 查询、新建、修改/停用专业目录 | 专业按学院和代码唯一；已被人员档案引用的专业不物理删除。 |
| `POST /api/admin/annual-eligibilities`、`GET /api/admin/annual-eligibilities?...` | ADMIN + `COLLEGE_ADMIN` | 更新资格并查询当前/历史记录 | 更新追加历史；同一人员每学年最多一条当前资格。状态 `ELIGIBLE` / `INELIGIBLE`。 |
| `GET /api/admin/personnel-imports/template?collegeId={id}&personType=STUDENT\|TEACHER` | ADMIN + `COLLEGE_ADMIN` | 下载 UTF-8 CSV 模板 | 学生/导师列头按固定模板版本区分。 |
| `POST /api/admin/personnel-imports`、`GET /api/admin/personnel-imports/{importId}` | ADMIN + `COLLEGE_ADMIN` | 上传 CSV/XLSX、任务概要及首次逐行结果 | 文件 ≤10 MB；每次最多 2000 数据行；表头错误拒绝整份，行错误不中断其他行。 |
| `GET /api/admin/personnel-imports/{importId}/rows` | ADMIN + `COLLEGE_ADMIN` | 历史逐行结果/错误定位 | 按任务学院范围检查；查询响应永不返回已展示凭证。 |
| `PATCH /api/admin/students/{studentId}/classification` | ADMIN 授权范围 | 专业、学位类型、变更依据及预期版本 | 追加分类修订；填报开始后的影响由身份纠错用例联动处理。 |
| `GET /api/admin/identity-correction-requests`、`POST /api/admin/identity-correction-requests/{requestId}/decision` | ADMIN 授权范围 | 申请队列/批准或驳回、依据 | 批准后调用身份纠错用例；不得直接绕过关系和名额事务。 |
| `POST /api/admin/teacher-profile-versions/{versionId}/review` | ADMIN 授权范围 | 审核通过/退回及意见 | 只有审核通过版本进入学生目录；导师可报范围不由此审核。 |
| `POST /api/admin/selection-batches`、`GET /api/admin/selection-batches/{batchId}`、`PATCH /api/admin/selection-batches/{batchId}` | ADMIN 授权范围 | 草稿创建、读取、草稿字段修改 | 发布后字段变更按批次状态和规则限制。 |
| `POST /api/admin/selection-batches/{batchId}/publish` | ADMIN 授权范围 | 发布命令；响应批次状态/运行槽位结果 | 先校验全部必需配置和补选计划，再原子占用同学院同学年槽位。 |
| `POST /api/admin/selection-batches/{batchId}/start`、`pause`、`resume`、`cancel`、`archive`、`unarchive` | ADMIN 授权范围 | 各自明确的状态命令 | 服务端按状态机检查前置条件；不能用 PATCH 任意设置状态。 |
| `PUT /api/admin/selection-batches/{batchId}/schedule` | ADMIN 授权范围 | 阶段/补选计划及预期版本 | 仅允许规则指定时点调整；记录时间修订；不得重开已关闭补选。 |
| `PUT /api/admin/selection-batches/{batchId}/teachers/{teacherId}/quota` | ADMIN 授权范围 | 名额上限及预期版本 | 不得低于已锁定人数；并发变更需版本/条件更新。 |
| `PUT /api/admin/selection-batches/{batchId}/supplement-teachers` | ADMIN 授权范围 | 补选导师名单及变更原因 | 仅窗口关闭前调整；录取时仍校验范围与名额。 |
| `POST /api/admin/matching-relations/{relationId}/adjustments` | ADMIN 关系管理权限 | 撤销/恢复/改派类型、新导师（如适用）、原因和审批意见 | 归档批次须先按规则解除归档；关系、学生状态、名额、流水和审计同事务。 |
| `GET /api/admin/selection-batches/{batchId}/statistics` | ADMIN 授权范围 | 冻结分母、未匹配拆分、补选和名额统计 | 按已确认统计口径；不允许通过筛选改变冻结分母。 |
| `POST /api/admin/selection-batches/{batchId}/exports`、`GET /api/admin/exports/{exportId}` | ADMIN 导出权限 | 白名单字段/筛选条件；导出任务状态/受控文件 | 字段和对象范围服务端二次校验，导出行为记访问审计。 |
| `POST /api/admin/admin-accounts`、`POST /api/admin/admin-accounts/{accountId}/temporary-credential-reset` | 总管理员专属能力 | 创建请求：登录标识；重置请求：目标账号路径参数；响应账号概要和一次性临时凭证 | 只创建普通 ADMIN，不自动授予业务能力；临时凭证只展示一次、72 小时有效、线下交付并审计；不得邮件或短信发送；总管理员应急恢复走 TODO-24。 |
| `GET /api/admin/admin-accounts` | 总管理员专属能力 | 分页查询现有 `ADMIN` 账号；返回账号编号、登录标识、账号状态、首次改密状态和创建时间 | 包含调用者本人；不返回密码、凭证明文/哈希或人员资料；用于确认账号并选择授权或重置目标。 |
| `GET /api/admin/admin-accounts/{accountId}/authorizations` | 总管理员 | 查询目标管理员的能力授权及授权范围；支持学院、状态筛选 | 仅目标为 ADMIN 账号时返回；该只读接口也可显示初始化/应急流程设置的 `ADMIN_ACCOUNT_MANAGER` 状态。 |
| `POST /api/admin/admin-accounts/{accountId}/authorizations` | 总管理员 | 授予一项业务能力及学院/可选批次范围 | 请求带 `Idempotency-Key`；支持 `COLLEGE_ADMIN`、`BATCH_AUDIT`，按 TODO-52/53 校验范围及依据；不可授予 `ADMIN_ACCOUNT_MANAGER`；`COLLEGE_ADMIN` 必须覆盖完整学院。 |
| `POST /api/admin/admin-accounts/{accountId}/authorizations/{authorizationId}/revoke` | 总管理员 | 撤销一项业务能力；请求提供撤销原因 | 请求带 `Idempotency-Key`；只允许撤销该账号、该授权记录上的普通业务能力；保留授权历史并记录撤销人、时间和审计。 |

上表列出的路径按本版本实施。学生停用、导师停用、身份纠错决策和其他未列管理接口仍未纳入本版本；实现这些接口前须补充并确认 API 新版本，不得自行推定路径、字段或扩大权限。管理员能力代码须来自服务端支持的授权目录；当前服务端目录登记 `COLLEGE_ADMIN`、`BATCH_AUDIT`。`ADMIN_ACCOUNT_MANAGER` 是保留能力，只读可见，不属于可授予目录。

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

`actorAccountId`、旧导师、旧关系状态和名额数从服务端读取。撤销、恢复、改派执行前校验授权范围、批次可操作状态、学生关系状态、冻结分类/范围要求（适用时）和新旧导师名额。整项调整与关系、学生状态、名额流水及审计同事务提交或回滚。

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

`batchId` 可省略，表示该能力覆盖指定学院内所有符合其业务定义的批次；提供时服务端检查批次属于同一学院。`capabilityCode` 必须属于服务端支持的业务能力目录（当前为 `COLLEGE_ADMIN`、`BATCH_AUDIT`）；`COLLEGE_ADMIN` 必须省略 `batchId`，覆盖整个学院；`basis` 必须填写。`grantedBy`、`grantedAt`、授权状态及审计主体由服务端生成。不能授予 `ADMIN_ACCOUNT_MANAGER`；目标账号保持 `ADMIN` 角色，权限仅在请求声明的数据范围内生效。

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

创建成功的 `data` 包含 `accountId`、`loginIdentifier`、`result: "CREATED"`、`temporaryCredential`、`expiresAt` 和 `credentialShownNow`。临时凭证由安全随机源生成，有效期 72 小时；账号记录中只存安全哈希，首次使用后必须改密。明文仅在首次成功响应中返回，管理员须线下转交，不通过邮件或短信发送。

重置其他普通管理员的临时凭证：

```http
POST /api/admin/admin-accounts/7301/temporary-credential-reset
Idempotency-Key: 193e...
```

服务端锁定目标账号，确认其为普通 `ADMIN` 后，撤销其未消费的旧临时凭证，清除正式密码并将 `mustChangePassword` 设为 `true`，然后签发 72 小时有效的新凭证。账号版本递增使其既有 Session 失效。不能通过普通接口重置总管理员本人；总管理员应急恢复按 TODO-24 线下核验、双人复核和留痕。

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

学院人员管理 API 均要求当前 `ADMIN` 主体具有 `COLLEGE_ADMIN`，且请求中的 `collegeId` 与该能力绑定学院一致。此能力只能覆盖完整学院；`BATCH_AUDIT` 等批次授权不能替代它。服务端每次请求都从数据库刷新当前账号和授权，不信任页面隐藏状态或会话旧授权摘要。

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

`loginIdentifier` 必须与 `studentNo` 相同；登录名由学号确定，不能为同一学生另设不同登录标识。`degreeType` 仅接受 `ACADEMIC_MASTER`（学硕）或 `PROFESSIONAL_MASTER`（专硕）。服务端创建 `STUDENT` 账号、初始资料版本和 `classification_version=1` 的分类修订记录；专业必须是同学院启用目录项。响应的 `credential` 与管理员账号创建响应结构相同，随机临时凭证 72 小时有效、只在首次响应出现，重复同幂等键不会再次给出凭证。

导师创建请求包含 `loginIdentifier`、`employeeNo`、`fullName`、`collegeId`，且 `loginIdentifier` 必须等于 `employeeNo`。服务端创建 `TEACHER` 账号、基本导师档案及空白 `DRAFT` 资料版本；导师须自行完善资料，审核通过前不对学生公开。学生与导师列表仅包含其登录标识、学号/工号、姓名、学院和必要档案概要。

专业新增请求字段：`collegeId`、`majorCode`、`name`、可选 `validFrom`/`validTo`（`YYYY-MM-DD`）和必填 `changeBasis`。修改请求字段：`name`、`active`、可选有效期、`changeBasis`。专业代码创建后不可修改；停用替代物理删除，保留现有人员与历史批次引用。

年度资格命令字段为 `personType`（`STUDENT`/`TEACHER`）、`personId`、`academicYearId`、`collegeId`、`eligibilityStatus`（`ELIGIBLE`/`INELIGIBLE`）、`evidenceType`、可选 `evidenceReference` 和 `sourceName`。服务端锁定人员行，校验人员学院后结束旧当前记录、追加新历史行并切换唯一当前资格槽位。当前资格查询默认只返回 `validTo=null` 的行；`history=true` 返回所有版本。状态更新、依据、来源、修改人和有效期均保留。

固定模板为 UTF-8 CSV；Excel 用户可直接打开并另存为 XLSX。模板版本为 `1.0`，表头和列顺序固定：

```text
学生：loginIdentifier,studentNo,fullName,majorCode,degreeType,enrollmentYearCode,eligibilityStatus,evidenceType,evidenceReference
导师：loginIdentifier,employeeNo,fullName,eligibilityStatus,evidenceType,evidenceReference
```

模板中的 `loginIdentifier` 与对应学生学号/导师工号必须相同；系统保留两列是为了让登录标识在导入结果中清晰可见，二者不一致的行会拒绝创建。

学年和学院由上传请求参数指定，不可从表格扩展范围。上传接受 `.csv`、`.xlsx`，最大 10 MB、最多 2000 条数据行。表头不完全匹配时拒绝整份文件且不创建人员；通过表头校验后按物理行号逐行处理。每个成功行的账号、人员资料、学生初始分类（学生）和年度资格在一个独立事务提交；同一文件其他行失败不回滚已成功行。每行返回行号、学号/工号、状态、错误码/说明、人员 ID、资格 ID 和首次创建的登录标识/临时凭证。导入源文件以随机存储键写入 Web 根目录外私有目录，并在 `managed_file` 保存文件摘要；历史导入行不保存密码或临时凭证明文。重复上传的同幂等键返回原任务和行状态，所有凭证字段为空。

## 6. 错误码目录（技术基线）

错误码为稳定机器可读字符串；前端按 `code` 分支，`message` 仅供展示/辅助，不能作为业务判断条件。字段错误通过 `data.fieldErrors` 给出，不在错误文本中返回 SQL、堆栈、密码或其他敏感信息。

| 错误码 | HTTP | 含义/适用场景 |
|---|---:|---|
| `INVALID_ARGUMENT` | 400 | JSON 格式、必填字段、类型或字段范围不合法。 |
| `UNAUTHENTICATED` | 401 | 未登录或凭证失效。 |
| `INVALID_CREDENTIALS` | 401 | 登录标识/密码组合无效；不区分账号是否存在。 |
| `ACCOUNT_DISABLED` | 403 | 账号已停用。 |
| `PASSWORD_CHANGE_REQUIRED` | 403 | 首次登录须先设置正式密码。 |
| `TEMP_CREDENTIAL_EXPIRED` | 401 | 临时凭证过期。 |
| `TEMP_CREDENTIAL_ALREADY_USED` | 409 | 临时凭证已消费。 |
| `INTERNAL_ERROR` | 500 | 未预期服务端错误；响应仅给出通用提示，详细异常只记录在服务端。 |
| `FORBIDDEN` | 403 | 缺少端点/操作权限。 |
| `SCOPE_FORBIDDEN` | 403 | 调用者可见的学院、批次或对象上缺少授权；不应暴露存在性的对象返回 `NOT_FOUND`。 |
| `NOT_FOUND` | 404 | 资源不存在或对调用者不可见。 |
| `IDEMPOTENCY_KEY_REUSED` | 409 | 同一操作者/动作/幂等键对应不同请求摘要。 |
| `REQUEST_IN_PROGRESS` | 409 | 同一幂等操作仍在执行；响应包含 `operationId` 或查询地址。 |
| `STATE_CONFLICT` | 409 | 对象状态已变化或不允许该动作。 |
| `PRECONDITION_REQUIRED` | 428 | 该资源已采用必需版本前置条件，但请求未携带版本。 |
| `PRECONDITION_FAILED` | 412 | `If-Match` 所带资源版本已过期。 |
| `BATCH_NOT_OPEN` | 409 | 批次未处于此动作允许的状态/时间窗口。 |
| `BATCH_RUNNING_SLOT_OCCUPIED` | 409 | 同学院同学年已存在运行批次。 |
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

`TODO-50` 至 `TODO-56` 已按业务方确认结果写入登记表及规则文档。本版本的端点路径与请求/响应字段是已确认实施契约；页面按此细化按钮可用条件、字段、确认提示、错误展示和分页行为。实现阶段补充 OpenAPI 文档与接口级验收测试，并验证目标部署环境要求。本 API 定稿不构成生产部署批准。
