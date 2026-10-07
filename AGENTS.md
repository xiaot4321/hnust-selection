# AGENTS.md

## 1. 项目说明

本项目为“湖南科技大学师生互选系统”。需求和设计基线已确认，主要学生、导师、管理员互选链路已进入代码实现；当前代码范围、未完成事项和验证限制见 [docs/README.md](docs/README.md)。项目目标、业务规则和决策依据见根目录的 [requirements.md](requirements.md)、[docs/business-rules.md](docs/business-rules.md) 与 [docs/todo-register.md](docs/todo-register.md)。

数据库设计已于 2026-10-02 按当前文档版本定案；API 契约当前为 1.6（2026-10-05），主要学生、导师和管理员业务链路已接入代码，具体实现/验收状态见 `docs/README.md`。当前可继续补齐业务边界、自动化验收和前端/后端功能。其他或生产环境建库仍须先核对目标服务版本、准备迁移与回滚方案，并完成部署和运维检查。当前本机验证库已应用初始 DDL、人员管理迁移、凭证取消到期迁移及管理员导出任务迁移；该事实不代表已获准向其他或生产环境部署。

## 2. 业务规则来源

业务实现必须优先参考：

- requirements.md：总体范围与已确认规则。
- docs/business-rules.md：详细业务规则与规则条号（`BR-xx`）。
- docs/state-machine.md：状态对象与转换。
- docs/permission-matrix.md：角色、范围和字段权限。
- docs/user-stories.md：用户场景与验收要点。
- docs/todo-register.md：待确认事项与已确认决策的统一登记（`TODO-xx` / `TODO-Pxx`）；与规则条号 `BR-xx` 不是同一编号体系。
- docs/logical-data-model.md：已确认的逻辑实体、关系、约束和事务边界基线；不能替代已确认业务规则。

逻辑数据模型、功能模块、物理数据库设计/数据字典和已确认 API 契约按现有文档定案；TODO-01 至 TODO-48、TODO-50 至 TODO-64 已确认，TODO-49 由业务方决定暂缓，TODO-65（师生凭证重置权限/API 范围）待确认。TODO-31 确认所有角色初始/重置凭证不设到期时间、仅可成功使用一次；TODO-52 确认仅总管理员可为其他 ADMIN 账号授予/撤销普通业务能力；TODO-56 将总管理员定义为系统级管理员，拥有所有已登记管理员能力和全系统学院/批次范围，但不冒充学生/导师、不绕过业务校验与审计；TODO-57 确认普通 ADMIN 可获授 `BATCH_MANAGER`，并按已授学院或批次范围管理批次；TODO-58 确认新建学生/导师账号初始密码分别取学号/工号末尾六位，编号不足六位时使用完整编号。`ADMIN_ACCOUNT_MANAGER` 仅能通过系统初始化或应急恢复流程设置。目标数据库版本为 MySQL 5.7.36。按业务方明确指令生成的初始建库脚本 `sql/mysql57/schema.sql` 已于 2026-10-01 应用到本机验证库；查询结果确认 49 张表和 151 个外键。人员管理迁移及恢复脚本已于 2026-10-03 执行；凭证取消到期迁移已于 2026-10-05 应用；管理员导出任务迁移于 2026-10-05 按业务方指令备份后应用，当前核验为 51 张表、160 个外键。该本机验证不构成向其他或生产环境部署的批准。

如果文档之间或代码与文档冲突：

1. 不要自行猜测或静默更改业务含义。
2. 明确指出冲突，并在相关文档登记 TODO。
3. 只有业务方确认后，才能把 TODO 改为已确认规则。

## 3. 核心互选规则

系统采用分轮志愿匹配机制，不是标准 Gale-Shapley 算法：

