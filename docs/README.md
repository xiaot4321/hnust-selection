# 文档目录与当前实现状态

本目录保存师生互选系统的专项设计文档。总体需求和项目入口位于仓库根目录。

## 当前文档

| 文档 | 内容 | 状态 |
|---|---|---|
| [总体需求](../requirements.md) | 范围、角色、流程、功能、共性约束和决策索引 | 0.29 师生凭证重置权限/API 范围待确认 |
| [分轮业务规则](business-rules.md) | 已确认匹配规则、例外处理及冲突记录 | 0.18 常规关系恢复与改派边界 |
| [状态机设计](state-machine.md) | 批次、阶段、志愿、学生状态、申请和关系状态 | 0.14 常规关系恢复与改派边界 |
| [权限矩阵](permission-matrix.md) | 角色权限、数据范围、敏感字段访问 | 1.7 师生凭证重置授权范围待确认 |
| [用户故事](user-stories.md) | 角色场景、验收目标和实现/验证状态 | 2.0 当前实现与验收状态 |
| [待确认事项登记表](todo-register.md) | 待确认事项与已确认决策的统一登记，含数据设计输入约束 | 3.5 决策记录；TODO-49 特殊边界暂缓，TODO-50 至 TODO-64 已确认，TODO-65 待确认 |
| [逻辑数据模型](logical-data-model.md) | 核心实体关系图、属性、唯一性、状态归属、事务、历史与访问边界 | 0.11 常规关系恢复与改派事务边界；TODO-49 特殊边界暂缓 |
| [物理数据库设计与数据字典](database-design.md) | 逻辑实体到 MySQL/InnoDB 表的映射、字段类型、约束、索引和事务并发设计 | 0.8 管理员异步导出任务；目标 MySQL 5.7.36 |
| [MySQL 5.7.36 完整建库 DDL](../sql/mysql57/schema.sql) | 51 张表、主键、唯一键、索引及 160 个外键的首次建库脚本 | 新库含管理员导出任务表；本机验证库已迁移至 51/160；其他环境部署须准备迁移/回滚方案 |
| [MySQL 前向迁移](../sql/mysql57/migrations/20261003_personnel_management_v1.sql) | 为旧版本机验证库添加资格唯一当前槽位、导入关联及总管理员人员能力 | 2026-10-03 本机结构变更已应用；不得对已迁移库重跑完整迁移 |
| [人员管理迁移恢复脚本](../sql/mysql57/migrations/20261003_personnel_management_v1_recovery.sql) | 修复本机迁移中因 MySQL 会话字符集导致失败的总管理员授权及审计记录 | 2026-10-03 本机恢复成功；授权和审计各写入一行；脚本可重复执行，仅适用于已部分应用 v1 结构的验证库 |
| [凭证取消到期迁移](../sql/mysql57/migrations/20261005_credential_expiry_removal_v1.sql) | 将凭证到期时间改为可空，并清除仍未使用/未撤销凭证的到期时间 | 2026-10-05 已应用到本机验证库；预先备份，3 条凭证中清除 1 条未消费凭证到期时间，50 张表和 158 个外键保持不变 |
| [凭证取消到期回滚脚本](../sql/mysql57/migrations/20261005_credential_expiry_removal_v1_rollback.sql) | 按签发时间恢复 72 小时到期规则 | 仅用于明确的回滚；执行后已超过 72 小时的未用凭证将不可用 |
| [管理员导出任务迁移](../sql/mysql57/migrations/20261005_admin_export_jobs_v1.sql) | 为现有数据库新增管理员异步导出任务表 | 2026-10-05 已应用到本机验证库；执行前备份为 `%TEMP%\hnust_selection_before_admin_export_jobs_20261005_175530.sql`，迁移后 51 张表、160 个外键；其他环境执行前核对版本和回滚方案 |
| [功能模块设计](functional-modules.md) | 学生、导师、管理端功能清单、模块职责、数据归属、模块协作及当前代码入口 | 0.16 互选实现状态盘点 |
| [API 设计](api-design.md) | 端点目录、请求/响应契约、错误码、认证授权、幂等和并发语义 | 1.7 登记师生凭证重置接口缺口；TODO-65 待确认 |

## 当前代码实现状态

