# 师生互选系统物理数据库设计与数据字典

> 版本：0.8（管理员异步导出任务）
> 状态：原始 0.5 DDL 已应用到本机 MySQL 5.7.36-log 验证库，核验 49 张表和 151 个外键；0.6 增补学院人员导入业务操作关联、导师导入行引用及年度资格唯一当前槽位。对应向前迁移脚本为 [20261003_personnel_management_v1.sql](../sql/mysql57/migrations/20261003_personnel_management_v1.sql)，0.7 完整建库脚本含 50 张表和 158 个外键。2026-10-03 本机首次迁移的结构变更已应用；随后通过 [迁移恢复脚本](../sql/mysql57/migrations/20261003_personnel_management_v1_recovery.sql) 成功写入总管理员学院业务授权和对应审计记录各一条。0.7 将临时凭证到期时间改为可空，`NULL` 表示不设到期时间；本机验证库已于 2026-10-05 执行向前迁移，50 张表和 158 个外键保持不变。0.8 在完整建库 DDL 中增加 `admin_export_job` 表，并提供 [管理员导出任务迁移](../sql/mysql57/migrations/20261005_admin_export_jobs_v1.sql)；该迁移已于 2026-10-05 在本机验证库应用，迁移前备份为 `%TEMP%\hnust_selection_before_admin_export_jobs_20261005_175530.sql`。当前核验为 51 张表和 160 个外键。其他或生产环境执行迁移前须备份并核对回滚方案；本机升级不构成生产部署批准。
> 更新日期：2026-10-05
> 基线：[总体需求](../requirements.md) 0.27、[逻辑数据模型](logical-data-model.md) 0.10、[待确认事项登记表](todo-register.md) 3.3 第 6 节。时间存储约定沿用 [API 设计](api-design.md) 1.5 的 UTC 约定。

## 1. 范围与设计原则

本设计把逻辑模型映射为 MySQL 5.7.36 关系表、字段、键、索引、引用和事务不变量，作为已定案的数据实现基线，与已确认的 API 契约协同使用。可执行建库脚本独立维护在 `sql/mysql57/schema.sql`；脚本按本稿生成，并已按业务方明确指令应用到本机验证库。该次应用不授权在其他或生产环境部署。

设计按逻辑模型分解业务事实，不把学生全局匹配状态写入学生身份表；志愿版本、申请处理、关系、名额流水和审计各自留存。当前状态列是受控查询投影，须与追加历史一致。成绩不建模。`TODO-49` 所列情形仍排除在本期范围之外。

### 1.1 MySQL 实现基线

