-- Recovery for the local development database after the first v1 migration attempt.
-- The reported attempt successfully applied the schema changes and inserted the
-- COLLEGE_ADMIN_BOOTSTRAP_MIGRATION business_operation, but its authorization and
-- audit INSERTs failed because the mysql client session used GBK instead of UTF-8.
-- Do NOT source the full v1 migration again: MySQL 5.7 DDL is committed statement by statement.
-- This script only repairs the failed bootstrap authorization/audit data and is safe to rerun.
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `hnust_selection`;

-- Confirm that the earlier DDL is present. Both counts should be one before proceeding.
SELECT COUNT(*) AS import_operation_column_count
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'personnel_import'
  AND column_name = 'business_operation_id';

SELECT COUNT(*) AS eligibility_slot_table_count
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name = 'annual_eligibility_slot'
  AND table_type = 'BASE TABLE';

-- Preserve one bootstrap operation per account. The initial source run already created one;
-- this NOT EXISTS guard also makes recovery safe if that row was absent or this file is rerun.
INSERT INTO `business_operation` (`actor_account_id`, `actor_kind`, `action_code`, `college_id`, `batch_id`,
  `request_id`, `request_fingerprint`, `result_code`, `started_at`, `completed_at`)
SELECT NULL, 'SYSTEM', 'COLLEGE_ADMIN_BOOTSTRAP_MIGRATION', manager.college_id, NULL,
  CONCAT('MIGRATION-COLLEGE-ADMIN-', manager.account_id), SHA2(CONCAT('COLLEGE_ADMIN:', manager.account_id), 256),
  'OK', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
FROM `account_authorization` manager
WHERE manager.capability_code = 'ADMIN_ACCOUNT_MANAGER' AND manager.revoked_at IS NULL
  AND NOT EXISTS (
    SELECT 1 FROM `account_authorization` ordinary
    WHERE ordinary.account_id = manager.account_id AND ordinary.college_id = manager.college_id
      AND ordinary.batch_id IS NULL AND ordinary.capability_code = 'COLLEGE_ADMIN'
      AND ordinary.revoked_at IS NULL
  )
  AND NOT EXISTS (
    SELECT 1 FROM `business_operation` existing_operation
    WHERE existing_operation.actor_account_id IS NULL
      AND existing_operation.action_code = 'COLLEGE_ADMIN_BOOTSTRAP_MIGRATION'
      AND existing_operation.request_id = CONCAT('MIGRATION-COLLEGE-ADMIN-', manager.account_id)
  );

-- Grant the total administrator ordinary college-wide business capability, if missing.
INSERT INTO `account_authorization` (`account_id`, `college_id`, `batch_id`, `capability_code`, `authority_slot`,
  `basis`, `granted_by`, `granted_at`, `revoked_by`, `revoked_at`)
SELECT manager.account_id, manager.college_id, NULL, 'COLLEGE_ADMIN', NULL,
  '数据模型升级：总管理员获得其所属学院的普通业务权限', manager.account_id, UTC_TIMESTAMP(3), NULL, NULL
FROM `account_authorization` manager
JOIN `business_operation` operation_row
  ON operation_row.actor_account_id IS NULL
 AND operation_row.action_code = 'COLLEGE_ADMIN_BOOTSTRAP_MIGRATION'
 AND operation_row.request_id = CONCAT('MIGRATION-COLLEGE-ADMIN-', manager.account_id)
WHERE manager.capability_code = 'ADMIN_ACCOUNT_MANAGER' AND manager.revoked_at IS NULL
  AND NOT EXISTS (
    SELECT 1 FROM `account_authorization` ordinary
    WHERE ordinary.account_id = manager.account_id AND ordinary.college_id = manager.college_id
      AND ordinary.batch_id IS NULL AND ordinary.capability_code = 'COLLEGE_ADMIN'
      AND ordinary.revoked_at IS NULL
  );

-- Record each migrated authorization once, including safe reruns of this recovery file.
INSERT INTO `audit_event` (`actor_account_id`, `actor_kind`, `actor_role`, `scope_basis`, `object_type`, `object_id`,
  `action_code`, `before_values_text`, `after_values_text`, `reason`, `approval_comment`, `occurred_at`, `business_operation_id`)
SELECT NULL, 'SYSTEM', NULL, CONCAT('collegeId=', authorization_row.college_id), 'ACCOUNT_AUTHORIZATION',
  authorization_row.id, 'COLLEGE_ADMIN_CAPABILITY_INITIALIZED', NULL,
  CONCAT('{"accountId":', authorization_row.account_id, ',"capabilityCode":"COLLEGE_ADMIN"}'),
  '系统升级为总管理员初始化其所属学院的普通业务能力', NULL, UTC_TIMESTAMP(3), operation_row.id
FROM `account_authorization` authorization_row
JOIN `business_operation` operation_row
  ON operation_row.actor_account_id IS NULL
 AND operation_row.action_code = 'COLLEGE_ADMIN_BOOTSTRAP_MIGRATION'
 AND operation_row.request_id = CONCAT('MIGRATION-COLLEGE-ADMIN-', authorization_row.account_id)
WHERE authorization_row.capability_code = 'COLLEGE_ADMIN'
  AND authorization_row.basis = '数据模型升级：总管理员获得其所属学院的普通业务权限'
  AND authorization_row.revoked_at IS NULL
  AND NOT EXISTS (
    SELECT 1 FROM `audit_event` existing_event
    WHERE existing_event.object_type = 'ACCOUNT_AUTHORIZATION'
      AND existing_event.object_id = authorization_row.id
      AND existing_event.action_code = 'COLLEGE_ADMIN_CAPABILITY_INITIALIZED'
      AND existing_event.business_operation_id = operation_row.id
  );

-- Final checks: there should be no missing capability or migration audit for an eligible total admin.
SELECT manager.account_id, manager.college_id, ordinary.id AS college_admin_authorization_id
FROM `account_authorization` manager
LEFT JOIN `account_authorization` ordinary
  ON ordinary.account_id = manager.account_id
 AND ordinary.college_id = manager.college_id
 AND ordinary.batch_id IS NULL
 AND ordinary.capability_code = 'COLLEGE_ADMIN'
 AND ordinary.revoked_at IS NULL
WHERE manager.capability_code = 'ADMIN_ACCOUNT_MANAGER'
  AND manager.revoked_at IS NULL;

SELECT authorization_row.id AS authorization_id, COUNT(existing_event.id) AS migration_audit_count
FROM `account_authorization` authorization_row
LEFT JOIN `business_operation` operation_row
  ON operation_row.actor_account_id IS NULL
 AND operation_row.action_code = 'COLLEGE_ADMIN_BOOTSTRAP_MIGRATION'
 AND operation_row.request_id = CONCAT('MIGRATION-COLLEGE-ADMIN-', authorization_row.account_id)
LEFT JOIN `audit_event` existing_event
  ON existing_event.object_type = 'ACCOUNT_AUTHORIZATION'
 AND existing_event.object_id = authorization_row.id
 AND existing_event.action_code = 'COLLEGE_ADMIN_CAPABILITY_INITIALIZED'
 AND existing_event.business_operation_id = operation_row.id
WHERE authorization_row.capability_code = 'COLLEGE_ADMIN'
  AND authorization_row.basis = '数据模型升级：总管理员获得其所属学院的普通业务权限'
  AND authorization_row.revoked_at IS NULL
GROUP BY authorization_row.id;
