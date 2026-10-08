-- Reversible cache for exact public matches from the HNUST faculty portal.
-- Target: MySQL 5.7.36. Review target schema and take a backup before applying.
CREATE TABLE `teacher_official_profile_cache` (
  `teacher_id` BIGINT NOT NULL,
  `matched_full_name` VARCHAR(128) NOT NULL,
  `matched_college_name` VARCHAR(128) NOT NULL,
  `photo_url` VARCHAR(1024) NULL,
  `professional_title` VARCHAR(128) NULL,
  `education_level` VARCHAR(64) NULL,
  `department` VARCHAR(128) NULL,
  `teaching_level` VARCHAR(128) NULL,
  `research_directions` TEXT NULL,
  `biography` TEXT NULL,
  `education_experience` TEXT NULL,
  `work_experience` TEXT NULL,
  `courses` TEXT NULL,
  `research_and_achievements` TEXT NULL,
  `profile_url` VARCHAR(1024) NOT NULL,
  `cached_at` DATETIME(3) NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `row_version` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`teacher_id`),
  KEY `ix_teacher_official_profile_cache_cached_at` (`cached_at`),
  CONSTRAINT `fk_teacher_official_profile_cache_teacher_id`
    FOREIGN KEY (`teacher_id`) REFERENCES `teacher` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;