- 学生一次提交一至三位不同导师的有序志愿。
- 学生具有专业和学位类型（学硕/专硕），由管理员导入/纠错，学生只读；填报前须核对，有误向管理员申请。导师按批次设置允许类型和专业，无需审核；配置不完整时默认全勾选，填报开始冻结。提交志愿、常规录取、补选申请与录取均须同时满足类型和专业，补选沿用冻结范围。填报开始后纠错转 UNMATCHED、跳过常规轮次等待补选；已录取关系须撤销并释放名额（TODO-47/48）。普通关系恢复仅处理 `RELATION_REVOKED` 来源；改派检查已冻结范围及新旧名额。迟启动无冻结依据、身份纠错和无补选窗口等 TODO-49 特殊边界继续暂缓。
- 第一、第二、第三轮依次只处理第一、第二、第三志愿。
- 导师逐项选择录取或不录取，不对学生排序。
- 每次录取须校验学生状态与导师剩余名额；录取成功立即锁定关系。
- 已匹配学生不参加后续轮次；三轮均结束后仍未匹配者为 `UNMATCHED`。少于三项志愿者在最后已填顺位轮结束后仍未匹配时也转为 `UNMATCHED`。
- 少于三项志愿且耗尽最后已填顺位仍未匹配者记录 `PREFERENCE_EXHAUSTED` 和实际末轮/顺位，并计入正常志愿流程未匹配。
- 填报、轮次和补选窗口采用 `[startAt,endAt)`；阶段行锁后读取数据库 UTC 时间判定，恰好等于截止时刻拒绝，截止前已通过校验的事务即使稍后提交仍生效；异步批量操作逐项判定（TODO-50）。
- 单次批量操作内部按已确认业务键排序；不同批量操作无全局 FIFO/公平顺序，名额行锁保证不超额，先取得行锁并成功提交的操作可获得最后名额（TODO-51）。
- 管理员须在批次发布前决定是否安排独立补选；安排时预先配置时间。补选不重跑三轮；补选窗口关闭时结案待处理申请并自动完成批次，关闭后不得重开，时间调整须在关闭前完成。
- 填报窗口开放时冻结合格学生名单和统计分母；申请进入处理时保存限定字段资料快照；首期不采集成绩。

已确认规则与边界条件统一登记在 docs/todo-register.md（第 3、4 节为已确认，第 5 节用于登记新待确认项，第 6 节为数据设计输入约束），不得在代码中自行定规。

## 4. 用户角色

- STUDENT：只操作和查看本人业务数据。
- TEACHER：只查看投向本人的当前业务申请和授权资料。
- ADMIN：按获授业务能力及学院/批次数据范围办理管理员业务；批次管理授权见 TODO-57。新建学生/导师账号初始密码按 TODO-58 取对应编号末尾六位；所有账号凭证均不设到期时间、仅能成功使用一次。
- 总管理员：属于 ADMIN 系统角色，拥有全部已登记管理员业务能力和全系统学院/批次范围，另负责管理员账户管理及为其他 ADMIN 账号授予/撤销普通业务能力；不另设学生/导师身份冒充或业务规则绕过权限。`ADMIN_ACCOUNT_MANAGER` 仅能在初始化或应急恢复流程中设置，不通过常规 API 授予、转移或撤销。

## 5. 技术方向

后端技术基线为 Java 8（JDK 8）、Spring Boot 2.6.6、MyBatis-Plus 3.3.1 和 MySQL 5.7.36；本机开发使用 Maven 3.9.1。依赖须兼容该组合；Spring Boot 管理的依赖优先遵循其版本管理。前端使用 Vue 3、TypeScript 5.2.2、Vite 8.3.2、Node.js 22.20.0 和 npm 10.9.3。TypeScript 5.2.2 是本机已安装的较新版本；Vite 8.3.2 本机未安装，按本机 Node.js 版本兼容性选定。

## 6. 未来代码结构建议

后端可按以下职责组织：

controller、service、service.impl、mapper、entity、dto、vo、request、response、config、security、common、exception、enums、utils。

- Controller 负责请求接收、参数校验、权限入口和调用 Service；不承载复杂业务逻辑。
- Service 负责规则、状态与范围校验、事务和并发控制。
- Mapper 负责数据库访问，不承载业务逻辑。
- Controller 不直接返回 Entity；请求和响应使用独立的数据结构。

