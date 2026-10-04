# 文档目录与后续计划

本目录保存师生互选系统的专项设计文档。总体需求和项目入口位于仓库根目录。

## 当前文档

| 文档 | 内容 | 状态 |
|---|---|---|
| [总体需求](../requirements.md) | 范围、角色、流程、功能、共性约束和决策索引 | 0.23 总管理员系统级权限补充 |
| [分轮业务规则](business-rules.md) | 已确认匹配规则、例外处理及冲突记录 | 0.14 总管理员系统级权限补充 |
| [状态机设计](state-machine.md) | 批次、阶段、志愿、学生状态、申请和关系状态 | 0.12 总管理员系统级权限补充 |
| [权限矩阵](permission-matrix.md) | 角色权限、数据范围、敏感字段访问 | 1.3 总管理员系统级权限补充 |
| [用户故事](user-stories.md) | 角色场景和验收要点 | 1.3 总管理员系统级权限补充 |
| [待确认事项登记表](todo-register.md) | 待确认事项与已确认决策的统一登记，含数据设计输入约束 | 2.7 决策记录；TODO-49 暂缓，TODO-50 至 TODO-56 已确认 |
| [逻辑数据模型](logical-data-model.md) | 核心实体关系图、属性、唯一性、状态归属、事务、历史与访问边界 | 0.8 人员资格唯一当前版本补充；TODO-49 暂缓 |
| [物理数据库设计与数据字典](database-design.md) | 逻辑实体到 MySQL/InnoDB 表的映射、字段类型、约束、索引和事务并发设计 | 0.6 人员管理增补；目标 MySQL 5.7.36 |
| [MySQL 5.7.36 完整建库 DDL](../sql/mysql57/schema.sql) | 50 张表、主键、唯一键、索引及 158 个外键的首次建库脚本 | 本机迁移结构已应用，恢复脚本写入总管理员授权和审计各一行；其他环境部署须准备迁移/回滚方案 |
| [MySQL 前向迁移](../sql/mysql57/migrations/20261003_personnel_management_v1.sql) | 为旧版本机验证库添加资格唯一当前槽位、导入关联及总管理员人员能力 | 2026-10-03 本机结构变更已应用；不得对已迁移库重跑完整迁移 |
| [人员管理迁移恢复脚本](../sql/mysql57/migrations/20261003_personnel_management_v1_recovery.sql) | 修复本机迁移中因 MySQL 会话字符集导致失败的总管理员授权及审计记录 | 2026-10-03 本机恢复成功；授权和审计各写入一行；脚本可重复执行，仅适用于已部分应用 v1 结构的验证库 |
| [功能模块设计](functional-modules.md) | 学生、导师、管理端功能清单、模块职责、数据归属、模块协作及用户故事追踪 | 0.8 总管理员系统级权限补充 |
| [API 设计](api-design.md) | 端点目录、请求/响应契约、错误码、认证授权、幂等和并发语义 | 0.7 总管理员系统级权限补充 |

## 项目代码

根目录 [README](../README.md) 提供运行方法；`backend/` 是 Spring Boot 后端，`frontend/` 是 Vue + TypeScript + Vite 前端。已实现管理员账号生命周期与授权、学院人员/专业/年度资格管理、CSV/XLSX 名单导入及管理界面；批次、志愿、导师办理、关系名额等互选业务仍待实现。数据库凭据从环境变量读取，应用启动时关闭 SQL 初始化，不会自动重跑 DDL 或迁移。

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

## 定案后的实施与部署工作

逻辑数据模型、状态机、权限矩阵、用户故事、功能模块、物理数据库设计和 API 契约均已按现有内容定案；`TODO-01` 至 `TODO-48`、`TODO-50` 至 `TODO-56` 已确认并同步，TODO-39 与 TODO-07 的冲突按方案 B 解决，TODO-49 暂缓。目标数据库版本为 MySQL 5.7.36。其他互选流程可依据文档继续实现；部署和运维验证仍须按下述事项执行：

1. **按定案基线开发：** 使用功能模块、逻辑/物理数据模型、状态机、权限矩阵和 API 契约建设业务项目。后端按 Java 8、Spring Boot 2.6.6、MyBatis-Plus 3.3.1 和本机 Maven 3.9.1；前端采用 Vue 3、TypeScript 5.2.2、Vite 8.3.2、Node.js 22.20.0 和 npm 10.9.3。首条端到端流程建议覆盖批次配置与冻结、学生提交志愿、导师处理、关系/名额原子更新、轮次结案及结果查询。
2. **部署与运维验证：** 目标环境部署前核对 MySQL 版本、学校数据字段长度和 `utf8mb4_unicode_ci` 语义，准备迁移/回滚脚本，并落实学校认证要求、私有文件存储/扫描、备份恢复、批量压测及总管理员应急恢复流程（登记表 `TODO-24`）。初始脚本已应用到本机验证库，不要直接重跑；本次定稿不自动批准向其他或生产环境部署。

## 文档维护规则

- 总体需求是范围和共性要求的入口；分轮业务规则是业务语义的详细依据。
- 状态机、权限矩阵和用户故事不得自行推翻已确认规则。
- 待确认事项与已确认决策统一登记在 [待确认事项登记表](todo-register.md)；规则变更先更新登记表，再同步受影响文档。
- 有冲突时记录原表述、影响和处理决定；未确认时在登记表保留为待确认项。
