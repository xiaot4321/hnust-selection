-- Reversible expansion for asynchronous private CSV exports. Target: MySQL 5.7.36.
-- Apply only after reviewing the target schema and taking a backup. Do not run against production without approval.
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
