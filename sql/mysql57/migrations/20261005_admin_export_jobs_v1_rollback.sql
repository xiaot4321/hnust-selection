-- Roll back only when no required export job history remains. This removes the table, not private files.
-- Remove private files for its storage_key values first, or preserve them separately if they are needed.
DROP TABLE `admin_export_job`;