## 7. API 与异常约定

- API 使用 /api 前缀，统一返回 Result<T>，字段为 code、message、data。新建学生/导师账号初始密码按 TODO-58 使用对应编号末尾六位，管理员账号仍使用随机凭证；所有初始/重置凭证按 TODO-31/58 不设到期时间。
- 业务异常使用统一异常类型和错误码，由全局异常处理器转换响应。
- 具体接口契约见 [docs/api-design.md](docs/api-design.md) 1.7；已确认端点路径、字段及十项技术方案按定案契约实施。师生遗忘密码后的管理员重置端点及授权范围为 TODO-65 待确认。首期认证采用 Spring Security 本地账号和服务端 Session；时间、版本条件、幂等和批量语义以 API 设计及已确认 TODO-50/51 为准，管理员授权和批次管理范围见 TODO-52/57，凭证安全规则见 TODO-31/58。学校身份认证要求、文件扫描/存储和批量上限压测属于实施或部署验证项。

## 8. 数据库与状态原则

- 表名和字段名采用小写下划线风格；Java 属性采用驼峰命名；主键使用 id。
- 关键唯一性和目标明确的强业务引用不能只依赖 Java 代码；数据库设计使用物理唯一约束和外键（多态引用除外），具体方案以已定案的数据库设计为准。Service 仍需校验业务语义、权限和对象范围。
- 志愿记录与最终匹配关系必须分开建模，保留历史；不得仅在学生表增加 teacher_id 代替业务记录。
- 状态变化由服务端动作触发。Service 校验当前状态、批次/轮次、身份权限、导师名额和学生匹配状态。
- Service 负责业务校验、事务边界和跨模块事务编排；Mapper 执行数据库访问与锁定 SQL；InnoDB 负责实际行锁和约束检查。录取、建立关系、名额变化及管理员关系调整须在同一数据库事务内提交，防止重复匹配、超额和重复请求。
- 优先使用数据库唯一/外键约束、条件更新、事务和行锁/乐观锁；不得以 Java `synchronized` 或事务外“先查再写”代替数据库并发控制。确有明确场景需要时才引入 Redis 锁。

## 9. 工作约定

1. 修改前阅读相关文档和已有实现。
2. 任务聚焦必要范围；不做无关重构，不删除既有功能。
3. 未确认的规则标为 TODO 并登记在 docs/todo-register.md，不猜测；已确认规则以登记表为准，不得自行推翻。
4. 重要功能变更同步更新总体需求、业务规则、状态机、权限矩阵或用户故事。
5. 规则变更先更新 docs/todo-register.md，再同步受影响文档并检查交叉引用。
6. 实现阶段的重要业务模块应按项目计划增加覆盖正常、权限、状态、重复提交、边界和并发场景的测试。
7. 当前若任务仅限分析或设计，不提前生成业务代码。

## 10. 模块位置索引与按需读取

本节用于从任务快速定位代码和文档。索引不要求每次读取全部列出的文件：先打开目标模块的入口和对应设计依据，再沿实际调用链读取直接相关的 Service、Repository、请求/响应类型、实体和测试；不要为了熟悉项目而遍历无关目录。只有跨模块任务才扩展到被调用模块。

### 仓库入口

