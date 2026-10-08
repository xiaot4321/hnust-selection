-- Removes the optional public faculty-profile cache and all cached copies.
-- Export any cache rows that must be retained before running this rollback.
DROP TABLE `teacher_official_profile_cache`;
