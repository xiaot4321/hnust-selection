-- Forward-only migration for the confirmed no-expiry temporary credential policy.
-- Target: MySQL 5.7.36. Take a database backup before applying to an existing environment.
-- Outstanding unused credentials have their prior 72-hour deadline cleared, including any
-- that were still unused when that deadline passed. Consumed/revoked rows keep their history.
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `hnust_selection`;

-- Preflight snapshot: review total credentials and outstanding credentials with deadlines.
SELECT COUNT(*) AS total_credentials,
       SUM(used_at IS NULL AND revoked_at IS NULL) AS outstanding_credentials,
       SUM(used_at IS NULL AND revoked_at IS NULL AND expires_at IS NOT NULL) AS deadlines_to_clear
FROM `temporary_credential`;

ALTER TABLE `temporary_credential`
  MODIFY COLUMN `expires_at` DATETIME(3) NULL;

UPDATE `temporary_credential`
SET `expires_at` = NULL
WHERE `used_at` IS NULL AND `revoked_at` IS NULL;

-- This result must be zero after the migration.
SELECT COUNT(*) AS outstanding_credentials_with_deadline
FROM `temporary_credential`
WHERE `used_at` IS NULL AND `revoked_at` IS NULL AND `expires_at` IS NOT NULL;
