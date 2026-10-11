-- Destructive rollback: removes profile-version links to teacher avatar files.
ALTER TABLE `teacher_public_profile_version`
  DROP FOREIGN KEY `fk_teacher_public_profile_version_avatar_file_id`,
  DROP KEY `ix_teacher_public_profile_version_avatar_file_id`,
  DROP COLUMN `avatar_file_id`;
