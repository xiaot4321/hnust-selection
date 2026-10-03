# MySQL 5.7.36 建库脚本

`schema.sql` 是按 `docs/database-design.md` 0.4 数据字典生成的首次建库脚本，包含 49 张表、主键、唯一键、查询索引及外键。后续 0.5 版仅对齐 UTC 时间语义，未改变初始结构；数据库设计现以 0.5 定稿。脚本不写入业务数据，也不执行 `DROP`。

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

2026-10-01，业务方在本机 MySQL 5.7.36-log 服务上以 `hnust_ddl@localhost` 执行了脚本，并提供查询结果：`hnust_selection` 中有 49 张基础表、151 个外键约束。该核验仅适用于本机验证库；设计于 2026-10-02 定案，但生产或其他环境部署前仍需核对目标版本并准备迁移/回滚方案。脚本面向空库且不可直接重跑；若执行失败或部分完成，先检查已创建对象。
