-- Removes the teacher-maintained school-profile sections and all values entered in them.
-- Export required values before running this rollback.
ALTER TABLE `teacher_public_profile_version`
  DROP COLUMN `research_and_achievements`,
  DROP COLUMN `courses`,
  DROP COLUMN `work_experience`,
  DROP COLUMN `education_experience`;