| 位置 | 内容 | 何时读取 |
|---|---|---|
| `README.md` | 项目启动、开发环境和运行说明 | 首次运行项目、调整启动方式或环境变量时 |
| `requirements.md` | 总体范围、角色、流程和共性规则 | 判断功能范围或跨模块行为时；具体业务规则优先定位到专项文档 |
| `docs/README.md` | 设计文档目录及当前实现状态 | 选择本任务需要的设计文档时 |
| `docs/functional-modules.md` | 模块职责、主责/协作关系及用户故事追踪 | 跨模块流程、职责边界或新模块切分时 |
| `docs/api-design.md` | API 路径、请求/响应、权限、错误、幂等与并发契约 | 修改或新增接口时；按目标端点查阅相关部分 |
| `docs/business-rules.md` | 业务规则条号 `BR-xx` | 实现业务判断时；读取相关规则，不默认通读全文 |
| `docs/state-machine.md` | 状态对象及合法转换 | 修改状态或阶段流转时 |
| `docs/permission-matrix.md` | 角色、数据范围和字段权限 | 修改授权、查询范围或敏感字段时 |
| `docs/user-stories.md` | 用户场景和验收要点 | 编写或核对对应场景的验收条件时 |
| `docs/todo-register.md` | 已确认决策与未确认事项 | 核对规则决策、变更业务规则或遇到文档冲突时；区分 `TODO-xx` 与 `BR-xx` |
| `docs/logical-data-model.md` | 逻辑实体、关系、历史和事务边界 | 新增或改变业务实体、关系或事务边界时 |
| `docs/database-design.md` | MySQL 5.7.36 物理表、字段、约束、索引和并发设计 | 修改表结构、SQL、约束或锁策略时 |

### 后端代码 `backend/`

Java 包根目录：`backend/src/main/java/cn/hnust/selection/`。按职责定位：

| 位置 | 职责 |
|---|---|
| `SelectionApplication.java` | Spring Boot 启动入口 |
| `controller/` | HTTP 接口入口和参数校验 |
| `service/`、`service/impl/` | 业务接口与实现；业务规则、事务边界和跨模块编排 |
| `repository/` | 当前数据访问接口和锁定/查询操作；本目录是现有功能的主要持久化入口 |
| `entity/` | 数据库实体映射 |
| `request/`、`vo/` | 请求结构、视图响应结构 |
| `dto/`、`response/` | 跨层传输或响应结构；按实际使用情况定位 |
| `config/`、`security/`、`bootstrap/` | Spring、安全配置、认证上下文和初始化流程 |
| `common/`、`exception/`、`enums/`、`utils/` | 统一响应、异常、枚举及通用工具 |
| `backend/src/main/resources/application.yml` | 应用配置；数据库凭据使用环境变量 |
| `backend/src/test/java/` | 按对应包路径存放测试；修改业务行为时优先查同模块测试 |
| `backend/scripts/` | 本地管理或初始化辅助脚本 |

当前已有业务模块的主要入口：

| 模块 | 后端位置 | 相关前端位置 |
|---|---|---|
| 登录、会话与账号授权 | `controller/AuthController.java`、`service/AccountAuthService.java`、`service/AccountAuthorizationService.java`、对应 `impl/`、`security/`、`config/SecurityConfig.java` | `views/AuthView.vue`、`api/http.ts` |
| 管理员账号生命周期与业务能力授权 | `controller/AdminAccountLifecycleController.java`、`controller/AdminAccountAuthorizationController.java`、对应 `service/`、`service/impl/`、`repository/` | `components/admin/AdminAccountLifecyclePanel.vue`、`components/admin/AdminAuthorizationPanel.vue`、`services/adminAccountLifecycleService.ts`、`services/adminAuthorizationService.ts`、`composables/useAdminAuthorizations.ts` |
| 人员、年度资格与名单导入 | `controller/PersonnelManagementController.java`、`service/Personnel*`、`service/impl/Personnel*`、`utils/PersonnelImportFileParser.java`、相关 `entity/`、`request/`、`vo/` | `components/admin/PersonnelManagementPanel.vue`、`services/personnelManagementService.ts` |
| 学院/专业目录 | `controller/MajorDirectoryController.java`、`entity/CollegeEntity.java`、`entity/MajorEntity.java` 及该控制器实际调用的 Service/Repository | 由人员管理界面使用；修改前从 `PersonnelManagementPanel.vue` 沿调用链确认 |
| 批次与阶段时间配置 | `controller/SelectionBatchManagementController.java`、`service/SelectionBatchManagementService.java`、对应 `impl/`、`repository/SelectionBatchRepository.java`、`service/impl/BatchFillingScheduleJob.java` | `components/admin/BatchManagementPanel.vue`、`services/selectionBatchService.ts` |
| 导师填报范围配置 | `controller/TeacherApplicationScopeController.java`、`service/PersonnelAccessService.java`、对应 `impl/`、`entity/TeacherScopeVersionEntity.java` | `components/teacher/TeacherApplicationScopePanel.vue` |

