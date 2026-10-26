-- TODO-74 data migration: publish existing pending teacher profiles directly.
-- No table, column, index, or foreign-key changes are made.
-- Precondition: the target database is on the current 54-table/168-FK schema.
-- Take a database backup before execution. Run this before deploying/resuming the application.

DROP TEMPORARY TABLE IF EXISTS tmp_teacher_profile_pending_migration;
CREATE TEMPORARY TABLE tmp_teacher_profile_pending_migration (
  teacher_id BIGINT NOT NULL PRIMARY KEY
) ENGINE=MEMORY;

INSERT INTO tmp_teacher_profile_pending_migration (teacher_id)
SELECT DISTINCT teacher_id
FROM teacher_public_profile_version
WHERE review_status = 'PENDING_REVIEW';

START TRANSACTION;

UPDATE teacher_public_profile_version
SET review_status = 'APPROVED',
    reviewed_by = NULL,
    reviewed_at = NULL,
    review_comment = NULL,
    published_at = COALESCE(published_at, UTC_TIMESTAMP(3)),
    row_version = row_version + 1
WHERE review_status = 'PENDING_REVIEW';

UPDATE teacher teacher
JOIN tmp_teacher_profile_pending_migration pending_teacher ON pending_teacher.teacher_id = teacher.id
SET teacher.current_public_profile_version_id = (
      SELECT profile.id
      FROM teacher_public_profile_version profile
      WHERE profile.teacher_id = teacher.id
        AND profile.review_status = 'APPROVED'
        AND profile.published_at IS NOT NULL
      ORDER BY profile.version_no DESC
      LIMIT 1
    ),
    teacher.row_version = teacher.row_version + 1;

COMMIT;

SELECT COUNT(*) AS remaining_pending_profile_count
FROM teacher_public_profile_version
WHERE review_status = 'PENDING_REVIEW';

SELECT COUNT(*) AS migrated_teacher_count
FROM tmp_teacher_profile_pending_migration;

DROP TEMPORARY TABLE tmp_teacher_profile_pending_migration;
