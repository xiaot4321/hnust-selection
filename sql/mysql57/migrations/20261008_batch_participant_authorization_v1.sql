-- Per-batch regular-round participation allowlists for students and teachers.
-- Target: MySQL 5.7.36. Review target schema and take a backup before applying.
CREATE TABLE `batch_student_participation` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `batch_id` BIGINT NOT NULL,
  `student_id` BIGINT NOT NULL,
  `granted_by` BIGINT NOT NULL,
  `reason` TEXT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_batch_student_participation_batch_student` (`batch_id`, `student_id`),
  KEY `ix_batch_student_participation_student_id` (`student_id`),
  KEY `ix_batch_student_participation_granted_by` (`granted_by`),
  CONSTRAINT `fk_batch_student_participation_batch_id` FOREIGN KEY (`batch_id`) REFERENCES `selection_batch` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_batch_student_participation_student_id` FOREIGN KEY (`student_id`) REFERENCES `student` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_batch_student_participation_granted_by` FOREIGN KEY (`granted_by`) REFERENCES `account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `batch_teacher_participation` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `batch_id` BIGINT NOT NULL,
  `teacher_id` BIGINT NOT NULL,
  `granted_by` BIGINT NOT NULL,
  `reason` TEXT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_batch_teacher_participation_batch_teacher` (`batch_id`, `teacher_id`),
  KEY `ix_batch_teacher_participation_teacher_id` (`teacher_id`),
  KEY `ix_batch_teacher_participation_granted_by` (`granted_by`),
  CONSTRAINT `fk_batch_teacher_participation_batch_id` FOREIGN KEY (`batch_id`) REFERENCES `selection_batch` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_batch_teacher_participation_teacher_id` FOREIGN KEY (`teacher_id`) REFERENCES `teacher` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_batch_teacher_participation_granted_by` FOREIGN KEY (`granted_by`) REFERENCES `account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

-- Preserve the previous automatic-participation behavior for batches already published or run.
-- Existing DRAFT batches deliberately remain unconfigured and require an administrator to select lists.
INSERT INTO `batch_student_participation` (`batch_id`, `student_id`, `granted_by`, `reason`)
SELECT participant.`batch_id`, participant.`student_id`, batch.`created_by`,
  'Migration compatibility: student was in the pre-authorization frozen roster'
FROM `batch_student` participant JOIN `selection_batch` batch ON batch.`id` = participant.`batch_id`
WHERE batch.`batch_status` <> 'DRAFT';

-- A published batch whose filling window has not opened had no frozen batch_student rows yet;
-- seed its prior automatic eligibility pool so deployment does not silently reduce its roster.
INSERT INTO `batch_student_participation` (`batch_id`, `student_id`, `granted_by`, `reason`)
SELECT batch.`id`, student.`id`, batch.`created_by`,
  'Migration compatibility: legacy published batch used automatic annual eligibility'
FROM `selection_batch` batch
JOIN `student` student ON student.`college_id` = batch.`college_id`
JOIN `account` account ON account.`id` = student.`account_id` AND account.`account_status` = 'ACTIVE'
JOIN `annual_eligibility_slot` eligibility_slot ON eligibility_slot.`academic_year_id` = batch.`academic_year_id`
  AND eligibility_slot.`college_id` = student.`college_id` AND eligibility_slot.`student_id` = student.`id`
JOIN `annual_eligibility` eligibility ON eligibility.`id` = eligibility_slot.`eligibility_id`
  AND eligibility.`eligibility_status` = 'ELIGIBLE'
LEFT JOIN `student_year_match_slot` relation_slot ON relation_slot.`academic_year_id` = batch.`academic_year_id`
  AND relation_slot.`student_id` = student.`id`
WHERE batch.`batch_status` IN ('SCHEDULED', 'ACTIVE', 'PAUSED')
  AND batch.`frozen_roster_at` IS NULL AND relation_slot.`student_id` IS NULL
  AND NOT EXISTS (SELECT 1 FROM `batch_student` current_roster
    WHERE current_roster.`batch_id` = batch.`id` AND current_roster.`student_id` = student.`id`);

-- Existing published/running batches already used configured positive quotas as normal teacher participation.
INSERT INTO `batch_teacher_participation` (`batch_id`, `teacher_id`, `granted_by`, `reason`)
SELECT quota.`batch_id`, quota.`teacher_id`, batch.`created_by`,
  'Migration compatibility: legacy published batch used configured positive quota for participation'
FROM `batch_teacher_quota` quota JOIN `selection_batch` batch ON batch.`id` = quota.`batch_id`
WHERE batch.`batch_status` <> 'DRAFT' AND quota.`quota_limit` > 0;