根目录 [README](../README.md) 提供本机运行方法；`backend/` 是 Spring Boot 后端，`frontend/` 是 Vue + TypeScript + Vite 前端。下表是截至 2026-10-07 对当前工作树的代码入口盘点。“已接入”表示相应 HTTP/API、业务服务和页面入口可在代码中定位；它不等同于端到端验收通过、生产部署完成或所有规则边界都已实现。

| 使用方 | 当前已接入的主要能力 | 主要代码入口（按任务从入口继续追调用链） |
|---|---|---|
| 学生 | 本人批次/进度/志愿历史和结果查询；导师目录；身份确认；1–3 位有序志愿提交、撤回与历史；补选申请；本人资料/简历；身份更正申请；站内通知。服务端写操作处理本人身份、批次阶段、幂等、分类版本和冻结范围校验。 | 后端 `StudentBatchController`、`StudentProgressController`、`StudentSelectionQueryController`、`StudentPreferenceMutationController`、`StudentSupplementMutationController`、`StudentPreferenceMutationServiceImpl`；前端 `StudentWorkspace.vue`、`studentPortalService.ts`。 |
| 导师 | 本人公开资料与招生范围配置；常规轮次和补选队列；逐项与异步批量决定；本人名额/匹配关系查询；站内联系；授权范围内受控简历读取。匹配写入联动关系、名额、学生状态、事件和审计。 | 后端 `TeacherWorkspaceController`、`TeacherWorkspaceServiceImpl`、`TeacherApplicationScopeController`；前端 `TeacherWorkspace.vue`、`teacherWorkspaceService.ts`。 |
| 管理员 | 学生/导师人员及年度资格、名单导入；管理员能力授权；学生身份纠错；导师资料审核；批次草稿、排期、发布、生命周期、导师范围/名额、轮次延期/重开和补选配置；关系查询及撤销/恢复/改派；冻结统计、审计和异步导出；`BATCH_AUDIT` 只读范围。关系变更要求原因并使用版本/幂等检查，按服务实现执行事务校验。 | 后端 `PersonnelManagementController`、`AdminIdentityCorrectionController`、`AdminTeacherProfileReviewController`、`SelectionBatchManagementController`、`AdminRelationAdjustmentController`、`AdminExportController`；前端 `components/admin/` 与 `services/` 对应模块。 |

### 尚未实现、暂缓或未验证的事项

- **TODO-49 特殊边界暂缓：** 启动时跳过填报阶段而缺少可验证冻结范围；无补选、补选已关闭或批次完成时的身份纠错路径；因身份纠错或缺少冻结范围产生的关系恢复/改派。常规关系恢复只处理 `RELATION_REVOKED` 来源，常规改派须校验已冻结范围和名额；TODO-49 请求由服务端拒绝，不能把“常规关系调整已接入”扩写成“所有异常边界已实现”。
- **密码找回：** 没有自助“忘记密码”页面、接口或恢复渠道。当前 `/auth/password-change` 要求已登录账号提交当前正式密码或一次性临时凭证；总管理员可重置其他普通 ADMIN 的临时凭证。用户故事要求管理员可重置师生遗忘的凭证，但角色/学院范围及 API 尚未确定（TODO-65），当前代码也未提供学生/导师账号重置端点。
- **文件安全：** 学生 PDF 上传接口及私有文件访问/存储代码已存在，但恶意文件扫描器的集成和部署环境接线仍待落实；上传文件在扫描通过前不可作为已安全可访问文件处理。
- **环境与运行验证：** 本机 MySQL 5.7.36 验证库已应用管理员导出任务迁移，现为 51 张表、160 个外键；这不代表其他或生产环境已迁移。部署前仍须核对学校认证要求、数据库兼容性、私有存储与扫描、迁移/回滚、备份恢复及批量压测。
- **自动化验收覆盖：** 当前后端测试目录盘点到 9 个测试文件，范围包括认证/授权、启动初始化、批次管理、人员注册/导入、导入解析和异常处理；没有专门覆盖学生志愿提交、导师轮次决定、补选、关系/名额并发调整的测试文件。前端未发现 `test`/`spec` 文件。此为测试源码盘点，不表示本次运行过测试或既有测试已通过。

功能清单及验收边界见 [功能模块设计](functional-modules.md) 与 [用户故事](user-stories.md)。API 文档规定契约；代码状态和测试覆盖须另外核对，不能仅凭设计文档推断实现完成。

## 版本记录

