-- TODO-74 rollback for the data migration only; no schema objects are changed.
-- IMPORTANT: use only before the application is resumed after migration.
-- If the new application has already published additional profiles, restore the pre-migration
-- database backup instead of running this script, so those new publications are not reverted.

DROP TEMPORARY TABLE IF EXISTS tmp_teacher_profile_pending_rollback;
CREATE TEMPORARY TABLE tmp_teacher_profile_pending_rollback (
  teacher_id BIGINT NOT NULL PRIMARY KEY
) ENGINE=MEMORY;

INSERT INTO tmp_teacher_profile_pending_rollback (teacher_id)
SELECT DISTINCT teacher_id
FROM teacher_public_profile_version
WHERE review_status = 'APPROVED'
  AND reviewed_by IS NULL
  AND reviewed_at IS NULL
  AND published_at IS NOT NULL;

START TRANSACTION;

UPDATE teacher teacher
JOIN tmp_teacher_profile_pending_rollback migrated_teacher ON migrated_teacher.teacher_id = teacher.id
LEFT JOIN (
  SELECT previous_profile.teacher_id, MAX(previous_profile.version_no) AS version_no
  FROM teacher_public_profile_version previous_profile
  WHERE previous_profile.review_status = 'APPROVED'
    AND previous_profile.reviewed_by IS NOT NULL
    AND previous_profile.published_at IS NOT NULL
  GROUP BY previous_profile.teacher_id
) previous_version ON previous_version.teacher_id = teacher.id
LEFT JOIN teacher_public_profile_version previous_profile
  ON previous_profile.teacher_id = previous_version.teacher_id
 AND previous_profile.version_no = previous_version.version_no
SET teacher.current_public_profile_version_id = previous_profile.id,
    teacher.row_version = teacher.row_version + 1;

UPDATE teacher_public_profile_version
SET review_status = 'PENDING_REVIEW',
    published_at = NULL,
    row_version = row_version + 1
WHERE review_status = 'APPROVED'
  AND reviewed_by IS NULL
  AND reviewed_at IS NULL
  AND published_at IS NOT NULL;

COMMIT;

SELECT COUNT(*) AS pending_profile_count
FROM teacher_public_profile_version
WHERE review_status = 'PENDING_REVIEW';

DROP TEMPORARY TABLE tmp_teacher_profile_pending_rollback;