| 项目 | 设计 |
|---|---|
| 存储引擎 | 所有业务表使用 `InnoDB`，以获得事务、行级锁和外键支持；不混用非事务引擎。 |
| 字符集 | 数据库、表及连接统一使用 `utf8mb4`，排序规则按已确认 DDL 使用 `utf8mb4_unicode_ci`。导入学校数据前核对大小写/重音比较语义；若与业务标识要求冲突，按数据库设计变更流程调整，不能依赖服务器默认值。 |
| 主键生成 | 普通业务表使用 `BIGINT AUTO_INCREMENT` 主键；纯槽位表保留复合主键。 |
| 时间存储 | 时间字段使用 `DATETIME(3)`，应用按 UTC 写入和读取，数据库连接统一约定时区；避免依赖连接时区自动转换。 |
| 事务和锁 | 录取、关系调整、批次发布等使用 InnoDB 事务；对名额、学生年度匹配槽位和待处理补选槽位执行条件更新或 `SELECT ... FOR UPDATE`，并以唯一键作最终并发防线。 |
| 外键索引 | 显式规划引用列和索引，并使外键两侧字段类型、长度及有符号属性一致。InnoDB 要求外键引用列有索引；缺少子表索引时 MySQL 可以自动创建，因此定稿 DDL 应显式声明以便审查和稳定索引命名。[MySQL 5.7 外键文档](https://dev.mysql.com/doc/refman/5.7/en/constraint-foreign-key.html) |
| 检查约束 | MySQL 5.7.36 会解析但忽略 `CHECK`，所以本文不使用 CHECK；字段组合、状态和跨行规则全部由服务端校验，并以主键/唯一键/外键保护可表达的不变量。[MySQL 5.7 约束说明](https://dev.mysql.com/doc/refman/5.7/en/alter-table.html) |
| 严格模式 | 部署时启用严格 SQL mode，避免无效值被静默截断或转换；将实际 SQL mode 纳入环境配置和启动检查。 |

MySQL 5.7 不提供通用的部分唯一索引；本设计以 `batch_running_slot`、`student_year_match_slot`、`student_pending_supplement_slot` 和 `teacher_application_scope_slot` 将条件唯一性落实为普通唯一键/主键，不依赖生成列或表达式索引。

版本维护说明：MySQL 官方资料记录 5.7 系列主动开发于 2023-10-25 随 5.7.44 结束；给定的 5.7.36 早于该分支最终补丁版本。设计按当前指定版本保持兼容，同时将生产环境的支持周期与升级路径列为部署评审事项。[MySQL 版本 FAQ](https://dev.mysql.com/doc/refman/26.7/en/faqs-general.html)、[Oracle 关于 5.7 支持阶段的说明](https://blogs.oracle.com/mysql/mysql-october-ga-releases-now-available)

### 1.2 字段与类型约定

| 约定 | 设计 |
|---|---|
| 主键 | 业务实体表使用 `id BIGINT AUTO_INCREMENT PRIMARY KEY`；并发槽位/指针辅助表以业务唯一键作为复合主键，不额外要求 `id`。关联一律用内部主键，学号、工号等仅作唯一业务键。 |
| 字符串 | `VARCHAR(n)`；短编码使用 `VARCHAR(32)`，业务标识、学号/工号长度按学校数据格式核定。超长说明使用 `TEXT`。 |
| 数值 | 名额、计数、版本号、顺序号使用 `INT`/`SMALLINT`；内部并发版本和主键使用 `BIGINT`。除允许负数的流水变化量外，数值由服务端限制为非负。 |
| 布尔 | 字典中的 `BOOLEAN` 使用 MySQL 布尔别名（底层 `TINYINT(1)`）；应用只写 0/1。 |
| 时间 | 时间字段统一用 `DATETIME(3)` 按 UTC 约定保存，展示时由应用转换时区；日期字段用 `DATE`。 |
| 枚举 | 状态/类型存 `VARCHAR(32)` 或合适长度的编码，不使用 MySQL 原生 `ENUM`；服务端校验已登记值。MySQL 5.7.36 不执行 CHECK，本文不以 CHECK 约束正确性。新增值须同步状态机与数据字典。 |
| JSON 类内容 | 不把集合塞入逗号分隔字符串。少量审计前后值采用带格式版本的 JSON 文本列，排除凭证、密码、完整简历和未授权字段；结构化业务集合单独建关联表。 |
| 空值 | 仅表示尚未发生或确实可选的属性；状态未知不得用业务含义不明的空字符串/零值代替。 |
| 删除 | 业务主表及历史表不做级联物理删除；外键使用 `ON DELETE RESTRICT`。文件清理按留存政策单独受控。 |

可变业务表使用 `created_at`、`updated_at`、`row_version`（乐观并发版本）；不可变历史表使用 `created_at`/`occurred_at` 与必要主体字段，不要求无意义的 `updated_at`。敏感写操作另记业务操作和审计事件。

## 2. 逻辑实体到物理表映射

表名统一小写下划线。下表保留逻辑模型的实体拆分；`*_slot` 表及范围冻结指针是并发唯一性辅助表，只保存当前占用/选定指针，不替代业务事实或历史。

| 逻辑实体 | 物理表 | 逻辑实体 | 物理表 |
|---|---|---|---|
| E01 College | `college` | E02 AcademicYear | `academic_year` |
| E03 Account | `account` | E04 AccountAuthorization | `account_authorization` |
| E05 TemporaryCredential | `temporary_credential` | E06 Student | `student` |
| E07 StudentProfileVersion | `student_profile_version` | E08 Teacher | `teacher` |
| E09 TeacherPublicProfileVersion | `teacher_public_profile_version` | E10 AnnualEligibility | `annual_eligibility` |
| E40 Major | `major` | E43 StudentClassificationRevision | `student_classification_revision` |
| E44 StudentIdentityCorrectionRequest | `student_identity_correction_request` | E11 SelectionBatch | `selection_batch` |
| E12 BatchRuleSnapshot | `batch_rule_snapshot` | E13 BatchStage | `batch_stage` |
| E14 ScheduleRevision | `schedule_revision` | E15 BatchLifecycleEvent | `batch_lifecycle_event` |
| E16 BatchStudent | `batch_student` | E17 BatchTeacherQuota | `batch_teacher_quota` |
| E41 TeacherApplicationScopeVersion | `teacher_application_scope_version` | E42 TeacherAllowedMajor | `teacher_allowed_major` |
| E18 PreferenceSubmission | `preference_submission` | E19 PreferenceItem | `preference_item` |
| E20 RoundApplication | `round_application` | E21 SupplementWindow | `supplement_window` |
| E22 SupplementTeacher | `supplement_teacher` | E23 SupplementApplication | `supplement_application` |
| E24 MatchingRelation | `matching_relation` | E25 StudentMatchEvent | `student_match_event` |
| E26 ApplicationEvent | `application_event` | E27 QuotaLedger | `quota_ledger` |
| E28 RelationAdjustment | `relation_adjustment` | E29 BusinessOperation | `business_operation` |
| E30 BulkOperationItem | `bulk_operation_item` | E31 ManagedFile | `managed_file` |
| E32 PersonnelImport | `personnel_import` | E33 PersonnelImportRow | `personnel_import_row` |
| E34 SiteNotice | `site_notice` | E35 NoticeRecipient | `notice_recipient` |
| E36 DeliveryAttempt | `delivery_attempt` | E37 DataAccessRecord | `data_access_record` |
| E38 AuditEvent | `audit_event` | E39 ApplicationProfileSnapshot | `application_profile_snapshot` |
| E45 AdminExportJob | `admin_export_job` |  |  |
| 并发唯一性辅助 | `batch_running_slot` | 并发唯一性辅助 | `student_year_match_slot` |
| 并发唯一性辅助 | `student_pending_supplement_slot` |  |  |
| 冻结范围唯一指针 | `teacher_application_scope_slot` |  |  |
| 分类纠错影响明细 | `student_classification_impact` |  |  |

## 3. 数据字典

以下字段清单列出主键外的业务字段；通用时间/并发字段按 §1.1 适用。`FK` 表示重点数据库外键，字段清单未逐项重复所有外键；完整外键清单以已确认的 `sql/mysql57/schema.sql` 为准。后续迁移脚本沿用约束语义。无物理外键的通用对象引用仅用于审计关联，应用须验证其真实对象。

### 3.1 身份、授权和人员

| 表 | 字段及类型 | 主键、唯一键和说明 |
|---|---|---|
| `college` | `college_code VARCHAR(32)`, `name VARCHAR(128)`, `is_active BOOLEAN` | `UNIQUE(college_code)`；首期学院范围按授权配置。 |
| `academic_year` | `year_code VARCHAR(16)`, `display_name VARCHAR(64)`, `starts_on DATE NULL`, `ends_on DATE NULL` | `UNIQUE(year_code)`；学年代码不与学生入学年份混用。 |
| `major` | `college_id BIGINT`, `major_code VARCHAR(32)`, `name VARCHAR(128)`, `is_active BOOLEAN`, `valid_from DATE NULL`, `valid_to DATE NULL`, `change_basis TEXT NULL` | `FK(college_id)`、`UNIQUE(college_id, major_code)`；专业停用/更名不删除旧引用。 |
| `account` | `login_identifier VARCHAR(128)`, `role_code VARCHAR(16)`, `account_status VARCHAR(16)`, `password_hash VARCHAR(255) NULL`, `must_change_password BOOLEAN`, `credential_changed_at DATETIME(3) NULL`, `last_login_at DATETIME(3) NULL` | `UNIQUE(login_identifier)` 全系统唯一；跨角色重号导入报错，不自动合并身份。密码仅存安全哈希。 |
| `account_authorization` | `account_id BIGINT`, `college_id BIGINT`, `batch_id BIGINT NULL`, `capability_code VARCHAR(48)`, `authority_slot VARCHAR(48) NULL`, `basis TEXT`, `granted_by BIGINT`, `granted_at DATETIME(3)`, `revoked_by BIGINT NULL`, `revoked_at DATETIME(3) NULL` | FK 至账号/学院/批次/操作者；授权范围及撤销时间留史。`authority_slot` 用唯一约束保证 `ADMIN_ACCOUNT_MANAGER` 仅一项当前授权（撤销时清空槽位或追加新授权行）。 |
| `temporary_credential` | `account_id BIGINT`, `credential_hash VARCHAR(255)`, `issued_at DATETIME(3)`, `expires_at DATETIME(3) NULL`, `shown_at DATETIME(3) NULL`, `used_at DATETIME(3) NULL`, `revoked_at DATETIME(3) NULL`, `issued_operation_id BIGINT` | FK 至账号/业务操作；仅保存哈希；`expires_at=NULL` 表示凭证不设到期时间；成功使用一次或撤销后失效，不可读回明文。 |
| `student` | `account_id BIGINT`, `student_no VARCHAR(64)`, `full_name VARCHAR(128)`, `college_id BIGINT`, `major_id BIGINT`, `degree_type VARCHAR(32)`, `classification_version INTEGER`, `enrollment_year_code VARCHAR(16)`, `graduation_date DATE NULL`, `graduation_date_basis TEXT NULL`, `graduation_date_confirmed_at DATETIME(3) NULL` | `UNIQUE(student_no)`, `UNIQUE(account_id)`；FK 至学院/专业/账号。毕业日期未确认时附件不自动清理。 |
| `teacher` | `account_id BIGINT`, `employee_no VARCHAR(64)`, `full_name VARCHAR(128)`, `college_id BIGINT`, `current_public_profile_version_id BIGINT NULL` | `UNIQUE(employee_no)`, `UNIQUE(account_id)`；公开资料当前指针须属于该导师。 |
| `student_profile_version` | `student_id BIGINT`, `version_no INTEGER`, `biography TEXT NULL`, `contact_text VARCHAR(255) NULL`, `resume_file_id BIGINT NULL`, `changed_by BIGINT`, `changed_at DATETIME(3)` | `UNIQUE(student_id, version_no)`；FK 至学生、文件、账号；历史版本不可覆盖。 |
| `teacher_public_profile_version` | `teacher_id BIGINT`, `version_no INTEGER`, `research_directions TEXT NULL`, `biography TEXT NULL`, `review_status VARCHAR(24)`, `submitted_at DATETIME(3)`, `reviewed_by BIGINT NULL`, `reviewed_at DATETIME(3) NULL`, `review_comment TEXT NULL`, `published_at DATETIME(3) NULL` | `UNIQUE(teacher_id, version_no)`；公开目录只读审核通过版本。 |
| `annual_eligibility` | `academic_year_id BIGINT`, `college_id BIGINT`, `student_id BIGINT NULL`, `teacher_id BIGINT NULL`, `eligibility_status VARCHAR(24)`, `evidence_type VARCHAR(32)`, `evidence_reference TEXT NULL`, `source_name VARCHAR(128) NULL`, `valid_from DATETIME(3) NULL`, `valid_to DATETIME(3) NULL`, `changed_by BIGINT` | FK 至学年/学院/学生/导师；服务端校验 `student_id` 与 `teacher_id` 恰有一个非空；变更追加历史行，旧当前行设置 `valid_to`。状态值为 `ELIGIBLE`/`INELIGIBLE`。 |
| `annual_eligibility_slot` | `academic_year_id BIGINT`, `college_id BIGINT`, `student_id BIGINT NULL`, `teacher_id BIGINT NULL`, `eligibility_id BIGINT`, `claimed_at DATETIME(3)` | 当前资格投影；`UNIQUE(academic_year_id, student_id)`、`UNIQUE(academic_year_id, teacher_id)`、`UNIQUE(eligibility_id)` 保证每人每学年最多一条当前资格；学生/导师引用恰有一个非空由 Service 校验，人员行锁串行化修改。 |
| `student_classification_revision` | `student_id BIGINT`, `version_no INTEGER`, `from_major_id BIGINT NULL`, `to_major_id BIGINT`, `from_degree_type VARCHAR(32) NULL`, `to_degree_type VARCHAR(32)`, `basis TEXT`, `reason TEXT`, `changed_by BIGINT`, `changed_at DATETIME(3)`, `correction_request_id BIGINT NULL` | `UNIQUE(student_id, version_no)`；前值可空表示初次导入；受影响批次、志愿与关系处置通过 `student_classification_impact` 逐条关联。 |
| `student_classification_impact` | `revision_id BIGINT`, `impact_no INTEGER`, `batch_id BIGINT`, `impact_type VARCHAR(32)`, `preference_submission_id BIGINT NULL`, `relation_adjustment_id BIGINT NULL`, `occurred_at DATETIME(3)` | `UNIQUE(revision_id, impact_no)`；以明细关系保存身份纠错波及的批次、志愿和关系处置，不把业务 ID 拼成字符串。 |
| `student_identity_correction_request` | `student_id BIGINT`, `submitted_at DATETIME(3)`, `current_classification_version INTEGER`, `requested_major_id BIGINT NULL`, `requested_degree_type VARCHAR(32) NULL`, `student_explanation TEXT`, `request_status VARCHAR(24)`, `handled_by BIGINT NULL`, `handled_at DATETIME(3) NULL`, `handling_comment TEXT NULL`, `resulting_revision_id BIGINT NULL` | FK 至学生/专业/操作者/分类版本；状态与实际身份修改分开保存，申请不自动更新学生身份。 |
| `managed_file` | `owner_account_id BIGINT`, `purpose_code VARCHAR(32)`, `original_filename VARCHAR(255)`, `media_type VARCHAR(128)`, `file_size_bytes BIGINT`, `content_digest VARCHAR(128)`, `storage_key VARCHAR(512)`, `uploaded_at DATETIME(3)`, `retention_basis VARCHAR(32)`, `retention_until DATE NULL`, `file_status VARCHAR(16)` | FK 至所有者；`storage_key` 是内部受控定位符，不得作为公开下载 URL。简历限定 PDF、≤10MB；日期未确认则 `retention_until` 为空且不自动删除。 |

### 3.2 批次、阶段、名单和导师设置

| 表 | 字段及类型 | 主键、唯一键和说明 |
|---|---|---|
| `selection_batch` | `college_id BIGINT`, `academic_year_id BIGINT`, `batch_code VARCHAR(48)`, `name VARCHAR(128)`, `batch_status VARCHAR(24)`, `current_stage_id BIGINT NULL`, `supplement_planned BOOLEAN`, `frozen_roster_at DATETIME(3) NULL`, `created_by BIGINT`, `published_at DATETIME(3) NULL`, `started_at DATETIME(3) NULL`, `completed_at DATETIME(3) NULL`, `archived_at DATETIME(3) NULL`, `cancelled_at DATETIME(3) NULL`, `append_reason TEXT NULL` | `UNIQUE(college_id, academic_year_id, batch_code)`；同学院学年运行唯一由 `batch_running_slot` 保证。 |
| `batch_rule_snapshot` | `batch_id BIGINT`, `version_no INTEGER`, `scope_text TEXT`, `eligibility_basis TEXT`, `min_preferences SMALLINT`, `max_preferences SMALLINT`, `round_rule_version VARCHAR(32)`, `source_document_versions TEXT`, `published_by BIGINT`, `published_at DATETIME(3)` | `UNIQUE(batch_id, version_no)`；已发布批次引用明确快照；不作为任意算法规则引擎。 |
| `batch_stage` | `batch_id BIGINT`, `stage_code VARCHAR(24)`, `stage_order SMALLINT`, `stage_status VARCHAR(24)`, `planned_start_at DATETIME(3) NULL`, `planned_end_at DATETIME(3) NULL`, `effective_start_at DATETIME(3) NULL`, `effective_end_at DATETIME(3) NULL`, `actual_started_at DATETIME(3) NULL`, `actual_closed_at DATETIME(3) NULL`, `close_reason VARCHAR(32) NULL`, `execution_cycle INTEGER` | `UNIQUE(batch_id, stage_code)`；标准代码为 FILLING/ROUND_1/ROUND_2/ROUND_3/SUPPLEMENT；补选阶段时间可空，权威窗口时间仍在 `supplement_window`。 |
| `schedule_revision` | `batch_id BIGINT`, `stage_id BIGINT NULL`, `supplement_window_id BIGINT NULL`, `revision_no INTEGER`, `revision_type VARCHAR(24)`, `old_start_at DATETIME(3) NULL`, `old_end_at DATETIME(3) NULL`, `new_start_at DATETIME(3) NULL`, `new_end_at DATETIME(3) NULL`, `relative_start_offset_seconds BIGINT NULL`, `relative_end_offset_seconds BIGINT NULL`, `actor_account_id BIGINT`, `reason TEXT`, `occurred_at DATETIME(3)` | `UNIQUE(batch_id, revision_no)`；阶段与补选窗口恰有一个目标；保存完整修订链而非覆盖原计划。 |
| `batch_lifecycle_event` | `batch_id BIGINT`, `action_code VARCHAR(32)`, `old_status VARCHAR(24) NULL`, `new_status VARCHAR(24)`, `stage_id BIGINT NULL`, `actor_account_id BIGINT NULL`, `actor_kind VARCHAR(16)`, `reason TEXT NULL`, `occurred_at DATETIME(3)`, `pause_started_at DATETIME(3) NULL`, `pause_ended_at DATETIME(3) NULL`, `frozen_stage_code VARCHAR(24) NULL`, `pause_duration_seconds BIGINT NULL`, `business_operation_id BIGINT NULL` | 追加式生命周期记录；系统主体允许无账号；同批次暂停/恢复区间须成对。 |
| `batch_student` | `batch_id BIGINT`, `student_id BIGINT`, `eligibility_snapshot VARCHAR(24)`, `account_enabled_snapshot BOOLEAN`, `eligibility_basis TEXT`, `identity_confirmed_at DATETIME(3) NULL`, `confirmed_classification_version INTEGER NULL`, `preference_status VARCHAR(24)`, `current_submission_id BIGINT NULL`, `final_submission_id BIGINT NULL`, `match_status VARCHAR(24) NULL`, `match_reason VARCHAR(40) NULL`, `match_changed_at DATETIME(3) NULL`, `current_relation_id BIGINT NULL`, `roster_correction_note TEXT NULL` | `UNIQUE(batch_id, student_id)`；在填报窗口开启时冻结分母名单；当前状态与事件/关系必须一致。 |
| `batch_teacher_quota` | `batch_id BIGINT`, `teacher_id BIGINT`, `eligibility_basis TEXT`, `quota_limit INTEGER`, `occupied_count INTEGER` | `UNIQUE(batch_id, teacher_id)`；`0 <= occupied_count <= quota_limit`；剩余数派生；账户更新须与关系/流水同事务，通用 `row_version` 用于并发控制。 |
| `teacher_application_scope_version` | `batch_id BIGINT`, `teacher_id BIGINT`, `version_no INTEGER`, `allowed_degree_mask SMALLINT`, `configured_by BIGINT`, `configured_at DATETIME(3)`, `frozen_at DATETIME(3) NULL`, `scope_source VARCHAR(24)`, `default_all_applied BOOLEAN` | `UNIQUE(batch_id, teacher_id, version_no)`；学位掩码 1=学硕、2=专硕、3=两者；配置不完整时记录默认全选，冻结后不可普通修改。 |
| `teacher_allowed_major` | `scope_version_id BIGINT`, `major_id BIGINT`, `major_code_snapshot VARCHAR(32)`, `major_name_snapshot VARCHAR(128)` | `UNIQUE(scope_version_id, major_id)`；不以逗号字符串存专业集合。配置不完整默认全选时，在填报开始冻结范围的事务中按当时目录物化允许专业项，后续目录变化不扩大已冻结集合。 |
| `batch_running_slot` | `college_id BIGINT`, `academic_year_id BIGINT`, `batch_id BIGINT`, `reserved_at DATETIME(3)` | `PRIMARY KEY(college_id, academic_year_id)`, `UNIQUE(batch_id)`；发布进入 SCHEDULED 时占位；完成、取消或撤回发布回到 DRAFT 时释放；暂停仍占位。与批次状态在同一事务维护。 |
| `teacher_application_scope_slot` | `batch_id BIGINT`, `teacher_id BIGINT`, `scope_version_id BIGINT`, `frozen_at DATETIME(3)` | `PRIMARY KEY(batch_id, teacher_id)`, `UNIQUE(scope_version_id)`；填报开始时指向该导师该批次唯一冻结版本；冻结版本不可替换。 |

### 3.3 志愿、申请和处理

| 表 | 字段及类型 | 主键、唯一键和说明 |
|---|---|---|
| `preference_submission` | `batch_student_id BIGINT`, `version_no INTEGER`, `item_count SMALLINT`, `submitted_at DATETIME(3)`, `submitted_by BIGINT`, `locked_at DATETIME(3) NULL`, `submission_status VARCHAR(16)` | `UNIQUE(batch_student_id, version_no)`；提交内容不可更新；状态变更另写 `application_event`。 |
| `preference_item` | `submission_id BIGINT`, `preference_order SMALLINT`, `batch_teacher_quota_id BIGINT`, `scope_version_id BIGINT`, `classification_version INTEGER`, `major_id BIGINT`, `degree_type VARCHAR(32)` | `UNIQUE(submission_id, preference_order)`, `UNIQUE(submission_id, batch_teacher_quota_id)`；顺位 1–3 连续，范围/身份校验依据固定。 |
| `round_application` | `batch_student_id BIGINT`, `stage_id BIGINT`, `preference_item_id BIGINT`, `teacher_id BIGINT`, `application_status VARCHAR(32)`, `close_reason VARCHAR(40) NULL`, `entered_review_at DATETIME(3) NULL`, `decided_at DATETIME(3) NULL`, `execution_cycle INTEGER`, `sort_submitted_at DATETIME(3) NULL`, `sort_student_no VARCHAR(64) NULL`, `active_snapshot_id BIGINT NULL` | `UNIQUE(preference_item_id)` 表示一个锁定志愿项一条当前申请；重开以 `execution_cycle` 和事件历史表示。记录应可验证 `stage` 对应志愿顺位。 |
| `supplement_window` | `batch_id BIGINT`, `stage_id BIGINT`, `planned_start_at DATETIME(3)`, `planned_end_at DATETIME(3)`, `effective_start_at DATETIME(3)`, `effective_end_at DATETIME(3)`, `actual_started_at DATETIME(3) NULL`, `actual_closed_at DATETIME(3) NULL`, `window_status VARCHAR(24)`, `close_reason VARCHAR(32) NULL` | `UNIQUE(batch_id)`；发布前计划配置；关闭后不得重开，关闭待处理申请并完成批次。 |
| `supplement_teacher` | `supplement_window_id BIGINT`, `batch_teacher_quota_id BIGINT`, `permission_version INTEGER`, `allowed_from DATETIME(3)`, `revoked_at DATETIME(3) NULL`, `granted_by BIGINT`, `reason TEXT NULL` | `UNIQUE(supplement_window_id, batch_teacher_quota_id, permission_version)`；每次重新许可追加版本，最高版本且未撤销者表示当前许可；名额和范围仍单独检查。 |
| `supplement_application` | `batch_student_id BIGINT`, `supplement_window_id BIGINT`, `teacher_id BIGINT`, `batch_teacher_quota_id BIGINT`, `application_status VARCHAR(24)`, `close_reason VARCHAR(32) NULL`, `submitted_at DATETIME(3)`, `decided_by BIGINT NULL`, `decided_at DATETIME(3) NULL`, `sort_submitted_at DATETIME(3)`, `sort_student_no VARCHAR(64)`, `active_snapshot_id BIGINT NULL` | 每次申请独立留存；同一学生待处理唯一由 `student_pending_supplement_slot` 保证；录取时不允许仅改申请状态而不建关系。 |
| `application_profile_snapshot` | `round_application_id BIGINT NULL`, `supplement_application_id BIGINT NULL`, `execution_cycle INTEGER`, `captured_at DATETIME(3)`, `capture_reason VARCHAR(24)`, `full_name VARCHAR(128)`, `student_no VARCHAR(64)`, `major_id BIGINT`, `major_name_snapshot VARCHAR(128)`, `degree_type VARCHAR(32)`, `biography TEXT NULL`, `resume_file_id BIGINT NULL` | 服务端校验常规申请与补选申请外键恰有一个非空；常规重开每周期新快照，补选提交时建快照；禁止联系方式/成绩字段。当前访问仍即时鉴权。 |
| `application_event` | `object_type VARCHAR(24)`, `object_id BIGINT`, `execution_cycle INTEGER NULL`, `action_code VARCHAR(32)`, `old_status VARCHAR(32) NULL`, `new_status VARCHAR(32) NULL`, `close_reason VARCHAR(40) NULL`, `actor_kind VARCHAR(16)`, `actor_account_id BIGINT NULL`, `occurred_at DATETIME(3)`, `business_operation_id BIGINT NULL`, `detail_text TEXT NULL` | 追加式事件；对象引用为通用类型/ID，应用按对象类型校验。区分导师不录取、超时、名额不足、时间跳过、身份纠错跳过、匹配跳过、取消和重开。 |
| `student_pending_supplement_slot` | `student_id BIGINT`, `supplement_application_id BIGINT`, `claimed_at DATETIME(3)` | `PRIMARY KEY(student_id)`, `UNIQUE(supplement_application_id)`；仅待处理期间存在；处理/取消时与申请状态同事务释放。 |

### 3.4 关系、名额与业务操作

| 表 | 字段及类型 | 主键、唯一键和说明 |
|---|---|---|
| `matching_relation` | `batch_student_id BIGINT`, `batch_teacher_quota_id BIGINT`, `academic_year_id BIGINT`, `relation_status VARCHAR(16)`, `source_type VARCHAR(24)`, `source_id BIGINT`, `created_by_operation_id BIGINT`, `original_relation_id BIGINT NULL`, `locked_at DATETIME(3)`, `revoked_at DATETIME(3) NULL` | 所有关系历史保留；导师改派创建新关系并引用原关系。当前有效唯一性由 `student_year_match_slot` 和批次学生当前关系指针共同约束。 |
| `student_year_match_slot` | `student_id BIGINT`, `academic_year_id BIGINT`, `relation_id BIGINT`, `claimed_at DATETIME(3)` | `PRIMARY KEY(student_id, academic_year_id)`, `UNIQUE(relation_id)`；仅指向当前 LOCKED 关系；录取/恢复时占位，撤销时释放，与关系和名额同事务。 |
| `student_match_event` | `batch_student_id BIGINT`, `old_status VARCHAR(24) NULL`, `new_status VARCHAR(24)`, `reason_code VARCHAR(40)`, `actual_round SMALLINT NULL`, `actual_preference_order SMALLINT NULL`, `source_type VARCHAR(24) NULL`, `source_id BIGINT NULL`, `occurred_at DATETIME(3)`, `business_operation_id BIGINT NULL` | 追加式状态历史；少填耗尽记录实际末轮/顺位；原因与导师处理结果分开。 |
| `quota_ledger` | `batch_teacher_quota_id BIGINT`, `change_type VARCHAR(24)`, `limit_before INTEGER`, `limit_after INTEGER`, `occupied_before INTEGER`, `occupied_after INTEGER`, `delta INTEGER`, `relation_id BIGINT NULL`, `business_operation_id BIGINT`, `actor_account_id BIGINT NULL`, `reason TEXT NULL`, `occurred_at DATETIME(3)` | 追加式核对流水；同一操作对同一账户只记一次对应变动。 |
| `relation_adjustment` | `batch_id BIGINT`, `student_id BIGINT`, `adjustment_type VARCHAR(16)`, `old_relation_id BIGINT NULL`, `new_relation_id BIGINT NULL`, `old_teacher_id BIGINT NULL`, `new_teacher_id BIGINT NULL`, `reason TEXT`, `approval_comment TEXT NULL`, `actor_account_id BIGINT`, `occurred_at DATETIME(3)`, `business_operation_id BIGINT` | 撤销/恢复/改派审计；改派须关联旧/新关系及两边名额流水；归档批次先经审计解除归档。 |
| `business_operation` | `actor_account_id BIGINT NULL`, `actor_kind VARCHAR(16)`, `action_code VARCHAR(48)`, `college_id BIGINT NULL`, `batch_id BIGINT NULL`, `request_id VARCHAR(128) NULL`, `request_fingerprint VARCHAR(128) NULL`, `result_code VARCHAR(32)`, `started_at DATETIME(3)`, `completed_at DATETIME(3) NULL`, `sorting_rule_version VARCHAR(32) NULL`, `scope_version_id BIGINT NULL`, `classification_version INTEGER NULL` | 幂等请求键按操作者+动作+请求 ID 唯一；相同请求 ID、不同指纹拒绝；自动事件也可记录系统主体。请求保存摘要，不留存敏感原文。 |
| `bulk_operation_item` | `business_operation_id BIGINT`, `application_type VARCHAR(24)`, `application_id BIGINT`, `sort_submitted_at DATETIME(3)`, `sort_student_no VARCHAR(64)`, `execution_order INTEGER`, `item_result VARCHAR(24)`, `failure_reason VARCHAR(40) NULL`, `admission_operation_id BIGINT NULL` | `UNIQUE(business_operation_id, execution_order)`；明确记录确定性顺序和逐项结果，不按界面勾选顺序。 |

### 3.5 导入、通知、文件访问和审计

| 表 | 字段及类型 | 主键、唯一键和说明 |
|---|---|---|
| `personnel_import` | `actor_account_id BIGINT`, `college_id BIGINT`, `academic_year_id BIGINT NULL`, `person_type VARCHAR(16)`, `template_version VARCHAR(32)`, `source_file_id BIGINT`, `business_operation_id BIGINT`, `import_status VARCHAR(24)`, `submitted_at DATETIME(3)`, `completed_at DATETIME(3) NULL`, `accepted_count INTEGER`, `rejected_count INTEGER`, `error_report_file_id BIGINT NULL` | `UNIQUE(business_operation_id)`；导入批次独立于互选批次；源文件在 Web 根目录外私有存储，历史状态和逐行结果可审计。 |
| `personnel_import_row` | `import_id BIGINT`, `row_number INTEGER`, `person_identifier VARCHAR(64)`, `row_status VARCHAR(24)`, `error_code VARCHAR(40) NULL`, `error_message TEXT NULL`, `person_id BIGINT NULL`, `teacher_id BIGINT NULL`, `eligibility_id BIGINT NULL` | `UNIQUE(import_id, row_number)`；学生写 `person_id`、导师写 `teacher_id`，每行仅一个人员引用；不保存密码或无关成绩/联系方式副本。 |
| `site_notice` | `sender_account_id BIGINT NULL`, `scope_college_id BIGINT NULL`, `batch_id BIGINT NULL`, `notice_type VARCHAR(32)`, `title VARCHAR(200)`, `body TEXT`, `source_operation_id BIGINT NULL`, `created_at DATETIME(3)`, `visible_at DATETIME(3) NULL` | 站内通知；结果类消息按业务披露时点安排。 |
| `notice_recipient` | `notice_id BIGINT`, `account_id BIGINT`, `recipient_status VARCHAR(16)`, `delivered_at DATETIME(3) NULL`, `read_at DATETIME(3) NULL` | `UNIQUE(notice_id, account_id)`；服务端计算并校验接收范围。 |
| `delivery_attempt` | `recipient_id BIGINT`, `attempt_no INTEGER`, `attempted_at DATETIME(3)`, `attempt_status VARCHAR(16)`, `failure_code VARCHAR(40) NULL`, `failure_detail TEXT NULL`, `retry_after DATETIME(3) NULL` | `UNIQUE(recipient_id, attempt_no)`；重试不创建重复业务通知或重复匹配。 |
| `data_access_record` | `account_id BIGINT`, `action_code VARCHAR(32)`, `college_id BIGINT NULL`, `batch_id BIGINT NULL`, `object_type VARCHAR(24)`, `object_id BIGINT NULL`, `field_set TEXT`, `authorization_basis TEXT`, `accessed_at DATETIME(3)`, `result_file_id BIGINT NULL`, `accessed_file_id BIGINT NULL` | 简历查看、联系方式查看、名单/结果/简历导出分别留痕；导出文件受控。 |
| `admin_export_job` | `batch_id BIGINT`, `requester_account_id BIGINT`, `export_type VARCHAR(24)`, `export_status VARCHAR(16)`, `request_id VARCHAR(128)`, `request_fingerprint VARCHAR(128)`, `storage_key VARCHAR(512) NULL`, `original_filename VARCHAR(255) NULL`, `file_size_bytes BIGINT NULL`, `row_count INTEGER NULL`, `error_code VARCHAR(40) NULL`, `created_at DATETIME(3)`, `completed_at DATETIME(3) NULL`, `expires_at DATETIME(3)` | `UNIQUE(requester_account_id, request_id)`；固定统计/匹配结果 CSV 模板；文件在私有存储，24 小时过期；创建和下载访问另写 `data_access_record`。 |
| `audit_event` | `actor_account_id BIGINT NULL`, `actor_kind VARCHAR(16)`, `actor_role VARCHAR(16) NULL`, `scope_basis TEXT NULL`, `object_type VARCHAR(32)`, `object_id BIGINT`, `action_code VARCHAR(48)`, `before_values_text TEXT NULL`, `after_values_text TEXT NULL`, `reason TEXT NULL`, `approval_comment TEXT NULL`, `occurred_at DATETIME(3)`, `business_operation_id BIGINT NULL` | 追加式；通用对象引用无 FK；不记录密码、临时凭证明文、简历内容或无关敏感字段。 |

## 4. 关键唯一约束与索引

索引名为建议名，定稿 DDL 须按 MySQL 5.7.36 验证。唯一约束是数据正确性的组成部分，不能只以“先查再插入”替代。

| 建议约束/索引 | 用途 |
|---|---|
| `account(login_identifier)` UNIQUE；`student(student_no)` UNIQUE；`teacher(employee_no)` UNIQUE | 登录标识和人员业务标识唯一。 |
| `batch_running_slot(college_id, academic_year_id)` PK | 线性化同学院同学年运行批次的发布占位；终态释放，暂停保留。 |
| `batch_student(batch_id, student_id)` UNIQUE；`batch_teacher_quota(batch_id, teacher_id)` UNIQUE；`batch_stage(batch_id, stage_code)` UNIQUE | 批次参与人、名额账户和阶段单一。 |
| `preference_submission(batch_student_id, version_no)` UNIQUE；`preference_item(submission_id, preference_order)` UNIQUE；`preference_item(submission_id, batch_teacher_quota_id)` UNIQUE | 保留多个完整版本，同时禁止版本内重复顺位/导师。 |
| `round_application(preference_item_id)` UNIQUE；`application_profile_snapshot(round_application_id, execution_cycle)` UNIQUE（非空常规申请侧） | 一个锁定志愿项对应唯一当前常规申请，每处理周期快照可追溯。 |
| `student_year_match_slot(student_id, academic_year_id)` PK | 跨批次同学年有效关系唯一；槽位与 `matching_relation`、名额余额同事务维护。 |
| `admin_export_job(requester_account_id, request_id)` UNIQUE；`(batch_id, export_status, created_at)`；`(expires_at, storage_key)` | 导出请求幂等、按批次/状态检索和过期文件清理；批次及请求管理员外键保护。 |
| `student_pending_supplement_slot(student_id)` PK | 并发下同一学生至多一条待处理补选申请。 |
| `teacher_application_scope_version(batch_id, teacher_id, version_no)` UNIQUE；`teacher_allowed_major(scope_version_id, major_id)` UNIQUE | 范围版本和专业项稳定且不重复。 |
| `teacher_application_scope_slot(batch_id, teacher_id)` PK | 每批次/导师仅一个冻结范围版本；常规轮次、重开与补选引用同一冻结版本。 |
| `business_operation(actor_account_id, action_code, request_id)` UNIQUE（request_id 非空） | 请求幂等；重复请求内容指纹必须相同。 |
| `notice_recipient(notice_id, account_id)` UNIQUE；`delivery_attempt(recipient_id, attempt_no)` UNIQUE | 通知收件和投递重试去重。 |
| 查询索引：`batch_student(batch_id, match_status, match_reason)`；`round_application(stage_id, teacher_id, application_status)`；`supplement_application(supplement_window_id, teacher_id, application_status)` | 管理统计和导师当前申请队列；查询仍须执行字段与对象授权。 |
| 查询索引：`matching_relation(academic_year_id, relation_status, batch_student_id)`；`quota_ledger(batch_teacher_quota_id, occurred_at)`；`audit_event(object_type, object_id, occurred_at)` | 当前关系校验、名额核对和审计检索。 |

条件唯一索引如“仅 LOCKED 行唯一”不适用于 MySQL 5.7。本设计用 `student_year_match_slot`、`student_pending_supplement_slot` 和 `batch_running_slot` 将条件唯一性转成普通唯一键；`teacher_application_scope_slot` 固定唯一冻结范围。槽位/指针操作、状态行、名额余额、流水与业务操作在同一事务中提交或回滚。

## 5. 外键与一致性策略

- 分层原则：数据库约束负责最终的数据完整性，Service 负责业务语义、权限和对象范围校验。人员、批次、志愿、申请、名额、关系、附件版本等目标明确的强业务引用，默认建立物理外键；Service 可先行检查并返回可理解的业务错误，但不能把该检查当作外键的替代。外键删除策略采用 `RESTRICT`，不得级联删除业务历史。
- 关联表的组合一致性（例如志愿项导师名额必须属于同批次）优先用复合候选键和复合外键表达。MySQL 约束无法表达或当前定案 DDL 未设置复合外键的场景，须由 Service 在同一事务内校验；涉及可并发变更的目标行时，Mapper 应锁定目标/关联行后再核对归属。不能仅凭前端筛选或事务外的“先查再写”保证。
- 审计、状态事件中的 `object_type + object_id`、申请来源 `source_type + source_id` 属多态引用，不设跨表外键；写入 Service 须校验引用类型、目标存在性及对象归属，保留业务操作外键以便追溯。
- `student` 当前专业/学位类型是最新分类投影；每次变更必须同时追加 `student_classification_revision` 并更新版本号。志愿项、范围校验操作和申请资料快照保留当时依据。
- `batch_student.match_status`、`current_relation_id` 和 `batch_teacher_quota.occupied_count` 是当前投影。关系与事件/流水是审计依据；任何更新须与相应历史行在同一事务中一致。
- 志愿版本的项数范围、顺位连续性，角色状态组合，批次计划/阶段组合等跨行规则由领域服务事务校验；MySQL 5.7.36 的 CHECK 不执行，正确性由服务逻辑、键约束和事务并发控制共同保证。

## 6. 事务和并发控制

事务边界由协调业务用例的 Service 方法定义，Spring 实现阶段通过事务管理器（通常为 `@Transactional`）让一组 Mapper 写入加入同一事务；Mapper 负责执行 SQL，不自行拆分或吞掉事务。参与事务的业务表使用 InnoDB，相关写入必须使用同一数据源和事务管理器。Service 做状态、权限、资格、范围和名额规则校验并决定整体提交/回滚；InnoDB 通过行锁、条件更新、主键/唯一键和外键落实跨请求并发保护。需要串行化读取后再写入时使用 `SELECT ... FOR UPDATE`，适合原子计数的操作优先使用带条件的 `UPDATE`；唯一键冲突应作为并发结果处理并映射为业务冲突。不得用 Java `synchronized`、事务外先查后写或仅靠 Redis 锁代替数据库约束与锁。多行锁按稳定顺序获取，发生死锁时仅对具备幂等保护的操作进行有限重试。

| 操作 | 原子写集合与并发保护 |
|---|---|
| 发布/撤回发布批次 | 发布时校验必需阶段/补选计划、规则快照及时间，插入 `batch_running_slot`；按已确认规则撤回发布时释放槽位并回到 DRAFT。更新批次状态并写生命周期/审计/业务操作。槽位主键冲突表示已有运行批次。 |
| 冻结学生名单 | 填报窗口开始时读取符合资格名单并排除学年已有有效关系者，写 `batch_student`；固定名单与分母，后续更正须单独授权留痕。 |
| 提交志愿 | 锁定阶段行后读取数据库 UTC 时间，按 `[startAt,endAt)` 校验填报窗口；再检查账号、身份确认版本、导师范围和资格。插入完整 `preference_submission` 与 1–3 条 `preference_item`；更新批次学生当前版本指针并写事件/操作/审计。任何校验失败整笔回滚；截止前通过校验的事务可以在截止后提交（TODO-50）。 |
| 导师录取（常规或补选） | 每项办理在独立事务内锁定阶段行并按数据库 UTC 时间校验窗口，再校验申请、学生状态/范围/资格和名额；需要读取并协调多行状态时由 Mapper 用 `SELECT ... FOR UPDATE` 锁定相应行。以唯一键占用 `student_year_match_slot`，并通过带条件更新确保名额仍有余额；建立 LOCKED 关系，更新批次学生、名额并写 `quota_ledger`、状态/申请事件、操作、审计及站内通知收件记录。任何唯一键冲突、条件更新影响行数不符或业务校验失败都回滚该项事务。 |
| 名额控制 | 对 `batch_teacher_quota` 做带 `row_version` 的条件更新，要求 `occupied_count < quota_limit`；名额调整须 `new_limit >= occupied_count`。受影响行数为 0 表示额度或版本已变化，Service 重新读取并按业务规则返回冲突；使用 InnoDB 行锁/条件更新，按稳定顺序获取多行锁并对幂等操作有限重试。独立批量操作无全局 FIFO；竞争最后名额时由先获得名额行锁并成功提交的操作取得（TODO-51）。 |
| 撤销/恢复/改派关系 | 与关系状态、学年槽位、旧/新导师名额、两侧名额流水、匹配状态、纠错记录、审计和通知同事务；改派整体成功或整体失败。 |
| 补选申请 | 校验窗口、UNMATCHED、资格/范围/许可/名额及同年关系；先成功占用 `student_pending_supplement_slot` 再提交申请和资料快照；不预扣名额。结案时释放槽位。 |
| 暂停/恢复/重开/关闭阶段 | 更新批次及阶段、时间修订、生命周期与相关申请事件；批量结案可分片执行，但须具备幂等键/游标，阶段完全结案前不可宣告关闭。补选关闭时最终将批次置为 COMPLETED。 |
| 身份纠错 | 追加分类版本并更新学生当前分类；重新核验受影响批次。填报开始后按已确认规则处理已有关系、释放名额、设为 UNMATCHED 并跳过剩余常规轮次；与录取操作使用同一学生/关系并发保护。 |

业务操作具备请求幂等标识；批量录取按最终锁定志愿提交时间升序、再按学号升序，并逐项持久化顺序与结果。同一操作内逐项串行、每项独立事务；不同操作间不承诺全局排序。UI 勾选顺序不作为数据库业务优先级。事务注解、Mapper 锁定 SQL 和唯一键冲突映射属于实现细节，须在 API/实现设计中细化，但不得削弱本节的数据完整性和并发保证。

## 7. 状态编码、留存和安全

状态编码以 [状态机设计](state-machine.md) 为准。数据库只保存对象当前状态及必须的事件历史；派生标签（如 NO_PREFERENCE、界面“待处理/已处理”）不建成独立真值。

常规申请结案原因须区分导师明确不录取、截止自动结案、批量名额不足、批次取消、按时间跳过、已匹配跳过、身份纠错跳过、轮次重开取代。学生 `UNMATCHED` 原因至少区分 `NOT_SUBMITTED`、`ROUND3_EXHAUSTED`、`PREFERENCE_EXHAUSTED`、`BATCH_CANCELLED`、`ALL_REMAINING_PREFERENCES_SKIPPED`、`RELATION_REVOKED`、`IDENTITY_CORRECTION`；少填耗尽记录实际轮次/顺位。

志愿、关系、名额、申请事件、身份分类版本、关系例外、批次生命周期、操作/审计和通知历史采用追加写入。业务归档后业务结果只读。账号停用不删人员及历史。临时凭证只保存哈希；审计和导入行不得写入明文凭证、密码或不必要的敏感字段。

简历文件通过受控文件服务访问，数据库只存内部 `storage_key`、摘要、元数据和保留依据。导师可见资料仅为姓名、学号、专业、学位类型、简介和当时简历版本；联系方式和成绩不得进入申请快照。简历毕业后五年保留；毕业日期未确认时不得自动清理。

## 8. 实施与部署验证项

以下事项属于实施配置或目标环境核验，不阻止本数据库设计定案；任何导致字段、键、索引或事务语义变化的结果须走正式设计变更：

1. 部署时核对服务端版本是否为 5.7.36；若不同，检查约束、时间精度、隔离/锁行为和驱动兼容性，并准备迁移 DDL 与回滚方案。本设计不依赖 MySQL 5.7 不支持的 `CHECK` 或部分唯一索引。
2. 在导入学校数据前，验证学号、工号、专业代码、姓名和登录标识均适配本稿已定案的字段长度及 `utf8mb4_unicode_ci` 排序规则；若不适配，先走设计变更，不得静默截断或转换。
3. 按部署条件落实私有文件存储、备份/恢复、下载鉴权和清理任务；不得把简历二进制放入可公开访问位置。摘要使用 API 契约规定的 SHA-256。
4. 按 API 契约核对数据库连接时区和 UTC 时间读取方式，并配置计划调整及任务调度恢复机制。
5. 当前字段必填性、外键、索引、状态码、保留和事务并发方案均以本稿定案内容为准；其他环境的部署方案需满足该基线，变更须记录新版本及迁移/回滚影响。