| 日期 | 变更 | 涉及版本 |
|---|---|---|
| 2026-09-29 | 业务方确认全部待确认事项（登记表 `TODO-01`～`TODO-17`、`TODO-P01`～`TODO-P10`），各文档同步落地为已确认规则 | requirements 0.4；business-rules / state-machine / permission-matrix / user-stories 0.3；项目总览 0.3；登记表 1.1 |
| 2026-09-29 | 文档审查修正与补强（登记表 `TODO-18`～`TODO-26`）：编号体系解耦、轮次重开前置条件、匹配状态作用域、字段级可见性、批量录取顺序、撤回发布等 | 同上 |
| 2026-09-29 | 复审确认剩余 5 项待确认并新增排序时间依据（登记表 `TODO-27`～`TODO-32`）；登记表新增第 6 节数据设计输入约束；待确认事项清空 | 登记表 1.2；requirements / business-rules / state-machine / permission-matrix / user-stories 内容同步 |
| 2026-09-30 | 确认启动时跳过阶段的学生处理、同学年运行批次唯一性范围、完成/归档批次关系纠错（登记表 `TODO-33`～`TODO-35`）；同步规则、状态机、权限和验收场景 | 登记表 1.3；requirements 0.5；business-rules / state-machine / permission-matrix / user-stories 0.4；项目总览 0.4 |
| 2026-10-01 | 创建逻辑数据模型；登记建模发现的 TODO-36 至 TODO-42；同步入口、状态/权限/验收待确认标记和后续设计状态 | logical-data-model 0.1；登记表 1.4；requirements 0.6；business-rules / state-machine / permission-matrix / user-stories 0.5；项目总览 0.5 |
| 2026-10-01 | 确认并同步 TODO-36 至 TODO-38、TODO-40 至 TODO-42；协调发现 TODO-39 与既有补选重开规则冲突，保留两个方案待业务方选择 | logical-data-model 0.2；登记表 1.5；requirements 0.7；business-rules / state-machine / permission-matrix / user-stories 0.6；项目总览 0.6 |
| 2026-10-01 | 业务方选择方案 B：补选关闭即自动完成批次，关闭后不得重开补选；更新 TODO-07、TODO-39 并同步各设计文档 | logical-data-model 0.3；登记表 1.6；requirements 0.8；business-rules / state-machine / permission-matrix / user-stories 0.7；项目总览 0.7 |
| 2026-10-01 | 确认使用 Java 8；总体需求补充编译、运行和依赖兼容约束；移除 Spring Boot 3 候选，Spring Boot 主版本改为待兼容性与部署要求评估 | requirements 0.9；AGENTS.md；项目总览 0.8 |
| 2026-10-01 | 确认使用 Spring Boot 2.6.6，与 Java 8 组成后端技术基线；同步依赖兼容要求 | requirements 0.10；AGENTS.md；项目总览 0.9 |
| 2026-10-01 | 确认导师允许学位类型与专业、按批次冻结、学生分类由管理员维护、补选沿用（TODO-43 至 TODO-46）；修正“不分专业/学位”的歧义，补充逻辑实体、校验、字段和场景 | requirements 0.11；business-rules / state-machine / permission-matrix / user-stories 0.8；logical-data-model 0.4；登记表 1.7；项目总览 0.10；AGENTS.md |
| 2026-10-01 | 确认 TODO-47/48：导师自主设范围、配置不完整默认全选；学生填报前核验身份，填报开始后纠错则取消录取、回退名额并等待补选。未安排或已关闭补选时的路径登记 TODO-49 | requirements 0.12；business-rules / state-machine / permission-matrix / user-stories 0.9；logical-data-model 0.5；登记表 1.8；项目总览 0.11；AGENTS.md |
| 2026-10-01 | 业务方决定暂不考虑 TODO-49 涉及的低概率边界；标记为暂缓且不阻塞本期逻辑模型评审 | requirements、business-rules、state-machine、permission-matrix、user-stories、logical-data-model、todo-register、AGENTS.md、项目总览 |
| 2026-10-01 | 逻辑模型评审：补全身份版本确认、更正申请留痕、身份纠错跳过结果及录取并发边界；修正 TODO-49 引用和统计分类 | logical-data-model 0.6；business-rules / state-machine 0.10 |
| 2026-10-01 | 业务方确认评审建议：E17 持久维护已占用名额并以 E24/E27 核对；统计展示当前未匹配总数与正常流程未匹配数，按来源拆分 | requirements 0.13；business-rules 0.11；user-stories 1.0；logical-data-model 0.7；todo-register 1.9 |
| 2026-10-01 | 根据需求和逻辑模型创建物理数据库设计/数据字典与功能模块设计评审稿；保持数据库产品待选，不创建 DDL 或业务代码 | database-design 0.1；functional-modules 0.1；本文件 |
| 2026-10-01 | 确认使用 MySQL；物理设计改为 MySQL/InnoDB 语义，明确 DATETIME(3)、utf8mb4、外键和 CHECK 约束的版本差异；MySQL 服务器版本待部署环境核定 | database-design 0.2；functional-modules 0.1；requirements、项目总览、AGENTS |
| 2026-10-01 | 明确数据库设计目标版本为 MySQL 5.7.36；移除 CHECK 依赖、采用 MySQL 5.7 字典语义，并记录分支维护阶段供部署评审 | database-design 0.3；functional-modules 0.1；requirements、项目总览、AGENTS |
| 2026-10-01 | 功能模块设计标明 MySQL/InnoDB 运行基线，与数据库设计 0.3 对齐 | functional-modules 0.2；本文件 |
| 2026-10-01 | 明确分层一致性方案：强引用默认使用物理外键；Service 负责业务校验和事务边界；Mapper 执行条件更新/锁定 SQL，由 InnoDB 落实并发保护；同步功能模块与项目约定 | database-design 0.4；functional-modules 0.3；requirements 0.14；项目总览 0.12；AGENTS |
| 2026-10-01 | 按用户指令生成 MySQL 5.7.36 首次建库 DDL（49 张表）；首次尝试时默认账号未通过认证、脚本尚未应用（后续执行见下一条） | `sql/mysql57/schema.sql`；本文件、数据库设计、项目总览、AGENTS |
| 2026-10-01 | 业务方在本机 MySQL 5.7.36-log 执行初始建库 DDL，并提供查询结果核验 49 张表、151 个外键；物理设计评审状态不变 | `sql/mysql57/schema.sql`；本文件、数据库设计、项目总览、AGENTS |
| 2026-10-02 | 按已确认用户故事补充功能模块主责/协作关系和验收边界追踪，明确模块间必须经应用服务协作；API、页面和实现仍待后续设计 | functional-modules 0.4；本文件、项目总览、AGENTS |
| 2026-10-02 | 业务方确认功能模块设计 0.4 作为功能基线；新增 API 设计评审稿，细化端点、数据契约、权限、错误、幂等和并发约定 | functional-modules 0.4；api-design 0.1；本文件、项目总览、AGENTS |
| 2026-10-02 | 对 API 设计列出的技术选择逐项给出推荐方案；截止临界点与并发批量录取顺序可能改变业务结果，登记为 TODO-50/51 待确认 | api-design 0.2；todo-register 2.0；requirements 0.15；本文件、项目总览、AGENTS |
| 2026-10-02 | 业务方采纳 API 十项推荐方案，确认截止校验和跨批量并发语义，关闭 TODO-50/51；同步时间规则、名额竞争规则及 MySQL UTC 约定 | api-design 0.3；todo-register 2.1；business-rules 0.12；state-machine 0.11；database-design 0.5；requirements 0.16；本文件、项目总览、AGENTS |
| 2026-10-02 | 业务方确认将现有逻辑模型、状态机、权限矩阵、用户故事、物理数据库设计和 API 草案按当前内容定案；解除数据设计完成前的业务实现门槛，保留 TODO-49 暂缓及目标环境部署核验要求 | requirements 0.17；todo-register 2.2；logical-data-model 0.7；state-machine 0.11；permission-matrix 0.9；user-stories 1.0；database-design 0.5；api-design 0.3；本文件、AGENTS、项目总览、DDL README |
| 2026-10-02 | 业务方指定 MyBatis-Plus 3.3.1、本机 Maven 3.9.1 和 Vue 3；同步后端/前端技术基线与候选工具链范围 | requirements 0.18；todo-register 2.3；AGENTS、项目总览、本文件 |
| 2026-10-02 | 按本机环境定版前端工具链：Node.js 22.20.0、npm 10.9.3、TypeScript 5.2.2；本机未安装 Vite，按 Node.js 兼容性选定 Vite 8.3.2 | requirements 0.19；todo-register 2.4；AGENTS、项目总览、本文件 |
| 2026-10-02 | 按已定案技术栈创建 Spring Boot/MyBatis-Plus 后端与 Vue/TypeScript/Vite 前端骨架，数据库凭据改由环境变量提供，禁用启动时 SQL 初始化 | README、backend/、frontend/、本文件 |
| 2026-10-03 | 业务方确认 TODO-52：仅总管理员可为其他 ADMIN 账号授予/撤销普通业务能力；`ADMIN_ACCOUNT_MANAGER` 仅通过初始化或应急恢复流程设置。同步权限矩阵、管理员用例和 API v0.4 契约 | requirements 0.20；permission-matrix 1.0；functional-modules 0.5；api-design 0.4；todo-register 2.5；AGENTS、本文件 |
| 2026-10-03 | 按业务方要求新增总管理员查看现有管理员账号的小窗；增加分页目录 API 和最小账号概要，并同步权限、验收故事与 API 实施契约 | requirements 0.21；permission-matrix 1.1；user-stories 1.1；functional-modules 0.6；api-design 0.5；本文件 |
| 2026-10-03 | 按已确认 TODO-53 至 TODO-55 实现总管理员生命周期、学院人员/专业/资格管理和固定模板逐行导入；增加前向迁移、管理页面、测试与实施契约 | requirements 0.22；business-rules 0.13；permission-matrix 1.2；user-stories 1.2；functional-modules 0.7；logical-data-model 0.8；database-design 0.6；api-design 0.6；本文件 |
| 2026-10-03 | 修正迁移预检对不存在的 `person_type` 列的引用，显式设置 `utf8mb4` 会话字符集；为本机已部分执行的迁移增加可重复运行的恢复脚本 | `sql/mysql57/migrations/`；database-design；本文件、SQL README |
| 2026-10-03 | 业务方执行人员管理迁移及恢复脚本；结构计数预检通过，总管理员业务授权与审计记录各成功写入一行 | 本机 MySQL 5.7.36-log 验证库；SQL README、database-design、本文件 |
| 2026-10-03 | 按业务方确认的 TODO-56 将总管理员定义为系统级管理员：全部已登记管理员能力和全系统学院/批次范围；同步服务端授权、前端学院选择、测试与规则文档 | requirements 0.23；business-rules 0.14；state-machine 0.12；permission-matrix 1.3；user-stories 1.3；functional-modules 0.8；api-design 0.7；todo-register 2.7；本文件 |
| 2026-10-04 | 按业务方确认的 TODO-57 新增 `BATCH_MANAGER`；实现批次草稿、排期、发布/启动、导师名额和本人招生范围配置及填报开始冻结；同步授权/API/验收文档 | requirements 0.24；permission-matrix 1.4；user-stories 1.4；functional-modules 0.9；api-design 0.8；todo-register 2.8；本文件 |
| 2026-10-04 | 补齐学生批次列表、身份更正申请查询，以及资料、导师目录、志愿、结果、补选和通知字段；不改变已确认业务规则 | api-design 0.9；本文件 |
| 2026-10-05 | 按业务方确认的 TODO-58，将学生/导师新账号初始密码改为学号/工号末尾六位（编号较短时完整使用）；同步建号、名单导入、首次改密提示及规则/API/权限文档，管理员凭证仍由安全随机源生成 | requirements 0.25；business-rules 0.15；permission-matrix 1.5；user-stories 1.5；functional-modules 0.10；api-design 1.0；todo-register 2.9；本文件 |
| 2026-10-05 | 按业务方确认的 TODO-31/58 取消全部角色临时凭证的 72 小时到期限制；凭证仍一次性消费并强制首次改密。数据库字段改为可空，新增向前迁移及回滚脚本并同步认证查询、界面与规则文档 | requirements 0.26；business-rules 0.16；logical-data-model 0.9；database-design 0.7；permission-matrix 1.6；user-stories 1.6；functional-modules 0.11；api-design 1.1；todo-register 3.0；SQL migration；本文件 |
| 2026-10-05 | 已备份并在本机 MySQL 5.7.36-log 验证库执行凭证取消到期迁移；复核 `expires_at` 可空、未消费凭证没有到期时间，表数/外键数不变 | `sql/mysql57/migrations/20261005_credential_expiry_removal_v1.sql`；MySQL migration verification；本文件 |
| 2026-10-05 | 按导师端已定案 API 实现工作台、轮次与补选办理、事务化关系/名额、批量处理、结果查询、站内消息和受控简历访问；补齐学生 PDF 上传入口及实现字段文档 | backend、frontend；user-stories 1.7；functional-modules 0.12；api-design 1.2；本文件 |
| 2026-10-05 | 补齐管理员批次暂停、恢复、取消、归档和解除归档命令，事务化平移暂停期间排期、结案取消申请、保留匹配/名额历史；增加冻结统计与 `BATCH_AUDIT` 只读视图 | backend、frontend；functional-modules 0.13；api-design 1.3；permission-matrix；todo-register 3.1；本文件 |
| 2026-10-05 | 按业务方确认的 TODO-59 至 TODO-64 补齐身份纠错、导师公开资料审核、关系撤销、异步导出、轮次延期/重开和补选导师名单；轮次重开明确提交 `newEndAt`，仅恢复截止自动结案项目并按执行周期留痕 | backend、frontend；business-rules、api-design、state-machine、user-stories、logical-data-model、todo-register；本文件 |
| 2026-10-05 | 按业务方指令备份本机验证库并执行管理员导出任务迁移，核验 51 张表、160 个外键及空任务表 | `sql/mysql57/migrations/20261005_admin_export_jobs_v1.sql`、`sql/mysql57/README.md`、数据库设计及本文件 |
| 2026-10-05 | 按业务方要求实现常规关系恢复与改派：恢复仅处理关系撤销来源，改派依据已冻结可报范围及双方名额；身份纠错、迟启动无冻结依据等 TODO-49 特殊边界继续拒绝，无需数据库结构变更 | requirements 0.28；logical-data-model 0.11；business-rules 0.18；state-machine 0.14；user-stories 1.9；functional-modules 0.15；api-design 1.6；todo-register 3.4；本文件 |
| 2026-10-07 | 统一仓库文档的代码实现状态：更新学生/导师/管理员链路入口、TODO-49 限制、TODO-65 师生凭证重置权限/API 缺口、自助找回/文件扫描缺口、自动化测试盘点和目标环境验证边界 | README、AGENTS、项目总览；requirements 0.29；todo-register 3.5；permission-matrix 1.7；functional-modules 0.16；user-stories 2.0；api-design 1.7；logical-data-model、本文件 |

