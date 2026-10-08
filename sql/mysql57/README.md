# MySQL 5.7.36 建库脚本

`schema.sql` 是按 `docs/database-design.md` 1.1 数据字典生成的完整首次建库脚本，包含 54 张表、主键、唯一键、查询索引及 167 个外键。为与本机已应用迁移后的结构保持一致，脚本仍包含已停用的批次参与授权历史表；当前应用不读写这些表。脚本不写入业务人员数据，也不执行 `DROP`。

本机库已应用初始 0.5 结构（49 张表、151 个外键）。2026-10-03 首次执行 `migrations/20261003_personnel_management_v1.sql` 时，结构变更成功，但 MySQL 客户端会话使用 GBK，导致预检查询报错及总管理员授权/审计数据写入失败。随后执行 `migrations/20261003_personnel_management_v1_recovery.sql`，两个结构计数均为 1，管理员授权和审计记录各写入一条。该本机迁移现已完成；不可重跑完整迁移。目标为其他或生产环境时，须先核对版本、备份、迁移与回滚方案。

TODO-31/58 已改为所有角色的一次性临时凭证均不设到期时间。2026-10-05 已先备份本机库，再执行 `migrations/20261005_credential_expiry_removal_v1.sql`：凭证总数为 3，其中 1 条未消费凭证清除了原到期时间；`expires_at` 已改为可空，未消费凭证中仍有到期时间的数量为 0。表数和外键数仍为 50/158。执行前备份保存在本机 `%TEMP%\hnust_selection_before_credential_expiry_removal_20261005.sql`。若明确回滚，运行 `migrations/20261005_credential_expiry_removal_v1_rollback.sql`，按 `issued_at + 72 小时` 恢复旧策略；回滚会使已超过原期限的未用凭证失效。其他或生产环境执行前仍须完成版本核对、备份和回滚检查。

管理员异步 CSV 导出增加 `admin_export_job` 表。完整新库 DDL 已包含该表；2026-10-05 按业务方指令先备份、确认目标表不存在后，已将 `migrations/20261005_admin_export_jobs_v1.sql` 应用到本机验证库。迁移后核验为 51 张表、160 个外键，表使用 InnoDB/`utf8mb4_unicode_ci`，当前任务记录为 0。迁移前备份保存在 `%TEMP%\hnust_selection_before_admin_export_jobs_20261005_175530.sql`。回滚脚本 `migrations/20261005_admin_export_jobs_v1_rollback.sql` 会删除任务表及其任务记录；回滚前应确认无须保留的导出任务历史和私有文件。其他或生产环境执行前仍须核对版本、备份、迁移与回滚方案。

2026-10-08 为学生导师目录增加可丢弃的 `teacher_official_profile_cache` 表，完整建库 DDL 共 52 张表、161 个外键。本机验证库已先备份至 `%TEMP%\hnust_selection_before_teacher_official_profile_cache_20261008.sql`，再执行 `migrations/20261008_teacher_official_profile_cache_v1.sql`；核验为 52 张表、161 个外键，且缓存表及其导师外键均存在。回滚脚本 `migrations/20261008_teacher_official_profile_cache_v1_rollback.sql` 会删除缓存表及其全部缓存资料。其他或生产环境执行前仍须核对目标版本、备份与回滚方案。

2026-10-08 曾新增草稿批次学生与常规导师参与授权表 `batch_student_participation`、`batch_teacher_participation`，并应用到本机验证库；完整结构为 54 张表、167 个外键。随后 TODO-68 撤销了应用层批次参与授权规则，表和迁移文件因已应用且可能含历史数据而保留，但当前代码不读写这些表。此前备份位于 `%TEMP%\hnust_selection_before_batch_participant_authorization_20261008.sql`。回滚脚本会删除两张授权表及其中全部记录；不要为实现业务规则变更而运行该回滚。只有另有明确的数据清理/迁移方案时，才可单独评估其适用性和数据风险。

若当前已经位于 `mysql>` 提示符，可直接执行恢复脚本：

```sql
source C:/Users/14426/Desktop/hnust-selection/sql/mysql57/migrations/20261003_personnel_management_v1_recovery.sql;
```

脚本开头的两个结构计数应为 `1`；末尾的管理员目录查询应显示非空 `college_admin_authorization_id`，审计计数应为 `1`。如果结构计数不是 `1`，先停止执行并检查数据库状态。

脚本暂按数据库名 `hnust_selection` 和 `utf8mb4_unicode_ci` 生成。该排序规则会影响字符串唯一键的大小写及重音比较；正式环境执行前须确认它符合学校对登录标识、学号、工号和代码字段的要求。脚本不使用 MySQL 5.7 不执行的 `CHECK` 约束；跨行和状态规则仍需由事务内业务校验保障。

脚本面向全新空库，只能在确认目标库后执行一次。MySQL 5.7 的 DDL 会逐条提交；若执行中途失败，不要直接重跑，应先检查已创建对象及失败原因。脚本不会删除或覆盖已有表，但同名表会导致执行失败。

在 Windows `cmd.exe` 中，可使用已配置的 MySQL 登录路径执行；不要把密码写进命令或提交到仓库：

```bat
mysql --login-path=hnust-selection < sql\mysql57\schema.sql
```

账户需要创建数据库及表、索引、外键的权限。执行后可检查服务端版本和表数量：

```sql
SELECT VERSION();
SELECT COUNT(*)
FROM information_schema.tables
WHERE table_schema = 'hnust_selection'
  AND table_type = 'BASE TABLE';
```

2026-10-01，业务方在本机 MySQL 5.7.36-log 服务上应用了初始建库脚本，并核验 49 张基础表、151 个外键约束。2026-10-03 人员管理实施将完整 DDL 更新到 0.6，并新增迁移；本机首次迁移已部分成功，恢复脚本用于补齐失败的管理员授权和审计写入。DDL 会逐条提交，若其他环境执行中途失败，也必须检查状态并制定专用恢复方案，不能直接重跑完整迁移。
