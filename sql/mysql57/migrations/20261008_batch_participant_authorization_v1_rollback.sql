-- Removes regular-round participant allowlists and all configured membership rows.
-- Ensure these authorization histories are no longer needed before running.
DROP TABLE `batch_teacher_participation`;
DROP TABLE `batch_student_participation`;