## 后续实现与部署工作

逻辑数据模型、状态机、权限矩阵、用户故事、功能模块、物理数据库设计和已定案 API 契约均已按现有内容定案；`TODO-01` 至 `TODO-48`、`TODO-50` 至 `TODO-64` 已确认并同步，TODO-39 与 TODO-07 的冲突按方案 B 解决，TODO-49 特殊边界继续暂缓，TODO-65 记录师生凭证重置的授权/API 契约缺口并待确认。主要师生互选链路已接入前后端代码；下一阶段应按用户故事补足核心业务的自动化验收和运行验证，而不是将现有流程重新标成未实现。轮次重开和常规关系恢复/改派复用既有表字段，不新增数据库结构。

1. **补齐功能缺口与验收：** 先由业务方确认 TODO-65 中师生凭证重置的执行角色与数据范围，再补齐 API/实现；自助找回渠道目前没有设计。为志愿、逐轮决定、补选、关系/名额原子更新补充正常、权限、状态、重复请求、截止边界及并发测试；完成浏览器端到端验收。TODO-49 仍须等待业务方决策后才能扩展。
2. **完成文件安全接线：** 在部署环境落实恶意文件扫描与私有存储服务；确认上传文件只有扫描通过后才可受控读取，并核验访问审计。
3. **完成目标环境部署核验：** 部署前核对 MySQL 版本、学校数据字段长度和 `utf8mb4_unicode_ci` 语义，准备适用版本的迁移/回滚方案，并落实备份恢复、批量压测、学校身份认证要求核查及总管理员应急恢复流程（登记表 `TODO-24`）。本机验证库的 DDL/迁移状态不构成向其他或生产环境部署的批准；初始脚本已应用，不要对本机迁移库直接重跑。

## 文档维护规则

- 总体需求是范围和共性要求的入口；分轮业务规则是业务语义的详细依据。
- 状态机、权限矩阵和用户故事不得自行推翻已确认规则。
- 待确认事项与已确认决策统一登记在 [待确认事项登记表](todo-register.md)；规则变更先更新登记表，再同步受影响文档。
- 有冲突时记录原表述、影响和处理决定；未确认时在登记表保留为待确认项。
