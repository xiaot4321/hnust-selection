-- HNNUST faculty-student selection system: MySQL 5.7.36 bootstrap DDL
-- Generated from docs/database-design.md v1.0 (54 tables, 167 foreign keys).
-- Fresh schema only. This script does not DROP existing databases, tables, or data.
-- Assumptions for first execution: schema hnust_selection; utf8mb4_unicode_ci.
-- Review identifier case/collation and the FK list before applying to production.
SET NAMES utf8mb4;
CREATE DATABASE IF NOT EXISTS `hnust_selection` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `hnust_selection`;

-- Create tables first; foreign keys are added after all tables exist to resolve cycles.
CREATE TABLE `college` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `college_code` VARCHAR(32) NOT NULL,
  `name` VARCHAR(128) NOT NULL,
  `is_active` BOOLEAN NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `row_version` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_college_college_code` (`college_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `academic_year` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `year_code` VARCHAR(16) NOT NULL,
  `display_name` VARCHAR(64) NOT NULL,
  `starts_on` DATE NULL,
  `ends_on` DATE NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `row_version` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_academic_year_year_code` (`year_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `major` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `college_id` BIGINT NOT NULL,
  `major_code` VARCHAR(32) NOT NULL,
  `name` VARCHAR(128) NOT NULL,
  `is_active` BOOLEAN NOT NULL,
  `valid_from` DATE NULL,
  `valid_to` DATE NULL,
  `change_basis` TEXT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `row_version` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_major_college_id_major_code` (`college_id`, `major_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `account` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `login_identifier` VARCHAR(128) NOT NULL,
  `role_code` VARCHAR(16) NOT NULL,
  `account_status` VARCHAR(16) NOT NULL,
  `password_hash` VARCHAR(255) NULL,
  `must_change_password` BOOLEAN NOT NULL,
  `credential_changed_at` DATETIME(3) NULL,
  `last_login_at` DATETIME(3) NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `row_version` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_account_login_identifier` (`login_identifier`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `account_authorization` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `account_id` BIGINT NOT NULL,
  `college_id` BIGINT NOT NULL,
  `batch_id` BIGINT NULL,
  `capability_code` VARCHAR(48) NOT NULL,
  `authority_slot` VARCHAR(48) NULL,
  `basis` TEXT NOT NULL,
  `granted_by` BIGINT NOT NULL,
  `granted_at` DATETIME(3) NOT NULL,
  `revoked_by` BIGINT NULL,
  `revoked_at` DATETIME(3) NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `row_version` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_account_authorization_authority_slot` (`authority_slot`),
  KEY `ix_account_authorization_account_id` (`account_id`),
  KEY `ix_account_authorization_college_id` (`college_id`),
  KEY `ix_account_authorization_batch_id` (`batch_id`),
  KEY `ix_account_authorization_granted_by` (`granted_by`),
  KEY `ix_account_authorization_revoked_by` (`revoked_by`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `temporary_credential` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `account_id` BIGINT NOT NULL,
  `credential_hash` VARCHAR(255) NOT NULL,
  `issued_at` DATETIME(3) NOT NULL,
  `expires_at` DATETIME(3) NULL,
  `shown_at` DATETIME(3) NULL,
  `used_at` DATETIME(3) NULL,
  `revoked_at` DATETIME(3) NULL,
  `issued_operation_id` BIGINT NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `row_version` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `ix_temporary_credential_account_id` (`account_id`),
  KEY `ix_temporary_credential_issued_operation_id` (`issued_operation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `student` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `account_id` BIGINT NOT NULL,
  `student_no` VARCHAR(64) NOT NULL,
  `full_name` VARCHAR(128) NOT NULL,
  `college_id` BIGINT NOT NULL,
  `major_id` BIGINT NOT NULL,
  `degree_type` VARCHAR(32) NOT NULL,
  `classification_version` INTEGER NOT NULL,
  `enrollment_year_code` VARCHAR(16) NOT NULL,
  `graduation_date` DATE NULL,
  `graduation_date_basis` TEXT NULL,
  `graduation_date_confirmed_at` DATETIME(3) NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `row_version` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_student_student_no` (`student_no`),
  UNIQUE KEY `uq_student_account_id` (`account_id`),
  KEY `ix_student_college_id` (`college_id`),
  KEY `ix_student_major_id` (`major_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `teacher` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `account_id` BIGINT NOT NULL,
  `employee_no` VARCHAR(64) NOT NULL,
  `full_name` VARCHAR(128) NOT NULL,
  `college_id` BIGINT NOT NULL,
  `current_public_profile_version_id` BIGINT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `row_version` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_teacher_employee_no` (`employee_no`),
  UNIQUE KEY `uq_teacher_account_id` (`account_id`),
  KEY `ix_teacher_college_id` (`college_id`),
  KEY `ix_teacher_current_public_profile_version_id` (`current_public_profile_version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `student_profile_version` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `student_id` BIGINT NOT NULL,
  `version_no` INTEGER NOT NULL,
  `biography` TEXT NULL,
  `contact_text` VARCHAR(255) NULL,
  `resume_file_id` BIGINT NULL,
  `changed_by` BIGINT NOT NULL,
  `changed_at` DATETIME(3) NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_student_profile_version_student_id_version_no` (`student_id`, `version_no`),
  KEY `ix_student_profile_version_resume_file_id` (`resume_file_id`),
  KEY `ix_student_profile_version_changed_by` (`changed_by`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `teacher_public_profile_version` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `teacher_id` BIGINT NOT NULL,
  `version_no` INTEGER NOT NULL,
  `research_directions` TEXT NULL,
  `biography` TEXT NULL,
  `review_status` VARCHAR(24) NOT NULL,
  `submitted_at` DATETIME(3) NOT NULL,
  `reviewed_by` BIGINT NULL,
  `reviewed_at` DATETIME(3) NULL,
  `review_comment` TEXT NULL,
  `published_at` DATETIME(3) NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `row_version` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_teacher_public_profile_version_teacher_id_version_no` (`teacher_id`, `version_no`),
  KEY `ix_teacher_public_profile_version_reviewed_by` (`reviewed_by`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `annual_eligibility` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `academic_year_id` BIGINT NOT NULL,
  `college_id` BIGINT NOT NULL,
  `student_id` BIGINT NULL,
  `teacher_id` BIGINT NULL,
  `eligibility_status` VARCHAR(24) NOT NULL,
  `evidence_type` VARCHAR(32) NOT NULL,
  `evidence_reference` TEXT NULL,
  `source_name` VARCHAR(128) NULL,
  `valid_from` DATETIME(3) NULL,
  `valid_to` DATETIME(3) NULL,
  `changed_by` BIGINT NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `row_version` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `ix_annual_eligibility_academic_year_id` (`academic_year_id`),
  KEY `ix_annual_eligibility_college_id` (`college_id`),
  KEY `ix_annual_eligibility_student_id` (`student_id`),
  KEY `ix_annual_eligibility_teacher_id` (`teacher_id`),
  KEY `ix_annual_eligibility_changed_by` (`changed_by`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `teacher_official_profile_cache` (
  `teacher_id` BIGINT NOT NULL,
  `matched_full_name` VARCHAR(128) NOT NULL,
  `matched_college_name` VARCHAR(128) NOT NULL,
  `photo_url` VARCHAR(1024) NULL,
  `professional_title` VARCHAR(128) NULL,
  `education_level` VARCHAR(64) NULL,
  `department` VARCHAR(128) NULL,
  `teaching_level` VARCHAR(128) NULL,
  `research_directions` TEXT NULL,
  `biography` TEXT NULL,
  `education_experience` TEXT NULL,
  `work_experience` TEXT NULL,
  `courses` TEXT NULL,
  `research_and_achievements` TEXT NULL,
  `profile_url` VARCHAR(1024) NOT NULL,
  `cached_at` DATETIME(3) NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `row_version` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`teacher_id`),
  KEY `ix_teacher_official_profile_cache_cached_at` (`cached_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

-- The slot points to the one current history row; all prior qualification rows remain append-only history.
-- Exactly one of student_id / teacher_id is enforced by the Service for MySQL 5.7 compatibility.
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

CREATE TABLE `student_classification_revision` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `student_id` BIGINT NOT NULL,
  `version_no` INTEGER NOT NULL,
  `from_major_id` BIGINT NULL,
  `to_major_id` BIGINT NOT NULL,
  `from_degree_type` VARCHAR(32) NULL,
  `to_degree_type` VARCHAR(32) NOT NULL,
  `basis` TEXT NOT NULL,
  `reason` TEXT NOT NULL,
  `changed_by` BIGINT NOT NULL,
  `changed_at` DATETIME(3) NOT NULL,
  `correction_request_id` BIGINT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_student_classification_revision_student_id_version_no` (`student_id`, `version_no`),
  KEY `ix_student_classification_revision_from_major_id` (`from_major_id`),
  KEY `ix_student_classification_revision_to_major_id` (`to_major_id`),
  KEY `ix_student_classification_revision_changed_by` (`changed_by`),
  KEY `ix_student_classification_revision_correction_request_id` (`correction_request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `student_classification_impact` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `revision_id` BIGINT NOT NULL,
  `impact_no` INTEGER NOT NULL,
  `batch_id` BIGINT NOT NULL,
  `impact_type` VARCHAR(32) NOT NULL,
  `preference_submission_id` BIGINT NULL,
  `relation_adjustment_id` BIGINT NULL,
  `occurred_at` DATETIME(3) NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_student_classification_impact_revision_id_impact_no` (`revision_id`, `impact_no`),
  KEY `ix_student_classification_impact_batch_id` (`batch_id`),
  KEY `ix_student_classification_impact_preference_submission_id` (`preference_submission_id`),
  KEY `ix_student_classification_impact_relation_adjustment_id` (`relation_adjustment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `student_identity_correction_request` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `student_id` BIGINT NOT NULL,
  `submitted_at` DATETIME(3) NOT NULL,
  `current_classification_version` INTEGER NOT NULL,
  `requested_major_id` BIGINT NULL,
  `requested_degree_type` VARCHAR(32) NULL,
  `student_explanation` TEXT NOT NULL,
  `request_status` VARCHAR(24) NOT NULL,
  `handled_by` BIGINT NULL,
  `handled_at` DATETIME(3) NULL,
  `handling_comment` TEXT NULL,
  `resulting_revision_id` BIGINT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `row_version` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `ix_student_identity_correction_request_student_id` (`student_id`),
  KEY `ix_student_identity_correction_request_requested_major_id` (`requested_major_id`),
  KEY `ix_student_identity_correction_request_handled_by` (`handled_by`),
  KEY `ix_student_identity_correction_request_resulting_revision_id` (`resulting_revision_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `managed_file` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `owner_account_id` BIGINT NOT NULL,
  `purpose_code` VARCHAR(32) NOT NULL,
  `original_filename` VARCHAR(255) NOT NULL,
  `media_type` VARCHAR(128) NOT NULL,
  `file_size_bytes` BIGINT NOT NULL,
  `content_digest` VARCHAR(128) NOT NULL,
  `storage_key` VARCHAR(512) NOT NULL,
  `uploaded_at` DATETIME(3) NOT NULL,
  `retention_basis` VARCHAR(32) NOT NULL,
  `retention_until` DATE NULL,
  `file_status` VARCHAR(16) NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `row_version` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `ix_managed_file_owner_account_id` (`owner_account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `selection_batch` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `college_id` BIGINT NOT NULL,
  `academic_year_id` BIGINT NOT NULL,
  `batch_code` VARCHAR(48) NOT NULL,
  `name` VARCHAR(128) NOT NULL,
  `batch_status` VARCHAR(24) NOT NULL,
  `current_stage_id` BIGINT NULL,
  `supplement_planned` BOOLEAN NOT NULL,
  `frozen_roster_at` DATETIME(3) NULL,
  `created_by` BIGINT NOT NULL,
  `published_at` DATETIME(3) NULL,
  `started_at` DATETIME(3) NULL,
  `completed_at` DATETIME(3) NULL,
  `archived_at` DATETIME(3) NULL,
  `cancelled_at` DATETIME(3) NULL,
  `append_reason` TEXT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `row_version` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_selection_batch_college_id_academic_year_id_batch_code` (`college_id`, `academic_year_id`, `batch_code`),
  KEY `ix_selection_batch_academic_year_id` (`academic_year_id`),
  KEY `ix_selection_batch_current_stage_id` (`current_stage_id`),
  KEY `ix_selection_batch_created_by` (`created_by`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `batch_rule_snapshot` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `batch_id` BIGINT NOT NULL,
  `version_no` INTEGER NOT NULL,
  `scope_text` TEXT NOT NULL,
  `eligibility_basis` TEXT NOT NULL,
  `min_preferences` SMALLINT NOT NULL,
  `max_preferences` SMALLINT NOT NULL,
  `round_rule_version` VARCHAR(32) NOT NULL,
  `source_document_versions` TEXT NOT NULL,
  `published_by` BIGINT NOT NULL,
  `published_at` DATETIME(3) NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_batch_rule_snapshot_batch_id_version_no` (`batch_id`, `version_no`),
  KEY `ix_batch_rule_snapshot_published_by` (`published_by`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `batch_stage` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `batch_id` BIGINT NOT NULL,
  `stage_code` VARCHAR(24) NOT NULL,
  `stage_order` SMALLINT NOT NULL,
  `stage_status` VARCHAR(24) NOT NULL,
  `planned_start_at` DATETIME(3) NULL,
  `planned_end_at` DATETIME(3) NULL,
  `effective_start_at` DATETIME(3) NULL,
  `effective_end_at` DATETIME(3) NULL,
  `actual_started_at` DATETIME(3) NULL,
  `actual_closed_at` DATETIME(3) NULL,
  `close_reason` VARCHAR(32) NULL,
  `execution_cycle` INTEGER NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `row_version` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_batch_stage_batch_id_stage_code` (`batch_id`, `stage_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `schedule_revision` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `batch_id` BIGINT NOT NULL,
  `stage_id` BIGINT NULL,
  `supplement_window_id` BIGINT NULL,
  `revision_no` INTEGER NOT NULL,
  `revision_type` VARCHAR(24) NOT NULL,
  `old_start_at` DATETIME(3) NULL,
  `old_end_at` DATETIME(3) NULL,
  `new_start_at` DATETIME(3) NULL,
  `new_end_at` DATETIME(3) NULL,
  `relative_start_offset_seconds` BIGINT NULL,
  `relative_end_offset_seconds` BIGINT NULL,
  `actor_account_id` BIGINT NOT NULL,
  `reason` TEXT NOT NULL,
  `occurred_at` DATETIME(3) NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_schedule_revision_batch_id_revision_no` (`batch_id`, `revision_no`),
  KEY `ix_schedule_revision_stage_id` (`stage_id`),
  KEY `ix_schedule_revision_supplement_window_id` (`supplement_window_id`),
  KEY `ix_schedule_revision_actor_account_id` (`actor_account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `batch_lifecycle_event` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `batch_id` BIGINT NOT NULL,
  `action_code` VARCHAR(32) NOT NULL,
  `old_status` VARCHAR(24) NULL,
  `new_status` VARCHAR(24) NOT NULL,
  `stage_id` BIGINT NULL,
  `actor_account_id` BIGINT NULL,
  `actor_kind` VARCHAR(16) NOT NULL,
  `reason` TEXT NULL,
  `occurred_at` DATETIME(3) NOT NULL,
  `pause_started_at` DATETIME(3) NULL,
  `pause_ended_at` DATETIME(3) NULL,
  `frozen_stage_code` VARCHAR(24) NULL,
  `pause_duration_seconds` BIGINT NULL,
  `business_operation_id` BIGINT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `ix_batch_lifecycle_event_batch_id` (`batch_id`),
  KEY `ix_batch_lifecycle_event_stage_id` (`stage_id`),
  KEY `ix_batch_lifecycle_event_actor_account_id` (`actor_account_id`),
  KEY `ix_batch_lifecycle_event_business_operation_id` (`business_operation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `batch_student` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `batch_id` BIGINT NOT NULL,
  `student_id` BIGINT NOT NULL,
  `eligibility_snapshot` VARCHAR(24) NOT NULL,
  `account_enabled_snapshot` BOOLEAN NOT NULL,
  `eligibility_basis` TEXT NOT NULL,
  `identity_confirmed_at` DATETIME(3) NULL,
  `confirmed_classification_version` INTEGER NULL,
  `preference_status` VARCHAR(24) NOT NULL,
  `current_submission_id` BIGINT NULL,
  `final_submission_id` BIGINT NULL,
  `match_status` VARCHAR(24) NULL,
  `match_reason` VARCHAR(40) NULL,
  `match_changed_at` DATETIME(3) NULL,
  `current_relation_id` BIGINT NULL,
  `roster_correction_note` TEXT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `row_version` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_batch_student_batch_id_student_id` (`batch_id`, `student_id`),
  KEY `ix_batch_student_batch_id_match_status_match_reason` (`batch_id`, `match_status`, `match_reason`),
  KEY `ix_batch_student_student_id` (`student_id`),
  KEY `ix_batch_student_current_submission_id` (`current_submission_id`),
  KEY `ix_batch_student_final_submission_id` (`final_submission_id`),
  KEY `ix_batch_student_current_relation_id` (`current_relation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

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
  KEY `ix_batch_student_participation_granted_by` (`granted_by`)
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
  KEY `ix_batch_teacher_participation_granted_by` (`granted_by`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `batch_teacher_quota` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `batch_id` BIGINT NOT NULL,
  `teacher_id` BIGINT NOT NULL,
  `eligibility_basis` TEXT NOT NULL,
  `quota_limit` INTEGER NOT NULL,
  `occupied_count` INTEGER NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `row_version` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_batch_teacher_quota_batch_id_teacher_id` (`batch_id`, `teacher_id`),
  KEY `ix_batch_teacher_quota_teacher_id` (`teacher_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `teacher_application_scope_version` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `batch_id` BIGINT NOT NULL,
  `teacher_id` BIGINT NOT NULL,
  `version_no` INTEGER NOT NULL,
  `allowed_degree_mask` SMALLINT NOT NULL,
  `configured_by` BIGINT NOT NULL,
  `configured_at` DATETIME(3) NOT NULL,
  `frozen_at` DATETIME(3) NULL,
  `scope_source` VARCHAR(24) NOT NULL,
  `default_all_applied` BOOLEAN NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `row_version` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_teacher_application_scope_version_batch_id_teach_092104ecc9` (`batch_id`, `teacher_id`, `version_no`),
  KEY `ix_teacher_application_scope_version_teacher_id` (`teacher_id`),
  KEY `ix_teacher_application_scope_version_configured_by` (`configured_by`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `teacher_allowed_major` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `scope_version_id` BIGINT NOT NULL,
  `major_id` BIGINT NOT NULL,
  `major_code_snapshot` VARCHAR(32) NOT NULL,
  `major_name_snapshot` VARCHAR(128) NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_teacher_allowed_major_scope_version_id_major_id` (`scope_version_id`, `major_id`),
  KEY `ix_teacher_allowed_major_major_id` (`major_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `batch_running_slot` (
  `college_id` BIGINT NOT NULL,
  `academic_year_id` BIGINT NOT NULL,
  `batch_id` BIGINT NOT NULL,
  `reserved_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`college_id`, `academic_year_id`),
  UNIQUE KEY `uq_batch_running_slot_batch_id` (`batch_id`),
  KEY `ix_batch_running_slot_academic_year_id` (`academic_year_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `teacher_application_scope_slot` (
  `batch_id` BIGINT NOT NULL,
  `teacher_id` BIGINT NOT NULL,
  `scope_version_id` BIGINT NOT NULL,
  `frozen_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`batch_id`, `teacher_id`),
  UNIQUE KEY `uq_teacher_application_scope_slot_scope_version_id` (`scope_version_id`),
  KEY `ix_teacher_application_scope_slot_teacher_id` (`teacher_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `preference_submission` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `batch_student_id` BIGINT NOT NULL,
  `version_no` INTEGER NOT NULL,
  `item_count` SMALLINT NOT NULL,
  `submitted_at` DATETIME(3) NOT NULL,
  `submitted_by` BIGINT NOT NULL,
  `locked_at` DATETIME(3) NULL,
  `submission_status` VARCHAR(16) NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `row_version` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_preference_submission_batch_student_id_version_no` (`batch_student_id`, `version_no`),
  KEY `ix_preference_submission_submitted_by` (`submitted_by`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `preference_item` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `submission_id` BIGINT NOT NULL,
  `preference_order` SMALLINT NOT NULL,
  `batch_teacher_quota_id` BIGINT NOT NULL,
  `scope_version_id` BIGINT NOT NULL,
  `classification_version` INTEGER NOT NULL,
  `major_id` BIGINT NOT NULL,
  `degree_type` VARCHAR(32) NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_preference_item_submission_id_preference_order` (`submission_id`, `preference_order`),
  UNIQUE KEY `uq_preference_item_submission_id_batch_teacher_quota_id` (`submission_id`, `batch_teacher_quota_id`),
  KEY `ix_preference_item_batch_teacher_quota_id` (`batch_teacher_quota_id`),
  KEY `ix_preference_item_scope_version_id` (`scope_version_id`),
  KEY `ix_preference_item_major_id` (`major_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `round_application` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `batch_student_id` BIGINT NOT NULL,
  `stage_id` BIGINT NOT NULL,
  `preference_item_id` BIGINT NOT NULL,
  `teacher_id` BIGINT NOT NULL,
  `application_status` VARCHAR(32) NOT NULL,
  `close_reason` VARCHAR(40) NULL,
  `entered_review_at` DATETIME(3) NULL,
  `decided_at` DATETIME(3) NULL,
  `execution_cycle` INTEGER NOT NULL,
  `sort_submitted_at` DATETIME(3) NULL,
  `sort_student_no` VARCHAR(64) NULL,
  `active_snapshot_id` BIGINT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `row_version` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_round_application_preference_item_id` (`preference_item_id`),
  KEY `ix_round_application_stage_id_teacher_id_application_status` (`stage_id`, `teacher_id`, `application_status`),
  KEY `ix_round_application_batch_student_id` (`batch_student_id`),
  KEY `ix_round_application_teacher_id` (`teacher_id`),
  KEY `ix_round_application_active_snapshot_id` (`active_snapshot_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `supplement_window` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `batch_id` BIGINT NOT NULL,
  `stage_id` BIGINT NOT NULL,
  `planned_start_at` DATETIME(3) NOT NULL,
  `planned_end_at` DATETIME(3) NOT NULL,
  `effective_start_at` DATETIME(3) NOT NULL,
  `effective_end_at` DATETIME(3) NOT NULL,
  `actual_started_at` DATETIME(3) NULL,
  `actual_closed_at` DATETIME(3) NULL,
  `window_status` VARCHAR(24) NOT NULL,
  `close_reason` VARCHAR(32) NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `row_version` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_supplement_window_batch_id` (`batch_id`),
  KEY `ix_supplement_window_stage_id` (`stage_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `supplement_teacher` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `supplement_window_id` BIGINT NOT NULL,
  `batch_teacher_quota_id` BIGINT NOT NULL,
  `permission_version` INTEGER NOT NULL,
  `allowed_from` DATETIME(3) NOT NULL,
  `revoked_at` DATETIME(3) NULL,
  `granted_by` BIGINT NOT NULL,
  `reason` TEXT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `row_version` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_supplement_teacher_supplement_window_id_batch_te_840f96b63c` (`supplement_window_id`, `batch_teacher_quota_id`, `permission_version`),
  KEY `ix_supplement_teacher_batch_teacher_quota_id` (`batch_teacher_quota_id`),
  KEY `ix_supplement_teacher_granted_by` (`granted_by`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `supplement_application` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `batch_student_id` BIGINT NOT NULL,
  `supplement_window_id` BIGINT NOT NULL,
  `teacher_id` BIGINT NOT NULL,
  `batch_teacher_quota_id` BIGINT NOT NULL,
  `application_status` VARCHAR(24) NOT NULL,
  `close_reason` VARCHAR(32) NULL,
  `submitted_at` DATETIME(3) NOT NULL,
  `decided_by` BIGINT NULL,
  `decided_at` DATETIME(3) NULL,
  `sort_submitted_at` DATETIME(3) NOT NULL,
  `sort_student_no` VARCHAR(64) NOT NULL,
  `active_snapshot_id` BIGINT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `row_version` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `ix_supplement_application_supplement_window_id_teac_e9b30fe517` (`supplement_window_id`, `teacher_id`, `application_status`),
  KEY `ix_supplement_application_batch_student_id` (`batch_student_id`),
  KEY `ix_supplement_application_teacher_id` (`teacher_id`),
  KEY `ix_supplement_application_batch_teacher_quota_id` (`batch_teacher_quota_id`),
  KEY `ix_supplement_application_decided_by` (`decided_by`),
  KEY `ix_supplement_application_active_snapshot_id` (`active_snapshot_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `application_profile_snapshot` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `round_application_id` BIGINT NULL,
  `supplement_application_id` BIGINT NULL,
  `execution_cycle` INTEGER NOT NULL,
  `captured_at` DATETIME(3) NOT NULL,
  `capture_reason` VARCHAR(24) NOT NULL,
  `full_name` VARCHAR(128) NOT NULL,
  `student_no` VARCHAR(64) NOT NULL,
  `major_id` BIGINT NOT NULL,
  `major_name_snapshot` VARCHAR(128) NOT NULL,
  `degree_type` VARCHAR(32) NOT NULL,
  `biography` TEXT NULL,
  `resume_file_id` BIGINT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_application_profile_snapshot_round_application_i_97f373d652` (`round_application_id`, `execution_cycle`),
  UNIQUE KEY `uq_application_profile_snapshot_supplement_applicat_e70c3fafb0` (`supplement_application_id`, `execution_cycle`),
  KEY `ix_application_profile_snapshot_major_id` (`major_id`),
  KEY `ix_application_profile_snapshot_resume_file_id` (`resume_file_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `application_event` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `object_type` VARCHAR(24) NOT NULL,
  `object_id` BIGINT NOT NULL,
  `execution_cycle` INTEGER NULL,
  `action_code` VARCHAR(32) NOT NULL,
  `old_status` VARCHAR(32) NULL,
  `new_status` VARCHAR(32) NULL,
  `close_reason` VARCHAR(40) NULL,
  `actor_kind` VARCHAR(16) NOT NULL,
  `actor_account_id` BIGINT NULL,
  `occurred_at` DATETIME(3) NOT NULL,
  `business_operation_id` BIGINT NULL,
  `detail_text` TEXT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `ix_application_event_actor_account_id` (`actor_account_id`),
  KEY `ix_application_event_business_operation_id` (`business_operation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `student_pending_supplement_slot` (
  `student_id` BIGINT NOT NULL,
  `supplement_application_id` BIGINT NOT NULL,
  `claimed_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`student_id`),
  UNIQUE KEY `uq_student_pending_supplement_slot_supplement_application_id` (`supplement_application_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `matching_relation` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `batch_student_id` BIGINT NOT NULL,
  `batch_teacher_quota_id` BIGINT NOT NULL,
  `academic_year_id` BIGINT NOT NULL,
  `relation_status` VARCHAR(16) NOT NULL,
  `source_type` VARCHAR(24) NOT NULL,
  `source_id` BIGINT NOT NULL,
  `created_by_operation_id` BIGINT NOT NULL,
  `original_relation_id` BIGINT NULL,
  `locked_at` DATETIME(3) NOT NULL,
  `revoked_at` DATETIME(3) NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `row_version` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `ix_matching_relation_academic_year_id_relation_stat_89a439551f` (`academic_year_id`, `relation_status`, `batch_student_id`),
  KEY `ix_matching_relation_batch_student_id` (`batch_student_id`),
  KEY `ix_matching_relation_batch_teacher_quota_id` (`batch_teacher_quota_id`),
  KEY `ix_matching_relation_created_by_operation_id` (`created_by_operation_id`),
  KEY `ix_matching_relation_original_relation_id` (`original_relation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `student_year_match_slot` (
  `student_id` BIGINT NOT NULL,
  `academic_year_id` BIGINT NOT NULL,
  `relation_id` BIGINT NOT NULL,
  `claimed_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`student_id`, `academic_year_id`),
  UNIQUE KEY `uq_student_year_match_slot_relation_id` (`relation_id`),
  KEY `ix_student_year_match_slot_academic_year_id` (`academic_year_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `student_match_event` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `batch_student_id` BIGINT NOT NULL,
  `old_status` VARCHAR(24) NULL,
  `new_status` VARCHAR(24) NOT NULL,
  `reason_code` VARCHAR(40) NOT NULL,
  `actual_round` SMALLINT NULL,
  `actual_preference_order` SMALLINT NULL,
  `source_type` VARCHAR(24) NULL,
  `source_id` BIGINT NULL,
  `occurred_at` DATETIME(3) NOT NULL,
  `business_operation_id` BIGINT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `ix_student_match_event_batch_student_id` (`batch_student_id`),
  KEY `ix_student_match_event_business_operation_id` (`business_operation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `quota_ledger` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `batch_teacher_quota_id` BIGINT NOT NULL,
  `change_type` VARCHAR(24) NOT NULL,
  `limit_before` INTEGER NOT NULL,
  `limit_after` INTEGER NOT NULL,
  `occupied_before` INTEGER NOT NULL,
  `occupied_after` INTEGER NOT NULL,
  `delta` INTEGER NOT NULL,
  `relation_id` BIGINT NULL,
  `business_operation_id` BIGINT NOT NULL,
  `actor_account_id` BIGINT NULL,
  `reason` TEXT NULL,
  `occurred_at` DATETIME(3) NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `ix_quota_ledger_batch_teacher_quota_id_occurred_at` (`batch_teacher_quota_id`, `occurred_at`),
  KEY `ix_quota_ledger_relation_id` (`relation_id`),
  KEY `ix_quota_ledger_business_operation_id` (`business_operation_id`),
  KEY `ix_quota_ledger_actor_account_id` (`actor_account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `relation_adjustment` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `batch_id` BIGINT NOT NULL,
  `student_id` BIGINT NOT NULL,
  `adjustment_type` VARCHAR(16) NOT NULL,
  `old_relation_id` BIGINT NULL,
  `new_relation_id` BIGINT NULL,
  `old_teacher_id` BIGINT NULL,
  `new_teacher_id` BIGINT NULL,
  `reason` TEXT NOT NULL,
  `approval_comment` TEXT NULL,
  `actor_account_id` BIGINT NOT NULL,
  `occurred_at` DATETIME(3) NOT NULL,
  `business_operation_id` BIGINT NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `ix_relation_adjustment_batch_id` (`batch_id`),
  KEY `ix_relation_adjustment_student_id` (`student_id`),
  KEY `ix_relation_adjustment_old_relation_id` (`old_relation_id`),
  KEY `ix_relation_adjustment_new_relation_id` (`new_relation_id`),
  KEY `ix_relation_adjustment_old_teacher_id` (`old_teacher_id`),
  KEY `ix_relation_adjustment_new_teacher_id` (`new_teacher_id`),
  KEY `ix_relation_adjustment_actor_account_id` (`actor_account_id`),
  KEY `ix_relation_adjustment_business_operation_id` (`business_operation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `business_operation` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `actor_account_id` BIGINT NULL,
  `actor_kind` VARCHAR(16) NOT NULL,
  `action_code` VARCHAR(48) NOT NULL,
  `college_id` BIGINT NULL,
  `batch_id` BIGINT NULL,
  `request_id` VARCHAR(128) NULL,
  `request_fingerprint` VARCHAR(128) NULL,
  `result_code` VARCHAR(32) NOT NULL,
  `started_at` DATETIME(3) NOT NULL,
  `completed_at` DATETIME(3) NULL,
  `sorting_rule_version` VARCHAR(32) NULL,
  `scope_version_id` BIGINT NULL,
  `classification_version` INTEGER NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `row_version` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_business_operation_actor_account_id_action_code_request_id` (`actor_account_id`, `action_code`, `request_id`),
  KEY `ix_business_operation_college_id` (`college_id`),
  KEY `ix_business_operation_batch_id` (`batch_id`),
  KEY `ix_business_operation_scope_version_id` (`scope_version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `bulk_operation_item` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `business_operation_id` BIGINT NOT NULL,
  `application_type` VARCHAR(24) NOT NULL,
  `application_id` BIGINT NOT NULL,
  `sort_submitted_at` DATETIME(3) NOT NULL,
  `sort_student_no` VARCHAR(64) NOT NULL,
  `execution_order` INTEGER NOT NULL,
  `item_result` VARCHAR(24) NOT NULL,
  `failure_reason` VARCHAR(40) NULL,
  `admission_operation_id` BIGINT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `row_version` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_bulk_operation_item_business_operation_id_execution_order` (`business_operation_id`, `execution_order`),
  KEY `ix_bulk_operation_item_admission_operation_id` (`admission_operation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `personnel_import` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `actor_account_id` BIGINT NOT NULL,
  `college_id` BIGINT NOT NULL,
  `academic_year_id` BIGINT NULL,
  `person_type` VARCHAR(16) NOT NULL,
  `template_version` VARCHAR(32) NOT NULL,
  `source_file_id` BIGINT NOT NULL,
  `business_operation_id` BIGINT NOT NULL,
  `import_status` VARCHAR(24) NOT NULL,
  `submitted_at` DATETIME(3) NOT NULL,
  `completed_at` DATETIME(3) NULL,
  `accepted_count` INTEGER NOT NULL,
  `rejected_count` INTEGER NOT NULL,
  `error_report_file_id` BIGINT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `row_version` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `ix_personnel_import_actor_account_id` (`actor_account_id`),
  KEY `ix_personnel_import_college_id` (`college_id`),
  KEY `ix_personnel_import_academic_year_id` (`academic_year_id`),
  KEY `ix_personnel_import_source_file_id` (`source_file_id`),
  KEY `ix_personnel_import_error_report_file_id` (`error_report_file_id`),
  UNIQUE KEY `uq_personnel_import_business_operation_id` (`business_operation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `personnel_import_row` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `import_id` BIGINT NOT NULL,
  `row_number` INTEGER NOT NULL,
  `person_identifier` VARCHAR(64) NOT NULL,
  `row_status` VARCHAR(24) NOT NULL,
  `error_code` VARCHAR(40) NULL,
  `error_message` TEXT NULL,
  `person_id` BIGINT NULL,
  `teacher_id` BIGINT NULL,
  `eligibility_id` BIGINT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `row_version` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `ix_personnel_import_row_import_id` (`import_id`),
  KEY `ix_personnel_import_row_person_id` (`person_id`),
  KEY `ix_personnel_import_row_teacher_id` (`teacher_id`),
  KEY `ix_personnel_import_row_eligibility_id` (`eligibility_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `site_notice` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `sender_account_id` BIGINT NULL,
  `scope_college_id` BIGINT NULL,
  `batch_id` BIGINT NULL,
  `notice_type` VARCHAR(32) NOT NULL,
  `title` VARCHAR(200) NOT NULL,
  `body` TEXT NOT NULL,
  `source_operation_id` BIGINT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `visible_at` DATETIME(3) NULL,
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `row_version` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `ix_site_notice_sender_account_id` (`sender_account_id`),
  KEY `ix_site_notice_scope_college_id` (`scope_college_id`),
  KEY `ix_site_notice_batch_id` (`batch_id`),
  KEY `ix_site_notice_source_operation_id` (`source_operation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `notice_recipient` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `notice_id` BIGINT NOT NULL,
  `account_id` BIGINT NOT NULL,
  `recipient_status` VARCHAR(16) NOT NULL,
  `delivered_at` DATETIME(3) NULL,
  `read_at` DATETIME(3) NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `row_version` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_notice_recipient_notice_id_account_id` (`notice_id`, `account_id`),
  KEY `ix_notice_recipient_account_id` (`account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `delivery_attempt` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `recipient_id` BIGINT NOT NULL,
  `attempt_no` INTEGER NOT NULL,
  `attempted_at` DATETIME(3) NOT NULL,
  `attempt_status` VARCHAR(16) NOT NULL,
  `failure_code` VARCHAR(40) NULL,
  `failure_detail` TEXT NULL,
  `retry_after` DATETIME(3) NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_delivery_attempt_recipient_id_attempt_no` (`recipient_id`, `attempt_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `data_access_record` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `account_id` BIGINT NOT NULL,
  `action_code` VARCHAR(32) NOT NULL,
  `college_id` BIGINT NULL,
  `batch_id` BIGINT NULL,
  `object_type` VARCHAR(24) NOT NULL,
  `object_id` BIGINT NULL,
  `field_set` TEXT NOT NULL,
  `authorization_basis` TEXT NOT NULL,
  `accessed_at` DATETIME(3) NOT NULL,
  `result_file_id` BIGINT NULL,
  `accessed_file_id` BIGINT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `ix_data_access_record_account_id` (`account_id`),
  KEY `ix_data_access_record_college_id` (`college_id`),
  KEY `ix_data_access_record_batch_id` (`batch_id`),
  KEY `ix_data_access_record_result_file_id` (`result_file_id`),
  KEY `ix_data_access_record_accessed_file_id` (`accessed_file_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `admin_export_job` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `batch_id` BIGINT NOT NULL,
  `requester_account_id` BIGINT NOT NULL,
  `export_type` VARCHAR(24) NOT NULL,
  `export_status` VARCHAR(16) NOT NULL,
  `request_id` VARCHAR(128) NOT NULL,
  `request_fingerprint` VARCHAR(128) NOT NULL,
  `storage_key` VARCHAR(512) NULL,
  `original_filename` VARCHAR(255) NULL,
  `file_size_bytes` BIGINT NULL,
  `row_count` INTEGER NULL,
  `error_code` VARCHAR(40) NULL,
  `created_at` DATETIME(3) NOT NULL,
  `completed_at` DATETIME(3) NULL,
  `expires_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `row_version` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_admin_export_actor_request` (`requester_account_id`, `request_id`),
  KEY `ix_admin_export_batch_status_created` (`batch_id`, `export_status`, `created_at`),
  KEY `ix_admin_export_expiry` (`expires_at`, `storage_key`),
  CONSTRAINT `fk_admin_export_job_batch` FOREIGN KEY (`batch_id`) REFERENCES `selection_batch` (`id`),
  CONSTRAINT `fk_admin_export_job_requester` FOREIGN KEY (`requester_account_id`) REFERENCES `account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE `audit_event` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `actor_account_id` BIGINT NULL,
  `actor_kind` VARCHAR(16) NOT NULL,
  `actor_role` VARCHAR(16) NULL,
  `scope_basis` TEXT NULL,
  `object_type` VARCHAR(32) NOT NULL,
  `object_id` BIGINT NOT NULL,
  `action_code` VARCHAR(48) NOT NULL,
  `before_values_text` TEXT NULL,
  `after_values_text` TEXT NULL,
  `reason` TEXT NULL,
  `approval_comment` TEXT NULL,
  `occurred_at` DATETIME(3) NOT NULL,
  `business_operation_id` BIGINT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `ix_audit_event_object_type_object_id_occurred_at` (`object_type`, `object_id`, `occurred_at`),
  KEY `ix_audit_event_actor_account_id` (`actor_account_id`),
  KEY `ix_audit_event_business_operation_id` (`business_operation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

-- Add physical foreign keys. Polymorphic object/source references intentionally remain service-validated.
ALTER TABLE `major` ADD CONSTRAINT `fk_major_college_id` FOREIGN KEY (`college_id`) REFERENCES `college` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `account_authorization` ADD CONSTRAINT `fk_account_authorization_account_id` FOREIGN KEY (`account_id`) REFERENCES `account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `account_authorization` ADD CONSTRAINT `fk_account_authorization_college_id` FOREIGN KEY (`college_id`) REFERENCES `college` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `account_authorization` ADD CONSTRAINT `fk_account_authorization_batch_id` FOREIGN KEY (`batch_id`) REFERENCES `selection_batch` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `account_authorization` ADD CONSTRAINT `fk_account_authorization_granted_by` FOREIGN KEY (`granted_by`) REFERENCES `account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `account_authorization` ADD CONSTRAINT `fk_account_authorization_revoked_by` FOREIGN KEY (`revoked_by`) REFERENCES `account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `temporary_credential` ADD CONSTRAINT `fk_temporary_credential_account_id` FOREIGN KEY (`account_id`) REFERENCES `account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `temporary_credential` ADD CONSTRAINT `fk_temporary_credential_issued_operation_id` FOREIGN KEY (`issued_operation_id`) REFERENCES `business_operation` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `student` ADD CONSTRAINT `fk_student_account_id` FOREIGN KEY (`account_id`) REFERENCES `account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `student` ADD CONSTRAINT `fk_student_college_id` FOREIGN KEY (`college_id`) REFERENCES `college` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `student` ADD CONSTRAINT `fk_student_major_id` FOREIGN KEY (`major_id`) REFERENCES `major` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `teacher` ADD CONSTRAINT `fk_teacher_account_id` FOREIGN KEY (`account_id`) REFERENCES `account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `teacher` ADD CONSTRAINT `fk_teacher_college_id` FOREIGN KEY (`college_id`) REFERENCES `college` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `teacher` ADD CONSTRAINT `fk_teacher_current_public_profile_version_id` FOREIGN KEY (`current_public_profile_version_id`) REFERENCES `teacher_public_profile_version` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `student_profile_version` ADD CONSTRAINT `fk_student_profile_version_student_id` FOREIGN KEY (`student_id`) REFERENCES `student` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `student_profile_version` ADD CONSTRAINT `fk_student_profile_version_resume_file_id` FOREIGN KEY (`resume_file_id`) REFERENCES `managed_file` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `student_profile_version` ADD CONSTRAINT `fk_student_profile_version_changed_by` FOREIGN KEY (`changed_by`) REFERENCES `account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `teacher_public_profile_version` ADD CONSTRAINT `fk_teacher_public_profile_version_teacher_id` FOREIGN KEY (`teacher_id`) REFERENCES `teacher` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `teacher_official_profile_cache` ADD CONSTRAINT `fk_teacher_official_profile_cache_teacher_id` FOREIGN KEY (`teacher_id`) REFERENCES `teacher` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `teacher_public_profile_version` ADD CONSTRAINT `fk_teacher_public_profile_version_reviewed_by` FOREIGN KEY (`reviewed_by`) REFERENCES `account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `annual_eligibility` ADD CONSTRAINT `fk_annual_eligibility_academic_year_id` FOREIGN KEY (`academic_year_id`) REFERENCES `academic_year` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `annual_eligibility` ADD CONSTRAINT `fk_annual_eligibility_college_id` FOREIGN KEY (`college_id`) REFERENCES `college` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `annual_eligibility` ADD CONSTRAINT `fk_annual_eligibility_student_id` FOREIGN KEY (`student_id`) REFERENCES `student` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `annual_eligibility` ADD CONSTRAINT `fk_annual_eligibility_teacher_id` FOREIGN KEY (`teacher_id`) REFERENCES `teacher` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `annual_eligibility` ADD CONSTRAINT `fk_annual_eligibility_changed_by` FOREIGN KEY (`changed_by`) REFERENCES `account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `annual_eligibility_slot` ADD CONSTRAINT `fk_annual_eligibility_slot_academic_year_id` FOREIGN KEY (`academic_year_id`) REFERENCES `academic_year` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `annual_eligibility_slot` ADD CONSTRAINT `fk_annual_eligibility_slot_college_id` FOREIGN KEY (`college_id`) REFERENCES `college` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `annual_eligibility_slot` ADD CONSTRAINT `fk_annual_eligibility_slot_student_id` FOREIGN KEY (`student_id`) REFERENCES `student` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `annual_eligibility_slot` ADD CONSTRAINT `fk_annual_eligibility_slot_teacher_id` FOREIGN KEY (`teacher_id`) REFERENCES `teacher` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `annual_eligibility_slot` ADD CONSTRAINT `fk_annual_eligibility_slot_eligibility_id` FOREIGN KEY (`eligibility_id`) REFERENCES `annual_eligibility` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `student_classification_revision` ADD CONSTRAINT `fk_student_classification_revision_student_id` FOREIGN KEY (`student_id`) REFERENCES `student` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `student_classification_revision` ADD CONSTRAINT `fk_student_classification_revision_from_major_id` FOREIGN KEY (`from_major_id`) REFERENCES `major` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `student_classification_revision` ADD CONSTRAINT `fk_student_classification_revision_to_major_id` FOREIGN KEY (`to_major_id`) REFERENCES `major` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `student_classification_revision` ADD CONSTRAINT `fk_student_classification_revision_changed_by` FOREIGN KEY (`changed_by`) REFERENCES `account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `student_classification_revision` ADD CONSTRAINT `fk_student_classification_revision_correction_request_id` FOREIGN KEY (`correction_request_id`) REFERENCES `student_identity_correction_request` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `student_classification_impact` ADD CONSTRAINT `fk_student_classification_impact_revision_id` FOREIGN KEY (`revision_id`) REFERENCES `student_classification_revision` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `student_classification_impact` ADD CONSTRAINT `fk_student_classification_impact_batch_id` FOREIGN KEY (`batch_id`) REFERENCES `selection_batch` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `student_classification_impact` ADD CONSTRAINT `fk_student_classification_impact_preference_submission_id` FOREIGN KEY (`preference_submission_id`) REFERENCES `preference_submission` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `student_classification_impact` ADD CONSTRAINT `fk_student_classification_impact_relation_adjustment_id` FOREIGN KEY (`relation_adjustment_id`) REFERENCES `relation_adjustment` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `student_identity_correction_request` ADD CONSTRAINT `fk_student_identity_correction_request_student_id` FOREIGN KEY (`student_id`) REFERENCES `student` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `student_identity_correction_request` ADD CONSTRAINT `fk_student_identity_correction_request_requested_major_id` FOREIGN KEY (`requested_major_id`) REFERENCES `major` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `student_identity_correction_request` ADD CONSTRAINT `fk_student_identity_correction_request_handled_by` FOREIGN KEY (`handled_by`) REFERENCES `account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `student_identity_correction_request` ADD CONSTRAINT `fk_student_identity_correction_request_resulting_revision_id` FOREIGN KEY (`resulting_revision_id`) REFERENCES `student_classification_revision` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `managed_file` ADD CONSTRAINT `fk_managed_file_owner_account_id` FOREIGN KEY (`owner_account_id`) REFERENCES `account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `selection_batch` ADD CONSTRAINT `fk_selection_batch_college_id` FOREIGN KEY (`college_id`) REFERENCES `college` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `selection_batch` ADD CONSTRAINT `fk_selection_batch_academic_year_id` FOREIGN KEY (`academic_year_id`) REFERENCES `academic_year` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `selection_batch` ADD CONSTRAINT `fk_selection_batch_current_stage_id` FOREIGN KEY (`current_stage_id`) REFERENCES `batch_stage` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `selection_batch` ADD CONSTRAINT `fk_selection_batch_created_by` FOREIGN KEY (`created_by`) REFERENCES `account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `batch_rule_snapshot` ADD CONSTRAINT `fk_batch_rule_snapshot_batch_id` FOREIGN KEY (`batch_id`) REFERENCES `selection_batch` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `batch_rule_snapshot` ADD CONSTRAINT `fk_batch_rule_snapshot_published_by` FOREIGN KEY (`published_by`) REFERENCES `account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `batch_stage` ADD CONSTRAINT `fk_batch_stage_batch_id` FOREIGN KEY (`batch_id`) REFERENCES `selection_batch` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `schedule_revision` ADD CONSTRAINT `fk_schedule_revision_batch_id` FOREIGN KEY (`batch_id`) REFERENCES `selection_batch` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `schedule_revision` ADD CONSTRAINT `fk_schedule_revision_stage_id` FOREIGN KEY (`stage_id`) REFERENCES `batch_stage` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `schedule_revision` ADD CONSTRAINT `fk_schedule_revision_supplement_window_id` FOREIGN KEY (`supplement_window_id`) REFERENCES `supplement_window` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `schedule_revision` ADD CONSTRAINT `fk_schedule_revision_actor_account_id` FOREIGN KEY (`actor_account_id`) REFERENCES `account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `batch_lifecycle_event` ADD CONSTRAINT `fk_batch_lifecycle_event_batch_id` FOREIGN KEY (`batch_id`) REFERENCES `selection_batch` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `batch_lifecycle_event` ADD CONSTRAINT `fk_batch_lifecycle_event_stage_id` FOREIGN KEY (`stage_id`) REFERENCES `batch_stage` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `batch_lifecycle_event` ADD CONSTRAINT `fk_batch_lifecycle_event_actor_account_id` FOREIGN KEY (`actor_account_id`) REFERENCES `account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `batch_lifecycle_event` ADD CONSTRAINT `fk_batch_lifecycle_event_business_operation_id` FOREIGN KEY (`business_operation_id`) REFERENCES `business_operation` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `batch_student` ADD CONSTRAINT `fk_batch_student_batch_id` FOREIGN KEY (`batch_id`) REFERENCES `selection_batch` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `batch_student` ADD CONSTRAINT `fk_batch_student_student_id` FOREIGN KEY (`student_id`) REFERENCES `student` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `batch_student_participation` ADD CONSTRAINT `fk_batch_student_participation_batch_id` FOREIGN KEY (`batch_id`) REFERENCES `selection_batch` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `batch_student_participation` ADD CONSTRAINT `fk_batch_student_participation_student_id` FOREIGN KEY (`student_id`) REFERENCES `student` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `batch_student_participation` ADD CONSTRAINT `fk_batch_student_participation_granted_by` FOREIGN KEY (`granted_by`) REFERENCES `account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `batch_teacher_participation` ADD CONSTRAINT `fk_batch_teacher_participation_batch_id` FOREIGN KEY (`batch_id`) REFERENCES `selection_batch` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `batch_teacher_participation` ADD CONSTRAINT `fk_batch_teacher_participation_teacher_id` FOREIGN KEY (`teacher_id`) REFERENCES `teacher` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `batch_teacher_participation` ADD CONSTRAINT `fk_batch_teacher_participation_granted_by` FOREIGN KEY (`granted_by`) REFERENCES `account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `batch_student` ADD CONSTRAINT `fk_batch_student_current_submission_id` FOREIGN KEY (`current_submission_id`) REFERENCES `preference_submission` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `batch_student` ADD CONSTRAINT `fk_batch_student_final_submission_id` FOREIGN KEY (`final_submission_id`) REFERENCES `preference_submission` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `batch_student` ADD CONSTRAINT `fk_batch_student_current_relation_id` FOREIGN KEY (`current_relation_id`) REFERENCES `matching_relation` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `batch_teacher_quota` ADD CONSTRAINT `fk_batch_teacher_quota_batch_id` FOREIGN KEY (`batch_id`) REFERENCES `selection_batch` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `batch_teacher_quota` ADD CONSTRAINT `fk_batch_teacher_quota_teacher_id` FOREIGN KEY (`teacher_id`) REFERENCES `teacher` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `teacher_application_scope_version` ADD CONSTRAINT `fk_teacher_application_scope_version_batch_id` FOREIGN KEY (`batch_id`) REFERENCES `selection_batch` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `teacher_application_scope_version` ADD CONSTRAINT `fk_teacher_application_scope_version_teacher_id` FOREIGN KEY (`teacher_id`) REFERENCES `teacher` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `teacher_application_scope_version` ADD CONSTRAINT `fk_teacher_application_scope_version_configured_by` FOREIGN KEY (`configured_by`) REFERENCES `account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `teacher_allowed_major` ADD CONSTRAINT `fk_teacher_allowed_major_scope_version_id` FOREIGN KEY (`scope_version_id`) REFERENCES `teacher_application_scope_version` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `teacher_allowed_major` ADD CONSTRAINT `fk_teacher_allowed_major_major_id` FOREIGN KEY (`major_id`) REFERENCES `major` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `batch_running_slot` ADD CONSTRAINT `fk_batch_running_slot_college_id` FOREIGN KEY (`college_id`) REFERENCES `college` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `batch_running_slot` ADD CONSTRAINT `fk_batch_running_slot_academic_year_id` FOREIGN KEY (`academic_year_id`) REFERENCES `academic_year` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `batch_running_slot` ADD CONSTRAINT `fk_batch_running_slot_batch_id` FOREIGN KEY (`batch_id`) REFERENCES `selection_batch` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `teacher_application_scope_slot` ADD CONSTRAINT `fk_teacher_application_scope_slot_batch_id` FOREIGN KEY (`batch_id`) REFERENCES `selection_batch` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `teacher_application_scope_slot` ADD CONSTRAINT `fk_teacher_application_scope_slot_teacher_id` FOREIGN KEY (`teacher_id`) REFERENCES `teacher` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `teacher_application_scope_slot` ADD CONSTRAINT `fk_teacher_application_scope_slot_scope_version_id` FOREIGN KEY (`scope_version_id`) REFERENCES `teacher_application_scope_version` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `preference_submission` ADD CONSTRAINT `fk_preference_submission_batch_student_id` FOREIGN KEY (`batch_student_id`) REFERENCES `batch_student` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `preference_submission` ADD CONSTRAINT `fk_preference_submission_submitted_by` FOREIGN KEY (`submitted_by`) REFERENCES `account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `preference_item` ADD CONSTRAINT `fk_preference_item_submission_id` FOREIGN KEY (`submission_id`) REFERENCES `preference_submission` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `preference_item` ADD CONSTRAINT `fk_preference_item_batch_teacher_quota_id` FOREIGN KEY (`batch_teacher_quota_id`) REFERENCES `batch_teacher_quota` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `preference_item` ADD CONSTRAINT `fk_preference_item_scope_version_id` FOREIGN KEY (`scope_version_id`) REFERENCES `teacher_application_scope_version` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `preference_item` ADD CONSTRAINT `fk_preference_item_major_id` FOREIGN KEY (`major_id`) REFERENCES `major` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `round_application` ADD CONSTRAINT `fk_round_application_batch_student_id` FOREIGN KEY (`batch_student_id`) REFERENCES `batch_student` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `round_application` ADD CONSTRAINT `fk_round_application_stage_id` FOREIGN KEY (`stage_id`) REFERENCES `batch_stage` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `round_application` ADD CONSTRAINT `fk_round_application_preference_item_id` FOREIGN KEY (`preference_item_id`) REFERENCES `preference_item` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `round_application` ADD CONSTRAINT `fk_round_application_teacher_id` FOREIGN KEY (`teacher_id`) REFERENCES `teacher` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `round_application` ADD CONSTRAINT `fk_round_application_active_snapshot_id` FOREIGN KEY (`active_snapshot_id`) REFERENCES `application_profile_snapshot` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `supplement_window` ADD CONSTRAINT `fk_supplement_window_batch_id` FOREIGN KEY (`batch_id`) REFERENCES `selection_batch` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `supplement_window` ADD CONSTRAINT `fk_supplement_window_stage_id` FOREIGN KEY (`stage_id`) REFERENCES `batch_stage` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `supplement_teacher` ADD CONSTRAINT `fk_supplement_teacher_supplement_window_id` FOREIGN KEY (`supplement_window_id`) REFERENCES `supplement_window` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `supplement_teacher` ADD CONSTRAINT `fk_supplement_teacher_batch_teacher_quota_id` FOREIGN KEY (`batch_teacher_quota_id`) REFERENCES `batch_teacher_quota` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `supplement_teacher` ADD CONSTRAINT `fk_supplement_teacher_granted_by` FOREIGN KEY (`granted_by`) REFERENCES `account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `supplement_application` ADD CONSTRAINT `fk_supplement_application_batch_student_id` FOREIGN KEY (`batch_student_id`) REFERENCES `batch_student` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `supplement_application` ADD CONSTRAINT `fk_supplement_application_supplement_window_id` FOREIGN KEY (`supplement_window_id`) REFERENCES `supplement_window` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `supplement_application` ADD CONSTRAINT `fk_supplement_application_teacher_id` FOREIGN KEY (`teacher_id`) REFERENCES `teacher` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `supplement_application` ADD CONSTRAINT `fk_supplement_application_batch_teacher_quota_id` FOREIGN KEY (`batch_teacher_quota_id`) REFERENCES `batch_teacher_quota` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `supplement_application` ADD CONSTRAINT `fk_supplement_application_decided_by` FOREIGN KEY (`decided_by`) REFERENCES `account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `supplement_application` ADD CONSTRAINT `fk_supplement_application_active_snapshot_id` FOREIGN KEY (`active_snapshot_id`) REFERENCES `application_profile_snapshot` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `application_profile_snapshot` ADD CONSTRAINT `fk_application_profile_snapshot_round_application_id` FOREIGN KEY (`round_application_id`) REFERENCES `round_application` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `application_profile_snapshot` ADD CONSTRAINT `fk_application_profile_snapshot_supplement_application_id` FOREIGN KEY (`supplement_application_id`) REFERENCES `supplement_application` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `application_profile_snapshot` ADD CONSTRAINT `fk_application_profile_snapshot_major_id` FOREIGN KEY (`major_id`) REFERENCES `major` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `application_profile_snapshot` ADD CONSTRAINT `fk_application_profile_snapshot_resume_file_id` FOREIGN KEY (`resume_file_id`) REFERENCES `managed_file` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `application_event` ADD CONSTRAINT `fk_application_event_actor_account_id` FOREIGN KEY (`actor_account_id`) REFERENCES `account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `application_event` ADD CONSTRAINT `fk_application_event_business_operation_id` FOREIGN KEY (`business_operation_id`) REFERENCES `business_operation` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `student_pending_supplement_slot` ADD CONSTRAINT `fk_student_pending_supplement_slot_student_id` FOREIGN KEY (`student_id`) REFERENCES `student` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `student_pending_supplement_slot` ADD CONSTRAINT `fk_student_pending_supplement_slot_supplement_application_id` FOREIGN KEY (`supplement_application_id`) REFERENCES `supplement_application` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `matching_relation` ADD CONSTRAINT `fk_matching_relation_batch_student_id` FOREIGN KEY (`batch_student_id`) REFERENCES `batch_student` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `matching_relation` ADD CONSTRAINT `fk_matching_relation_batch_teacher_quota_id` FOREIGN KEY (`batch_teacher_quota_id`) REFERENCES `batch_teacher_quota` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `matching_relation` ADD CONSTRAINT `fk_matching_relation_academic_year_id` FOREIGN KEY (`academic_year_id`) REFERENCES `academic_year` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `matching_relation` ADD CONSTRAINT `fk_matching_relation_created_by_operation_id` FOREIGN KEY (`created_by_operation_id`) REFERENCES `business_operation` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `matching_relation` ADD CONSTRAINT `fk_matching_relation_original_relation_id` FOREIGN KEY (`original_relation_id`) REFERENCES `matching_relation` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `student_year_match_slot` ADD CONSTRAINT `fk_student_year_match_slot_student_id` FOREIGN KEY (`student_id`) REFERENCES `student` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `student_year_match_slot` ADD CONSTRAINT `fk_student_year_match_slot_academic_year_id` FOREIGN KEY (`academic_year_id`) REFERENCES `academic_year` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `student_year_match_slot` ADD CONSTRAINT `fk_student_year_match_slot_relation_id` FOREIGN KEY (`relation_id`) REFERENCES `matching_relation` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `student_match_event` ADD CONSTRAINT `fk_student_match_event_batch_student_id` FOREIGN KEY (`batch_student_id`) REFERENCES `batch_student` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `student_match_event` ADD CONSTRAINT `fk_student_match_event_business_operation_id` FOREIGN KEY (`business_operation_id`) REFERENCES `business_operation` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `quota_ledger` ADD CONSTRAINT `fk_quota_ledger_batch_teacher_quota_id` FOREIGN KEY (`batch_teacher_quota_id`) REFERENCES `batch_teacher_quota` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `quota_ledger` ADD CONSTRAINT `fk_quota_ledger_relation_id` FOREIGN KEY (`relation_id`) REFERENCES `matching_relation` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `quota_ledger` ADD CONSTRAINT `fk_quota_ledger_business_operation_id` FOREIGN KEY (`business_operation_id`) REFERENCES `business_operation` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `quota_ledger` ADD CONSTRAINT `fk_quota_ledger_actor_account_id` FOREIGN KEY (`actor_account_id`) REFERENCES `account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `relation_adjustment` ADD CONSTRAINT `fk_relation_adjustment_batch_id` FOREIGN KEY (`batch_id`) REFERENCES `selection_batch` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `relation_adjustment` ADD CONSTRAINT `fk_relation_adjustment_student_id` FOREIGN KEY (`student_id`) REFERENCES `student` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `relation_adjustment` ADD CONSTRAINT `fk_relation_adjustment_old_relation_id` FOREIGN KEY (`old_relation_id`) REFERENCES `matching_relation` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `relation_adjustment` ADD CONSTRAINT `fk_relation_adjustment_new_relation_id` FOREIGN KEY (`new_relation_id`) REFERENCES `matching_relation` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `relation_adjustment` ADD CONSTRAINT `fk_relation_adjustment_old_teacher_id` FOREIGN KEY (`old_teacher_id`) REFERENCES `teacher` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `relation_adjustment` ADD CONSTRAINT `fk_relation_adjustment_new_teacher_id` FOREIGN KEY (`new_teacher_id`) REFERENCES `teacher` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `relation_adjustment` ADD CONSTRAINT `fk_relation_adjustment_actor_account_id` FOREIGN KEY (`actor_account_id`) REFERENCES `account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `relation_adjustment` ADD CONSTRAINT `fk_relation_adjustment_business_operation_id` FOREIGN KEY (`business_operation_id`) REFERENCES `business_operation` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `business_operation` ADD CONSTRAINT `fk_business_operation_actor_account_id` FOREIGN KEY (`actor_account_id`) REFERENCES `account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `business_operation` ADD CONSTRAINT `fk_business_operation_college_id` FOREIGN KEY (`college_id`) REFERENCES `college` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `business_operation` ADD CONSTRAINT `fk_business_operation_batch_id` FOREIGN KEY (`batch_id`) REFERENCES `selection_batch` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `business_operation` ADD CONSTRAINT `fk_business_operation_scope_version_id` FOREIGN KEY (`scope_version_id`) REFERENCES `teacher_application_scope_version` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `bulk_operation_item` ADD CONSTRAINT `fk_bulk_operation_item_business_operation_id` FOREIGN KEY (`business_operation_id`) REFERENCES `business_operation` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `bulk_operation_item` ADD CONSTRAINT `fk_bulk_operation_item_admission_operation_id` FOREIGN KEY (`admission_operation_id`) REFERENCES `business_operation` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `personnel_import` ADD CONSTRAINT `fk_personnel_import_actor_account_id` FOREIGN KEY (`actor_account_id`) REFERENCES `account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `personnel_import` ADD CONSTRAINT `fk_personnel_import_college_id` FOREIGN KEY (`college_id`) REFERENCES `college` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `personnel_import` ADD CONSTRAINT `fk_personnel_import_academic_year_id` FOREIGN KEY (`academic_year_id`) REFERENCES `academic_year` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `personnel_import` ADD CONSTRAINT `fk_personnel_import_source_file_id` FOREIGN KEY (`source_file_id`) REFERENCES `managed_file` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `personnel_import` ADD CONSTRAINT `fk_personnel_import_error_report_file_id` FOREIGN KEY (`error_report_file_id`) REFERENCES `managed_file` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `personnel_import` ADD CONSTRAINT `fk_personnel_import_business_operation_id` FOREIGN KEY (`business_operation_id`) REFERENCES `business_operation` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `personnel_import_row` ADD CONSTRAINT `fk_personnel_import_row_import_id` FOREIGN KEY (`import_id`) REFERENCES `personnel_import` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `personnel_import_row` ADD CONSTRAINT `fk_personnel_import_row_person_id` FOREIGN KEY (`person_id`) REFERENCES `student` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `personnel_import_row` ADD CONSTRAINT `fk_personnel_import_row_teacher_id` FOREIGN KEY (`teacher_id`) REFERENCES `teacher` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `personnel_import_row` ADD CONSTRAINT `fk_personnel_import_row_eligibility_id` FOREIGN KEY (`eligibility_id`) REFERENCES `annual_eligibility` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `site_notice` ADD CONSTRAINT `fk_site_notice_sender_account_id` FOREIGN KEY (`sender_account_id`) REFERENCES `account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `site_notice` ADD CONSTRAINT `fk_site_notice_scope_college_id` FOREIGN KEY (`scope_college_id`) REFERENCES `college` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `site_notice` ADD CONSTRAINT `fk_site_notice_batch_id` FOREIGN KEY (`batch_id`) REFERENCES `selection_batch` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `site_notice` ADD CONSTRAINT `fk_site_notice_source_operation_id` FOREIGN KEY (`source_operation_id`) REFERENCES `business_operation` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `notice_recipient` ADD CONSTRAINT `fk_notice_recipient_notice_id` FOREIGN KEY (`notice_id`) REFERENCES `site_notice` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `notice_recipient` ADD CONSTRAINT `fk_notice_recipient_account_id` FOREIGN KEY (`account_id`) REFERENCES `account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `delivery_attempt` ADD CONSTRAINT `fk_delivery_attempt_recipient_id` FOREIGN KEY (`recipient_id`) REFERENCES `notice_recipient` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `data_access_record` ADD CONSTRAINT `fk_data_access_record_account_id` FOREIGN KEY (`account_id`) REFERENCES `account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `data_access_record` ADD CONSTRAINT `fk_data_access_record_college_id` FOREIGN KEY (`college_id`) REFERENCES `college` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `data_access_record` ADD CONSTRAINT `fk_data_access_record_batch_id` FOREIGN KEY (`batch_id`) REFERENCES `selection_batch` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `data_access_record` ADD CONSTRAINT `fk_data_access_record_result_file_id` FOREIGN KEY (`result_file_id`) REFERENCES `managed_file` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `data_access_record` ADD CONSTRAINT `fk_data_access_record_accessed_file_id` FOREIGN KEY (`accessed_file_id`) REFERENCES `managed_file` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `audit_event` ADD CONSTRAINT `fk_audit_event_actor_account_id` FOREIGN KEY (`actor_account_id`) REFERENCES `account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `audit_event` ADD CONSTRAINT `fk_audit_event_business_operation_id` FOREIGN KEY (`business_operation_id`) REFERENCES `business_operation` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;

-- MySQL 5.7 ignores CHECK constraints. Exactly-one-of, status, scope, and cross-row invariants
-- remain validated in the application transaction as specified in the design document.
