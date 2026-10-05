-- Recovery rollback for 20261005_credential_expiry_removal_v1.sql.
-- This restores the prior 72-hour policy using issued_at as the original deadline basis.
-- Applying it intentionally makes outstanding credentials older than 72 hours unusable again.
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `hnust_selection`;

UPDATE `temporary_credential`
SET `expires_at` = DATE_ADD(`issued_at`, INTERVAL 72 HOUR)
WHERE `expires_at` IS NULL;

ALTER TABLE `temporary_credential`
  MODIFY COLUMN `expires_at` DATETIME(3) NOT NULL;

-- This result must be zero after rollback.
SELECT COUNT(*) AS credentials_without_deadline
FROM `temporary_credential`
WHERE `expires_at` IS NULL;
