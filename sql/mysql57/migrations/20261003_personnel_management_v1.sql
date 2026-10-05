-- Forward-only additive migration for personnel, catalogue and annual eligibility management.
-- Target: MySQL 5.7.36. Run once against hnust_selection after taking the local development DB backup.
-- It preserves personnel and qualification history; it does not delete or rewrite existing profiles.
-- Set the session encoding explicitly: the Windows mysql client may otherwise use GBK,
-- which corrupts UTF-8 literals and causes collation errors in later comparisons.
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `hnust_selection`;

-- Preflight: this query must return no rows. Multiple current records need a business decision before migration.
SELECT academic_year_id, student_id, teacher_id, COUNT(*) AS current_record_count
FROM annual_eligibility
WHERE valid_to IS NULL
GROUP BY academic_year_id, student_id, teacher_id
HAVING COUNT(*) > 1;

-- Preflight: report current records that do not reference exactly one matching person identity.
-- annual_eligibility intentionally has no person_type column; its type is derived from the populated FK.
SELECT id, student_id, teacher_id
FROM annual_eligibility
WHERE valid_to IS NULL
  AND ((student_id IS NULL AND teacher_id IS NULL)
    OR (student_id IS NOT NULL AND teacher_id IS NOT NULL));

-- Existing import rows are student-only in the original schema. Add nullable links for teachers and operations;
-- new imports always fill business_operation_id and exactly one person link.
ALTER TABLE `personnel_import`
  ADD COLUMN `business_operation_id` BIGINT NULL AFTER `source_file_id`,
  ADD UNIQUE KEY `uq_personnel_import_business_operation_id` (`business_operation_id`);

ALTER TABLE `personnel_import_row`
  ADD COLUMN `teacher_id` BIGINT NULL AFTER `person_id`,
  ADD KEY `ix_personnel_import_row_teacher_id` (`teacher_id`);

-- This unique slot is the database-level guard for one current qualification per person and year.
-- MySQL 5.7 has no enforced CHECK constraint, so Service validates that one and only one person FK is non-null.
CREATE TABLE `annual_eligibility_slot` (
  `academic_year_id` BIGINT NOT NULL,
  `college_id` BIGINT NOT NULL,
  `student_id` BIGINT NULL,
  `teacher_id` BIGINT NULL,
  `eligibility_id` BIGINT NOT NULL,
  `claimed_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`academic_year_id`, `eligibility_id`),
  UNIQUE KEY `uq_annual_eligibility_slot_year_student` (`academic_year_id`, `student_id`),
  UNIQUE KEY `uq_annual_eligibility_slot_year_teacher` (`academic_year_id`, `teacher_id`),
  UNIQUE KEY `uq_annual_eligibility_slot_eligibility_id` (`eligibility_id`),
  KEY `ix_annual_eligibility_slot_college_id` (`college_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

-- Backfill an operation link for existing import batches before adding its FK.
INSERT INTO `business_operation` (`actor_account_id`, `actor_kind`, `action_code`, `college_id`, `batch_id`,
  `request_id`, `request_fingerprint`, `result_code`, `started_at`, `completed_at`)
SELECT NULL, 'SYSTEM', 'PERSONNEL_IMPORT_MIGRATION', pi.college_id, NULL,
  CONCAT('MIGRATION-PERSONNEL-IMPORT-', pi.id), SHA2(CONCAT('PERSONNEL_IMPORT:', pi.id), 256), 'OK',
  pi.submitted_at, COALESCE(pi.completed_at, pi.submitted_at)
FROM `personnel_import` pi
WHERE pi.business_operation_id IS NULL;

UPDATE `personnel_import` pi
JOIN `business_operation` operation_row
  ON operation_row.actor_account_id IS NULL
 AND operation_row.action_code = 'PERSONNEL_IMPORT_MIGRATION'
 AND operation_row.request_id = CONCAT('MIGRATION-PERSONNEL-IMPORT-', pi.id)
SET pi.business_operation_id = operation_row.id
WHERE pi.business_operation_id IS NULL;

INSERT INTO `annual_eligibility_slot` (`academic_year_id`, `college_id`, `student_id`, `teacher_id`, `eligibility_id`, `claimed_at`)
SELECT academic_year_id, college_id, student_id, teacher_id, id, COALESCE(valid_from, created_at)
FROM annual_eligibility
WHERE valid_to IS NULL AND ((student_id IS NOT NULL) XOR (teacher_id IS NOT NULL));

ALTER TABLE `annual_eligibility_slot`
  ADD CONSTRAINT `fk_annual_eligibility_slot_academic_year_id`
    FOREIGN KEY (`academic_year_id`) REFERENCES `academic_year` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT `fk_annual_eligibility_slot_college_id`
    FOREIGN KEY (`college_id`) REFERENCES `college` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT `fk_annual_eligibility_slot_student_id`
    FOREIGN KEY (`student_id`) REFERENCES `student` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT `fk_annual_eligibility_slot_teacher_id`
    FOREIGN KEY (`teacher_id`) REFERENCES `teacher` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT `fk_annual_eligibility_slot_eligibility_id`
    FOREIGN KEY (`eligibility_id`) REFERENCES `annual_eligibility` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE `personnel_import`
  ADD CONSTRAINT `fk_personnel_import_business_operation_id`
    FOREIGN KEY (`business_operation_id`) REFERENCES `business_operation` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE `personnel_import_row`
  ADD CONSTRAINT `fk_personnel_import_row_teacher_id`
    FOREIGN KEY (`teacher_id`) REFERENCES `teacher` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;

-- Existing bootstrap total-admin account gains the same college-wide business capability as other ADMINs.
-- Its reserved ADMIN_ACCOUNT_MANAGER slot remains unchanged and retains the same account scope.
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

INSERT INTO `account_authorization` (`account_id`, `college_id`, `batch_id`, `capability_code`, `authority_slot`,
  `basis`, `granted_by`, `granted_at`, `revoked_by`, `revoked_at`)
SELECT manager.account_id, manager.college_id, NULL, 'COLLEGE_ADMIN', NULL,
  '数据模型升级：总管理员获得其所属学院的普通业务权限', manager.account_id, UTC_TIMESTAMP(3), NULL, NULL
FROM `account_authorization` manager
WHERE manager.capability_code = 'ADMIN_ACCOUNT_MANAGER' AND manager.revoked_at IS NULL
  AND NOT EXISTS (
    SELECT 1 FROM `account_authorization` ordinary
    WHERE ordinary.account_id = manager.account_id AND ordinary.college_id = manager.college_id
      AND ordinary.batch_id IS NULL AND ordinary.capability_code = 'COLLEGE_ADMIN'
      AND ordinary.revoked_at IS NULL
  );

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

-- Validation after the migration:
-- SELECT COUNT(*) FROM annual_eligibility_slot;
-- SELECT COUNT(*) FROM personnel_import WHERE business_operation_id IS NULL;
-- SELECT COUNT(*) FROM personnel_import_row WHERE teacher_id IS NOT NULL;
-- SELECT account_id, college_id, capability_code FROM account_authorization WHERE capability_code = 'COLLEGE_ADMIN';
