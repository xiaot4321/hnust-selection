-- Adds a managed-file reference for the avatar attached to each reviewed profile version.
-- Target: MySQL 5.7.36. Back up the target database and verify the existing profile-field migration first.
ALTER TABLE `teacher_public_profile_version`
  ADD COLUMN `avatar_file_id` BIGINT NULL AFTER `research_and_achievements`,
  ADD KEY `ix_teacher_public_profile_version_avatar_file_id` (`avatar_file_id`),
  ADD CONSTRAINT `fk_teacher_public_profile_version_avatar_file_id`
    FOREIGN KEY (`avatar_file_id`) REFERENCES `managed_file` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT;
