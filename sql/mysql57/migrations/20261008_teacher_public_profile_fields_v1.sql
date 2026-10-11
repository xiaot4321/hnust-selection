-- Adds the school-profile sections maintained by teachers and reviewed by admins.
-- Target: MySQL 5.7.36. Take a backup and verify the current schema before applying.
ALTER TABLE `teacher_public_profile_version`
  ADD COLUMN `education_experience` TEXT NULL AFTER `biography`,
  ADD COLUMN `work_experience` TEXT NULL AFTER `education_experience`,
  ADD COLUMN `courses` TEXT NULL AFTER `work_experience`,
  ADD COLUMN `research_and_achievements` TEXT NULL AFTER `courses`;
