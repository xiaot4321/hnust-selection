# 文档目录与后续计划

本目录保存师生互选系统的专项设计文档。总体需求和项目入口位于仓库根目录。

## 当前文档

| 文档 | 内容 | 状态 |
|---|---|---|
| [总体需求](../requirements.md) | 范围、角色、流程、功能、共性约束和决策索引 | 0.28 常规关系恢复与改派边界 |
| [分轮业务规则](business-rules.md) | 已确认匹配规则、例外处理及冲突记录 | 0.18 常规关系恢复与改派边界 |
| [状态机设计](state-machine.md) | 批次、阶段、志愿、学生状态、申请和关系状态 | 0.14 常规关系恢复与改派边界 |
| [权限矩阵](permission-matrix.md) | 角色权限、数据范围、敏感字段访问 | 1.6 临时凭证取消到期限制 |
| [用户故事](user-stories.md) | 角色场景和验收要点 | 1.9 常规关系恢复与改派验收 |
| [待确认事项登记表](todo-register.md) | 待确认事项与已确认决策的统一登记，含数据设计输入约束 | 3.4 决策记录；TODO-49 特殊边界暂缓，TODO-50 至 TODO-64 已确认 |
| [逻辑数据模型](logical-data-model.md) | 核心实体关系图、属性、唯一性、状态归属、事务、历史与访问边界 | 0.11 常规关系恢复与改派事务边界；TODO-49 特殊边界暂缓 |
| [物理数据库设计与数据字典](database-design.md) | 逻辑实体到 MySQL/InnoDB 表的映射、字段类型、约束、索引和事务并发设计 | 0.8 管理员异步导出任务；目标 MySQL 5.7.36 |
| [MySQL 5.7.36 完整建库 DDL](../sql/mysql57/schema.sql) | 51 张表、主键、唯一键、索引及 160 个外键的首次建库脚本 | 新库含管理员导出任务表；本机验证库已迁移至 51/160；其他环境部署须准备迁移/回滚方案 |
| [MySQL 前向迁移](../sql/mysql57/migrations/20261003_personnel_management_v1.sql) | 为旧版本机验证库添加资格唯一当前槽位、导入关联及总管理员人员能力 | 2026-10-03 本机结构变更已应用；不得对已迁移库重跑完整迁移 |
| [人员管理迁移恢复脚本](../sql/mysql57/migrations/20261003_personnel_management_v1_recovery.sql) | 修复本机迁移中因 MySQL 会话字符集导致失败的总管理员授权及审计记录 | 2026-10-03 本机恢复成功；授权和审计各写入一行；脚本可重复执行，仅适用于已部分应用 v1 结构的验证库 |
| [凭证取消到期迁移](../sql/mysql57/migrations/20261005_credential_expiry_removal_v1.sql) | 将凭证到期时间改为可空，并清除仍未使用/未撤销凭证的到期时间 | 2026-10-05 已应用到本机验证库；预先备份，3 条凭证中清除 1 条未消费凭证到期时间，50 张表和 158 个外键保持不变 |
| [凭证取消到期回滚脚本](../sql/mysql57/migrations/20261005_credential_expiry_removal_v1_rollback.sql) | 按签发时间恢复 72 小时到期规则 | 仅用于明确的回滚；执行后已超过 72 小时的未用凭证将不可用 |
| [管理员导出任务迁移](../sql/mysql57/migrations/20261005_admin_export_jobs_v1.sql) | 为现有数据库新增管理员异步导出任务表 | 2026-10-05 已应用到本机验证库；执行前备份为 `%TEMP%\hnust_selection_before_admin_export_jobs_20261005_175530.sql`，迁移后 51 张表、160 个外键；其他环境执行前核对版本和回滚方案 |
| [功能模块设计](functional-modules.md) | 学生、导师、管理端功能清单、模块职责、数据归属、模块协作及用户故事追踪 | 0.15 常规关系恢复与改派实现状态 |
| [API 设计](api-design.md) | 端点目录、请求/响应契约、错误码、认证授权、幂等和并发语义 | 1.6 常规关系恢复与改派 |

## 项目代码

根目录 [README](../README.md) 提供运行方法；`backend/` 是 Spring Boot 后端，`frontend/` 是 Vue + TypeScript + Vite 前端。管理员端已实现账号授权、人员/资格/导入、身份纠错、导师资料审核、批次/轮次管理、关系撤销/恢复/改派、冻结统计、异步导出和 `BATCH_AUDIT` 只读视图；导师端已接入常规轮次及补选办理、名额/关系查询、受控简历读取与申请消息。关系恢复/改派复用现有数据库结构，TODO-49 特殊边界仍拒绝；轮次重开也复用现有数据库结构。导出任务迁移已应用到本机验证库。文件扫描器集成与部署运维验证仍待落实；TODO-59 至 TODO-64 已确认。

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

## 定案后的实施与部署工作

逻辑数据模型、状态机、权限矩阵、用户故事、功能模块、物理数据库设计和 API 契约均已按现有内容定案；`TODO-01` 至 `TODO-48`、`TODO-50` 至 `TODO-64` 已确认并同步，TODO-39 与 TODO-07 的冲突按方案 B 解决，TODO-49 特殊边界继续暂缓。目标数据库版本为 MySQL 5.7.36。导师工作台、管理员批次管理与治理 API 已接入；管理员导出任务迁移已应用到本机验证库，其他环境迁移仍须单独核对。学校身份认证、文件扫描器、部署运维与批量压测仍是实施或部署验证项。轮次重开和常规关系恢复/改派复用既有表字段，不新增数据库结构：

1. **按定案基线开发：** 使用功能模块、逻辑/物理数据模型、状态机、权限矩阵和 API 契约建设业务项目。后端按 Java 8、Spring Boot 2.6.6、MyBatis-Plus 3.3.1 和本机 Maven 3.9.1；前端采用 Vue 3、TypeScript 5.2.2、Vite 8.3.2、Node.js 22.20.0 和 npm 10.9.3。首条端到端流程建议覆盖批次配置与冻结、学生提交志愿、导师处理、关系/名额原子更新、轮次结案及结果查询。
2. **部署与运维验证：** 目标环境部署前核对 MySQL 版本、学校数据字段长度和 `utf8mb4_unicode_ci` 语义，准备迁移/回滚脚本，并落实学校认证要求、私有文件存储/扫描、备份恢复、批量压测及总管理员应急恢复流程（登记表 `TODO-24`）。初始脚本已应用到本机验证库，不要直接重跑；本次定稿不自动批准向其他或生产环境部署。

## 文档维护规则

- 总体需求是范围和共性要求的入口；分轮业务规则是业务语义的详细依据。
- 状态机、权限矩阵和用户故事不得自行推翻已确认规则。
- 待确认事项与已确认决策统一登记在 [待确认事项登记表](todo-register.md)；规则变更先更新登记表，再同步受影响文档。
- 有冲突时记录原表述、影响和处理决定；未确认时在登记表保留为待确认项。