`backend/src/main/resources/` 保存运行配置；Maven 依赖与构建配置在 `backend/pom.xml`。任务只涉及某个 API 时，从该 Controller 沿调用链追到实际 Service 和 Repository，不要默认阅读全部包。

### 前端代码 `frontend/`

源码根目录：`frontend/src/`。

| 位置 | 职责 |
|---|---|
| `main.ts`、`App.vue` | 应用入口和顶层界面 |
| `views/` | 页面级视图 |
| `components/admin/`、`components/teacher/` | 管理端、导师端功能组件 |
| `services/` | 按业务模块组织的 API 调用 |
| `api/http.ts` | HTTP 请求、会话与统一错误处理 |
| `types/api.ts` | 前端 API 类型 |
| `composables/` | 可复用的 Vue 组合式逻辑 |
| `style.css` | 全局样式 |
| `vite.config.ts`、`tsconfig.json`、`package.json` | Vite、TypeScript 和 npm 配置 |

修改某个页面时，优先读取目标组件/视图、对应 `services/`、`types/api.ts` 和 `api/http.ts` 中确实相关的部分；不要扫描整个 `frontend/src/`。`router/` 和 `stores/` 当前为目录骨架，若后续加入文件再按具体任务定位。

### 数据库脚本 `sql/`

| 位置 | 内容 | 注意事项 |
|---|---|---|
| `sql/mysql57/schema.sql` | MySQL 5.7.36 初始建库 DDL | 仅在确有建库/结构任务时读取；已应用到本机验证库，不要直接重跑 |
| `sql/mysql57/migrations/` | 前向迁移和对应恢复脚本 | 只读取目标迁移及 README；核对迁移适用状态，不要对已迁移库重复执行 |
| `sql/mysql57/testdata/` | 本机/测试用途数据脚本 | 仅测试或验证数据任务需要时读取 |
| `sql/mysql57/README.md` | 脚本用途、执行状态和适用环境说明 | 执行或修改 SQL 脚本前读取 |

### 互选流程实现现状与任务入口

仓库已有主要互选业务代码，不能再把该流程整体标为“待实现”。截至 2026-10-07 的代码盘点如下；“已接入”指相应 Controller/Service 和前端入口存在，不代表全部验收、压测或部署验证已完成：

- **学生端：** 批次/进度和导师目录查询、身份确认、志愿提交/撤回/历史、补选申请、资料与简历、更正申请、通知已接入。
- **导师端：** 本人资料和招生范围、常规轮次及补选申请队列、逐项/批量决定、名额/关系查询、站内消息和受控简历读取已接入。
- **管理端：** 人员/资格/导入、账号授权、身份纠错、导师资料审核、批次生命周期和轮次延期/重开、关系撤销/恢复/改派、统计/审计/异步导出已接入。
- **尚待补齐或验证：** TODO-49 所列特殊边界继续暂缓并由服务端拒绝；自助找回密码没有实现。用户故事要求管理员可重置师生凭证，但执行角色/范围和 API 仍按 TODO-65 待确认（当前只有总管理员重置其他普通 ADMIN 的接口）；简历扫描器集成、目标环境部署/运维核验和核心流程自动化测试仍不足。

处理相关任务时，先从 `docs/functional-modules.md` 和 `docs/README.md` 的当前实现状态表定位入口，再按行为读取 `docs/api-design.md`、相关 `BR-xx`、状态机、权限和数据模型章节，并检查现有调用链。功能清单/设计契约是目标依据，代码存在不代表对应验收已通过；不要把旧文档里的“尚未实现”复制成当前状态。

### 任务到阅读范围

