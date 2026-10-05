-- 开发验证数据：为年度资格页面提供一个可选学年。
-- 可重复执行；若 TEST-2026 已存在，不会覆盖已有记录。
SET NAMES utf8mb4;
USE `hnust_selection`;

INSERT INTO `academic_year` (`year_code`, `display_name`, `starts_on`, `ends_on`)
SELECT 'TEST-2026', '2026 测试学年（开发验证）', NULL, NULL
WHERE NOT EXISTS (
  SELECT 1 FROM `academic_year` WHERE `year_code` = 'TEST-2026'
);

SELECT `id`, `year_code`, `display_name`, `starts_on`, `ends_on`
FROM `academic_year`
WHERE `year_code` = 'TEST-2026';