- **后端接口或缺陷：** 目标 Controller → 实际调用的 Service/实现 → Repository → 本次使用的 request/VO/entity → 对应测试；只查该接口关联的 API 和业务规则。
- **前端页面或交互：** 目标 view/component → 对应 service → 相关 API 类型/HTTP 公共逻辑 → 对应用户故事或 API 契约。
- **数据库变更：** 先查逻辑/物理数据模型和迁移 README，再检查目标 DDL/迁移文件及受影响 Repository；不要把修改初始 DDL 当作迁移方案。
- **权限或状态问题：** 查对应矩阵/状态机规则和该行为的接口、服务实现；不要默认需要读取其他业务模块。
- **文档分析或设计：** 只读取目标设计文档及其明确引用；未要求实现时不生成业务代码。

## 11. 具体功能实现任务模板与执行流程

每次用户要求实现具体功能或修复业务行为时，Codex 都必须先按本节模板梳理任务，再直接执行已授权的实现工作。模板是任务分析和完成检查清单，不要求用户重复填写已有的项目资料，也不要求输出逐步内部推理；对用户简要说明本次范围、关键依据或需要处理的阻塞即可。简单且边界清楚的任务不必单独输出计划，但仍须遵守本节约束；不要只给计划而不实现。

### 每次实现都要核对

1. **目标与范围：** 从用户请求确定目标角色、业务场景、预期行为和本次不涉及的内容；结合第 10 节找到当前代码入口。区分已经实现的能力与仅存在于设计文档中的能力。
2. **依据与影响：** 只读取相关模块及其直接调用链，并查阅与本次行为有关的 API 契约、`BR-xx`、状态机、权限、数据模型或 `TODO-xx`。识别受影响的端点、请求/响应、数据库对象、前端组件和相邻功能；无关文档和目录不必读取。
3. **业务检查：** 按任务相关性核对角色权限和数据范围、当前状态与合法转换、窗口边界、重复提交/幂等、名额和并发、历史留痕及字段可见性。某一项与任务无关时无需扩展读取。
4. **不确定项：** 已确认规则优先于代码中的旧行为。发现文档/代码冲突或未确认事项时，不猜测、不静默改变业务含义；按第 2 节登记 TODO，并暂停依赖该决定的实现。与该决定无关的部分可以继续完成。
5. **最小实现：** 遵循既有分层和技术基线，只改完成验收所需的代码；后端按 Controller、Service、Repository 职责实现，前端沿组件、service、类型和 HTTP 层维护契约。避免无关重构、删除既有功能或自行引入新业务规则。
6. **同步与测试：** 行为或规则变化时按第 9 节更新受影响文档。重要业务模块按第 9 节补充正常、权限、状态、重复提交、边界和并发测试；本节不要求在未获用户请求时运行测试命令。
7. **完成检查：** 对照验收条件检查改动和 diff；确认没有遗漏用户要求、越权路径、状态/事务问题或意外文件变更。最终汇报改动位置、覆盖的行为、测试增改与运行情况，以及未解决事项；未运行验证时如实说明。

### 单项功能任务模板

Codex 每次实现时应根据用户请求与仓库资料自动补全下列项目；不要求用户把已有规则再次粘贴到提示词中。

```text
目标与角色：本次要实现什么行为，由哪个角色在什么场景使用？
范围与代码入口：本次包含/不包含什么；当前后端、前端和数据访问入口在哪里？
依据：适用的 API 契约、BR-xx、TODO-xx、状态机、权限和数据模型章节是什么？只列本次相关依据。
验收条件：正常流程应如何工作？相关的权限、状态、重复请求、时间边界、并发和错误场景有哪些？
影响面：需要改哪些接口/字段/表/页面/模块；哪些相邻行为需要保持兼容？
实现约束：遵守哪些分层、技术版本、事务/锁、审计和字段保护要求？
文档与测试：需要同步哪些文档；本次需要新增或调整哪些针对性测试？
验证与完成：本任务要求执行哪些验证；完成后应报告哪些变更、结果和未决事项？
```

上述问题用于引导每次实现，不是额外的审批流程。若验收和规则已经能从用户指令及已确认文档确定，就继续实现；只有依赖未确认业务决定的部分才暂停并说明原因。
