package cn.hnust.selection.repository;

import cn.hnust.selection.vo.AllowedMajorVO;
import cn.hnust.selection.entity.TeacherOfficialProfileCacheEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Repository
public class TeacherDirectoryRepository {
    private static final String CAN_APPLY = "(batch.batch_status = 'ACTIVE' " +
        "AND (scope.allowed_degree_mask & CASE student.degree_type WHEN 'ACADEMIC_MASTER' THEN 1 " +
        "WHEN 'PROFESSIONAL_MASTER' THEN 2 ELSE 0 END) <> 0 " +
        "AND EXISTS (SELECT 1 FROM teacher_allowed_major scope_major WHERE scope_major.scope_version_id = slot.scope_version_id " +
        "AND scope_major.major_id = student.major_id) AND participant.eligibility_snapshot = 'ELIGIBLE' " +
        "AND participant.account_enabled_snapshot = 1 AND student_account.account_status = 'ACTIVE' " +
        "AND ((EXISTS (SELECT 1 FROM batch_stage filling WHERE filling.batch_id = batch.id " +
        "AND filling.stage_code = 'FILLING' AND filling.stage_status = 'OPEN' " +
        "AND filling.effective_start_at <= UTC_TIMESTAMP(3) AND UTC_TIMESTAMP(3) < filling.effective_end_at) " +
        "AND (participant.match_status IS NULL OR participant.match_status <> 'MATCHED') " +
        "AND participant.final_submission_id IS NULL " +
        "AND participant.preference_status <> 'LOCKED' " +
        "AND participant.confirmed_classification_version = student.classification_version) " +
        "OR (participant.match_status = 'UNMATCHED' " +
        "AND EXISTS (SELECT 1 FROM supplement_window supplement " +
        "WHERE supplement.batch_id = batch.id AND supplement.window_status = 'OPEN' " +
        "AND supplement.effective_start_at <= UTC_TIMESTAMP(3) AND UTC_TIMESTAMP(3) < supplement.effective_end_at " +
        ") " +
        "AND quota.occupied_count < quota.quota_limit " +
        "AND NOT EXISTS (SELECT 1 FROM student_pending_supplement_slot pending WHERE pending.student_id = student.id) " +
        "AND NOT EXISTS (SELECT 1 FROM student_year_match_slot relation_slot WHERE relation_slot.student_id = student.id " +
        "AND relation_slot.academic_year_id = batch.academic_year_id))))";

    private static final String FROM_AND_WHERE = " FROM selection_batch batch " +
        "JOIN batch_student participant ON participant.batch_id = batch.id AND participant.student_id = ? " +
        "JOIN student ON student.id = participant.student_id AND student.college_id = batch.college_id " +
        "JOIN account student_account ON student_account.id = student.account_id " +
        "JOIN batch_teacher_quota quota ON quota.batch_id = batch.id " +
        "JOIN teacher ON teacher.id = quota.teacher_id AND teacher.college_id = batch.college_id " +
        "JOIN college teacher_college ON teacher_college.id = teacher.college_id " +
        "JOIN account teacher_account ON teacher_account.id = teacher.account_id " +
        "JOIN teacher_public_profile_version profile ON profile.id = teacher.current_public_profile_version_id " +
        "AND profile.teacher_id = teacher.id " +
        "LEFT JOIN managed_file avatar ON avatar.id = profile.avatar_file_id AND avatar.owner_account_id = teacher.account_id " +
        "AND avatar.purpose_code = 'TEACHER_PROFILE_AVATAR' AND avatar.file_status = 'AVAILABLE' " +
        "JOIN teacher_application_scope_slot slot ON slot.batch_id = batch.id AND slot.teacher_id = teacher.id " +
        "JOIN teacher_application_scope_version scope ON scope.id = slot.scope_version_id " +
        "AND scope.batch_id = batch.id AND scope.teacher_id = teacher.id " +
        "WHERE batch.id = ? AND teacher_account.account_status = 'ACTIVE' AND profile.published_at IS NOT NULL " +
        "AND EXISTS (SELECT 1 FROM annual_eligibility_slot eligibility_slot " +
        "JOIN annual_eligibility eligibility ON eligibility.id = eligibility_slot.eligibility_id " +
        "AND eligibility.eligibility_status = 'ELIGIBLE' AND eligibility.valid_to IS NULL " +
        "WHERE eligibility_slot.academic_year_id = batch.academic_year_id " +
        "AND eligibility_slot.college_id = batch.college_id AND eligibility_slot.teacher_id = teacher.id " +
        "AND (eligibility.valid_from IS NULL OR eligibility.valid_from <= UTC_TIMESTAMP(3))) " +
        "AND slot.frozen_at IS NOT NULL AND scope.frozen_at IS NOT NULL ";

    private static final String PRE_FILLING_DEFAULT_SCOPE = "(scope.id IS NULL OR scope.allowed_degree_mask NOT IN (1, 2, 3) " +
        "OR NOT EXISTS (SELECT 1 FROM teacher_allowed_major configured_major " +
        "WHERE configured_major.scope_version_id = scope.id))";
    private static final String PRE_FILLING_DEGREE_MASK = "(CASE WHEN " + PRE_FILLING_DEFAULT_SCOPE +
        " THEN 3 ELSE scope.allowed_degree_mask END)";
    private static final String PRE_FILLING_FROM_AND_WHERE = " FROM selection_batch batch " +
        "JOIN batch_teacher_quota quota ON quota.batch_id = batch.id " +
        "JOIN teacher ON teacher.id = quota.teacher_id AND teacher.college_id = batch.college_id " +
        "JOIN college teacher_college ON teacher_college.id = teacher.college_id " +
        "JOIN account teacher_account ON teacher_account.id = teacher.account_id " +
        "JOIN teacher_public_profile_version profile ON profile.id = teacher.current_public_profile_version_id " +
        "AND profile.teacher_id = teacher.id " +
        "LEFT JOIN managed_file avatar ON avatar.id = profile.avatar_file_id AND avatar.owner_account_id = teacher.account_id " +
        "AND avatar.purpose_code = 'TEACHER_PROFILE_AVATAR' AND avatar.file_status = 'AVAILABLE' " +
        "LEFT JOIN teacher_application_scope_slot slot ON slot.batch_id = batch.id AND slot.teacher_id = teacher.id " +
        "LEFT JOIN teacher_application_scope_version scope ON scope.id = COALESCE(slot.scope_version_id, " +
        "(SELECT scope_candidate.id FROM teacher_application_scope_version scope_candidate " +
        "WHERE scope_candidate.batch_id = batch.id AND scope_candidate.teacher_id = teacher.id " +
        "ORDER BY scope_candidate.version_no DESC LIMIT 1)) " +
        "WHERE batch.id = ? AND batch.batch_status IN ('SCHEDULED', 'ACTIVE', 'PAUSED') " +
        "AND teacher_account.account_status = 'ACTIVE' AND profile.published_at IS NOT NULL ";

    private final JdbcTemplate jdbcTemplate;

    public TeacherDirectoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<DirectoryContext> findContext(Long batchId, Long studentId) {
        List<DirectoryContext> rows = jdbcTemplate.query(
            "SELECT batch.id AS batch_id, batch.batch_status, batch.academic_year_id, student.id AS student_id, " +
                "student.major_id, student.degree_type, student.classification_version, account.account_status " +
                "FROM selection_batch batch JOIN student ON student.id = ? AND student.college_id = batch.college_id " +
                "JOIN account ON account.id = student.account_id WHERE batch.id = ?",
            (rs, rowNum) -> new DirectoryContext(rs.getLong("batch_id"), rs.getString("batch_status"),
                rs.getLong("academic_year_id"), rs.getLong("student_id"), rs.getLong("major_id"),
                rs.getString("degree_type"), rs.getInt("classification_version"), rs.getString("account_status")),
            studentId, batchId);
        return rows.isEmpty() ? Optional.<DirectoryContext>empty() : Optional.of(rows.get(0));
    }

    public boolean isActiveStudent(Long studentId, Long accountId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM student " +
            "JOIN account ON account.id = student.account_id AND account.account_status = 'ACTIVE' " +
            "WHERE student.id = ? AND student.account_id = ?", Integer.class, studentId, accountId);
        return count != null && count.intValue() == 1;
    }

    /** 与具体批次无关的公开目录：仅列账号启用、资料已发布且至少一个学年当前资格有效的导师。 */
    public List<TeacherRow> findQualifiedTeachers(String keyword, String researchDirection, int pageNo, int pageSize) {
        StringBuilder sql = new StringBuilder("SELECT teacher.id AS teacher_id, teacher.employee_no, teacher.full_name, college.name AS college_name, " +
            "profile.research_directions, profile.biography, profile.education_experience, profile.work_experience, " +
            "profile.courses, profile.research_and_achievements, avatar.storage_key AS avatar_storage_key " +
            "FROM teacher JOIN college ON college.id = teacher.college_id " +
            "JOIN account ON account.id = teacher.account_id AND account.account_status = 'ACTIVE' " +
            "JOIN teacher_public_profile_version profile ON profile.id = teacher.current_public_profile_version_id " +
            "AND profile.teacher_id = teacher.id AND profile.published_at IS NOT NULL " +
            "LEFT JOIN managed_file avatar ON avatar.id = profile.avatar_file_id AND avatar.owner_account_id = teacher.account_id " +
            "AND avatar.purpose_code = 'TEACHER_PROFILE_AVATAR' AND avatar.file_status = 'AVAILABLE' " +
            "WHERE EXISTS (SELECT 1 FROM annual_eligibility_slot eligibility_slot " +
            "JOIN annual_eligibility eligibility ON eligibility.id = eligibility_slot.eligibility_id " +
            "AND eligibility.eligibility_status = 'ELIGIBLE' AND eligibility.valid_to IS NULL " +
            "WHERE eligibility_slot.teacher_id = teacher.id " +
            "AND (eligibility.valid_from IS NULL OR eligibility.valid_from <= UTC_TIMESTAMP(3))) ");
        List<Object> args = new ArrayList<Object>();
        appendQualifiedFilters(sql, args, keyword, researchDirection);
        sql.append(" ORDER BY college.name, teacher.full_name, teacher.employee_no, teacher.id LIMIT ? OFFSET ?");
        args.add(Integer.valueOf(pageSize));
        args.add(Long.valueOf(((long) pageNo - 1L) * pageSize));
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new TeacherRow(
            rs.getLong("teacher_id"), rs.getString("employee_no"), rs.getString("full_name"), rs.getString("college_name"),
            rs.getString("research_directions"), rs.getString("biography"), Integer.valueOf(0), null, false,
            rs.getString("education_experience"), rs.getString("work_experience"), rs.getString("courses"),
            rs.getString("research_and_achievements"), rs.getString("avatar_storage_key")), args.toArray());
    }

    public long countQualifiedTeachers(String keyword, String researchDirection) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM teacher " +
            "JOIN account ON account.id = teacher.account_id AND account.account_status = 'ACTIVE' " +
            "JOIN teacher_public_profile_version profile ON profile.id = teacher.current_public_profile_version_id " +
            "AND profile.teacher_id = teacher.id AND profile.published_at IS NOT NULL " +
            "WHERE EXISTS (SELECT 1 FROM annual_eligibility_slot eligibility_slot " +
            "JOIN annual_eligibility eligibility ON eligibility.id = eligibility_slot.eligibility_id " +
            "AND eligibility.eligibility_status = 'ELIGIBLE' AND eligibility.valid_to IS NULL " +
            "WHERE eligibility_slot.teacher_id = teacher.id " +
            "AND (eligibility.valid_from IS NULL OR eligibility.valid_from <= UTC_TIMESTAMP(3))) ");
        List<Object> args = new ArrayList<Object>();
        appendQualifiedFilters(sql, args, keyword, researchDirection);
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0L : count.longValue();
    }

    public Optional<TeacherRow> findQualifiedTeacher(Long teacherId) {
        List<TeacherRow> rows = jdbcTemplate.query("SELECT teacher.id AS teacher_id, teacher.employee_no, teacher.full_name, college.name AS college_name, " +
                "profile.research_directions, profile.biography, profile.education_experience, profile.work_experience, " +
                "profile.courses, profile.research_and_achievements, avatar.storage_key AS avatar_storage_key " +
                "FROM teacher JOIN college ON college.id = teacher.college_id " +
                "JOIN account ON account.id = teacher.account_id AND account.account_status = 'ACTIVE' " +
                "JOIN teacher_public_profile_version profile ON profile.id = teacher.current_public_profile_version_id " +
                "AND profile.teacher_id = teacher.id AND profile.published_at IS NOT NULL " +
                "LEFT JOIN managed_file avatar ON avatar.id = profile.avatar_file_id AND avatar.owner_account_id = teacher.account_id " +
                "AND avatar.purpose_code = 'TEACHER_PROFILE_AVATAR' AND avatar.file_status = 'AVAILABLE' " +
                "WHERE teacher.id = ? AND EXISTS (SELECT 1 FROM annual_eligibility_slot eligibility_slot " +
                "JOIN annual_eligibility eligibility ON eligibility.id = eligibility_slot.eligibility_id " +
                "AND eligibility.eligibility_status = 'ELIGIBLE' AND eligibility.valid_to IS NULL " +
                "WHERE eligibility_slot.teacher_id = teacher.id " +
                "AND (eligibility.valid_from IS NULL OR eligibility.valid_from <= UTC_TIMESTAMP(3)))",
            (rs, rowNum) -> new TeacherRow(rs.getLong("teacher_id"), rs.getString("employee_no"), rs.getString("full_name"),
                rs.getString("college_name"), rs.getString("research_directions"), rs.getString("biography"), Integer.valueOf(0), null,
                false, rs.getString("education_experience"), rs.getString("work_experience"), rs.getString("courses"),
                rs.getString("research_and_achievements"), rs.getString("avatar_storage_key")), teacherId);
        return rows.isEmpty() ? Optional.<TeacherRow>empty() : Optional.of(rows.get(0));
    }

    private void appendQualifiedFilters(StringBuilder sql, List<Object> args, String keyword, String researchDirection) {
        if (keyword != null && !keyword.isEmpty()) {
            sql.append(" AND (teacher.full_name LIKE ? OR teacher.employee_no LIKE ?)");
            String pattern = "%" + keyword + "%";
            args.add(pattern);
            args.add(pattern);
        }
        if (researchDirection != null && !researchDirection.isEmpty()) {
            sql.append(" AND profile.research_directions LIKE ?");
            args.add("%" + researchDirection + "%");
        }
    }

    /** 填报阶段尚未开放时，导师目录以批次名额名单作为公开资料预览来源。 */
    public boolean isBeforeFilling(Long batchId) {
        Integer value = jdbcTemplate.queryForObject(
            "SELECT CASE WHEN batch.batch_status = 'SCHEDULED' OR " +
                "(batch.batch_status IN ('ACTIVE', 'PAUSED') AND stage.stage_code = 'FILLING' " +
                "AND stage.stage_status IN ('NOT_STARTED', 'WAITING_FILLING')) THEN 1 ELSE 0 END " +
                "FROM selection_batch batch LEFT JOIN batch_stage stage ON stage.id = batch.current_stage_id " +
                "WHERE batch.id = ?",
            Integer.class, batchId);
        return value != null && value.intValue() == 1;
    }

    /** 第一轮处理尚未开始时，目录不强制按可报状态收窄。 */
    public boolean isBeforeFirstRound(Long batchId) {
        Integer value = jdbcTemplate.queryForObject(
            "SELECT CASE WHEN batch.batch_status = 'SCHEDULED' OR " +
                "(batch.batch_status IN ('ACTIVE', 'PAUSED') AND (stage.stage_code = 'FILLING' OR " +
                "(stage.stage_code = 'ROUND_1' AND stage.stage_status IN ('NOT_STARTED', 'WAITING_FILLING')))) " +
                "THEN 1 ELSE 0 END FROM selection_batch batch " +
                "LEFT JOIN batch_stage stage ON stage.id = batch.current_stage_id WHERE batch.id = ?",
            Integer.class, batchId);
        return value != null && value.intValue() == 1;
    }

    public List<TeacherRow> findTeachers(Long batchId, Long studentId, String keyword, String researchDirection,
        Long majorId, Integer degreeBit, Boolean canApply, int pageNo, int pageSize) {
        StringBuilder sql = new StringBuilder("SELECT teacher.id AS teacher_id, teacher.employee_no, teacher.full_name, teacher_college.name AS college_name, " +
            "profile.research_directions, profile.biography, avatar.storage_key AS avatar_storage_key, scope.allowed_degree_mask, slot.scope_version_id, ");
        sql.append("CASE WHEN ").append(CAN_APPLY).append(" THEN 1 ELSE 0 END AS can_apply");
        sql.append(FROM_AND_WHERE);
        List<Object> args = new ArrayList<Object>();
        args.add(studentId);
        args.add(batchId);
        appendFilters(sql, args, keyword, researchDirection, majorId, degreeBit, canApply);
        sql.append(" ORDER BY teacher.full_name, teacher.employee_no, teacher.id LIMIT ? OFFSET ?");
        args.add(Integer.valueOf(pageSize));
        args.add(Long.valueOf(((long) pageNo - 1L) * pageSize));
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new TeacherRow(
            rs.getLong("teacher_id"), rs.getString("employee_no"), rs.getString("full_name"),
            rs.getString("college_name"), rs.getString("research_directions"), rs.getString("biography"), rs.getInt("allowed_degree_mask"),
            rs.getLong("scope_version_id"), rs.getBoolean("can_apply"), rs.getString("avatar_storage_key")), args.toArray());
    }

    public List<TeacherRow> findPreFillingTeachers(Long batchId, String keyword, String researchDirection,
        Long majorId, Integer degreeBit, Boolean canApply, int pageNo, int pageSize) {
        StringBuilder sql = new StringBuilder("SELECT teacher.id AS teacher_id, teacher.employee_no, teacher.full_name, teacher_college.name AS college_name, " +
            "profile.research_directions, profile.biography, avatar.storage_key AS avatar_storage_key, ")
            .append(PRE_FILLING_DEGREE_MASK).append(" AS allowed_degree_mask, CASE WHEN ")
            .append(PRE_FILLING_DEFAULT_SCOPE).append(" THEN 0 ELSE scope.id END AS scope_version_id, 0 AS can_apply")
            .append(PRE_FILLING_FROM_AND_WHERE);
        List<Object> args = new ArrayList<Object>();
        args.add(batchId);
        appendPreFillingFilters(sql, args, keyword, researchDirection, majorId, degreeBit, canApply);
        sql.append(" ORDER BY teacher.full_name, teacher.employee_no, teacher.id LIMIT ? OFFSET ?");
        args.add(Integer.valueOf(pageSize));
        args.add(Long.valueOf(((long) pageNo - 1L) * pageSize));
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new TeacherRow(
            rs.getLong("teacher_id"), rs.getString("employee_no"), rs.getString("full_name"),
            rs.getString("college_name"), rs.getString("research_directions"), rs.getString("biography"),
            rs.getInt("allowed_degree_mask"), rs.getLong("scope_version_id"), false,
            rs.getString("avatar_storage_key")), args.toArray());
    }

    public long countPreFillingTeachers(Long batchId, String keyword, String researchDirection,
        Long majorId, Integer degreeBit, Boolean canApply) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*)").append(PRE_FILLING_FROM_AND_WHERE);
        List<Object> args = new ArrayList<Object>();
        args.add(batchId);
        appendPreFillingFilters(sql, args, keyword, researchDirection, majorId, degreeBit, canApply);
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0L : count.longValue();
    }

    public Optional<TeacherRow> findPreFillingTeacher(Long batchId, Long teacherId) {
        StringBuilder sql = new StringBuilder("SELECT teacher.id AS teacher_id, teacher.employee_no, teacher.full_name, teacher_college.name AS college_name, " +
            "profile.research_directions, profile.biography, profile.education_experience, profile.work_experience, " +
            "profile.courses, profile.research_and_achievements, avatar.storage_key AS avatar_storage_key, ")
            .append(PRE_FILLING_DEGREE_MASK).append(" AS allowed_degree_mask, CASE WHEN ")
            .append(PRE_FILLING_DEFAULT_SCOPE).append(" THEN 0 ELSE scope.id END AS scope_version_id, 0 AS can_apply")
            .append(PRE_FILLING_FROM_AND_WHERE).append(" AND teacher.id = ?");
        List<TeacherRow> rows = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new TeacherRow(
            rs.getLong("teacher_id"), rs.getString("employee_no"), rs.getString("full_name"),
            rs.getString("college_name"), rs.getString("research_directions"), rs.getString("biography"),
            rs.getInt("allowed_degree_mask"), rs.getLong("scope_version_id"), false,
            rs.getString("education_experience"), rs.getString("work_experience"), rs.getString("courses"),
            rs.getString("research_and_achievements"), rs.getString("avatar_storage_key")), batchId, teacherId);
        return rows.isEmpty() ? Optional.<TeacherRow>empty() : Optional.of(rows.get(0));
    }

    /** 对未配置/不完整的范围显示当前专业目录预览；正式范围仍会在填报开放时冻结。 */
    public List<AllowedMajorVO> findPreFillingDefaultMajors(Long batchId) {
        return jdbcTemplate.query("SELECT major.id, major.major_code, major.name FROM selection_batch batch " +
                "JOIN major ON major.college_id = batch.college_id AND major.is_active = TRUE " +
                "WHERE batch.id = ? ORDER BY major.major_code, major.id",
            (rs, rowNum) -> new AllowedMajorVO(rs.getLong("id"), rs.getString("major_code"), rs.getString("name")), batchId);
    }

    public long countTeachers(Long batchId, Long studentId, String keyword, String researchDirection,
        Long majorId, Integer degreeBit, Boolean canApply) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*)").append(FROM_AND_WHERE);
        List<Object> args = new ArrayList<Object>();
        args.add(studentId);
        args.add(batchId);
        appendFilters(sql, args, keyword, researchDirection, majorId, degreeBit, canApply);
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0L : count.longValue();
    }

    public Optional<TeacherRow> findTeacher(Long batchId, Long studentId, Long teacherId) {
        StringBuilder sql = new StringBuilder("SELECT teacher.id AS teacher_id, teacher.employee_no, teacher.full_name, teacher_college.name AS college_name, " +
            "profile.research_directions, profile.biography, profile.education_experience, profile.work_experience, " +
            "profile.courses, profile.research_and_achievements, avatar.storage_key AS avatar_storage_key, scope.allowed_degree_mask, slot.scope_version_id, ");
        sql.append("CASE WHEN ").append(CAN_APPLY).append(" THEN 1 ELSE 0 END AS can_apply");
        sql.append(FROM_AND_WHERE).append(" AND teacher.id = ?");
        List<TeacherRow> rows = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new TeacherRow(
            rs.getLong("teacher_id"), rs.getString("employee_no"), rs.getString("full_name"),
            rs.getString("college_name"), rs.getString("research_directions"), rs.getString("biography"), rs.getInt("allowed_degree_mask"),
            rs.getLong("scope_version_id"), rs.getBoolean("can_apply"), rs.getString("education_experience"),
            rs.getString("work_experience"), rs.getString("courses"), rs.getString("research_and_achievements"),
            rs.getString("avatar_storage_key")), studentId, batchId, teacherId);
        return rows.isEmpty() ? Optional.<TeacherRow>empty() : Optional.of(rows.get(0));
    }

    public List<AllowedMajorVO> findAllowedMajors(Long scopeVersionId) {
        return jdbcTemplate.query(
            "SELECT major_id, major_code_snapshot, major_name_snapshot FROM teacher_allowed_major " +
                "WHERE scope_version_id = ? ORDER BY major_code_snapshot, major_id",
            (rs, rowNum) -> new AllowedMajorVO(rs.getLong("major_id"), rs.getString("major_code_snapshot"),
                rs.getString("major_name_snapshot")), scopeVersionId);
    }

    public java.util.Map<Long, List<AllowedMajorVO>> findAllowedMajors(List<Long> scopeVersionIds) {
        if (scopeVersionIds == null || scopeVersionIds.isEmpty()) return Collections.emptyMap();
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < scopeVersionIds.size(); i++) {
            if (i > 0) placeholders.append(',');
            placeholders.append('?');
        }
        Object[] args = scopeVersionIds.toArray();
        List<ScopeMajorRow> rows = jdbcTemplate.query(
            "SELECT scope_version_id, major_id, major_code_snapshot, major_name_snapshot FROM teacher_allowed_major " +
                "WHERE scope_version_id IN (" + placeholders + ") ORDER BY scope_version_id, major_code_snapshot, major_id",
            (rs, rowNum) -> new ScopeMajorRow(rs.getLong("scope_version_id"),
                new AllowedMajorVO(rs.getLong("major_id"), rs.getString("major_code_snapshot"),
                    rs.getString("major_name_snapshot"))), args);
        java.util.Map<Long, List<AllowedMajorVO>> result = new java.util.LinkedHashMap<Long, List<AllowedMajorVO>>();
        for (ScopeMajorRow row : rows) {
            List<AllowedMajorVO> majors = result.get(row.scopeVersionId);
            if (majors == null) {
                majors = new ArrayList<AllowedMajorVO>();
                result.put(row.scopeVersionId, majors);
            }
            majors.add(row.major);
        }
        return result;
    }

    private void appendFilters(StringBuilder sql, List<Object> args, String keyword, String researchDirection,
                               Long majorId, Integer degreeBit, Boolean canApply) {
        if (keyword != null && !keyword.isEmpty()) {
            sql.append(" AND teacher.full_name LIKE ?");
            String pattern = "%" + keyword + "%";
            args.add(pattern);
        }
        if (researchDirection != null && !researchDirection.isEmpty()) {
            sql.append(" AND profile.research_directions LIKE ?");
            args.add("%" + researchDirection + "%");
        }
        if (majorId != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM teacher_allowed_major filter_major WHERE filter_major.scope_version_id = slot.scope_version_id " +
                "AND filter_major.major_id = ?)");
            args.add(majorId);
        }
        if (degreeBit != null) {
            sql.append(" AND (scope.allowed_degree_mask & ?) <> 0");
            args.add(degreeBit);
        }
        if (canApply != null) {
            sql.append(" AND ").append(CAN_APPLY).append(canApply.booleanValue() ? " " : " = 0 ");
        }
    }

    private void appendPreFillingFilters(StringBuilder sql, List<Object> args, String keyword, String researchDirection,
        Long majorId, Integer degreeBit, Boolean canApply) {
        if (keyword != null && !keyword.isEmpty()) {
            sql.append(" AND teacher.full_name LIKE ?");
            args.add("%" + keyword + "%");
        }
        if (researchDirection != null && !researchDirection.isEmpty()) {
            sql.append(" AND profile.research_directions LIKE ?");
            args.add("%" + researchDirection + "%");
        }
        if (majorId != null) {
            sql.append(" AND (").append(PRE_FILLING_DEFAULT_SCOPE)
                .append(" OR EXISTS (SELECT 1 FROM teacher_allowed_major filter_major " +
                    "WHERE filter_major.scope_version_id = scope.id AND filter_major.major_id = ?))");
            args.add(majorId);
        }
        if (degreeBit != null) {
            sql.append(" AND (").append(PRE_FILLING_DEGREE_MASK).append(" & ?) <> 0");
            args.add(degreeBit);
        }
        if (canApply != null) {
            // Before filling opens, directory rows are previews and are never actionable.
            sql.append(canApply.booleanValue() ? " AND 1 = 0 " : " AND 1 = 1 ");
        }
    }

    public static class DirectoryContext {
        private final Long batchId;
        private final String batchStatus;
        private final Long academicYearId;
        private final Long studentId;
        private final Long majorId;
        private final String degreeType;
        private final Integer classificationVersion;
        private final String accountStatus;
        public DirectoryContext(Long batchId, String batchStatus, Long academicYearId, Long studentId, Long majorId,
            String degreeType, Integer classificationVersion, String accountStatus) {
            this.batchId = batchId; this.batchStatus = batchStatus; this.academicYearId = academicYearId;
            this.studentId = studentId; this.majorId = majorId; this.degreeType = degreeType;
            this.classificationVersion = classificationVersion; this.accountStatus = accountStatus;
        }
        public Long getBatchId() { return batchId; }
        public String getBatchStatus() { return batchStatus; }
        public Long getAcademicYearId() { return academicYearId; }
        public Long getStudentId() { return studentId; }
        public Long getMajorId() { return majorId; }
        public String getDegreeType() { return degreeType; }
        public Integer getClassificationVersion() { return classificationVersion; }
        public String getAccountStatus() { return accountStatus; }
    }

    public static class TeacherRow {
        private final Long teacherId;
        private final String employeeNo;
        private final String fullName;
        private final String collegeName;
        private final String researchDirections;
        private final String biography;
        private final Integer degreeMask;
        private final Long scopeVersionId;
        private final boolean canApply;
        private final String educationExperience;
        private final String workExperience;
        private final String courses;
        private final String researchAndAchievements;
        private final String avatarStorageKey;
        public TeacherRow(Long teacherId, String employeeNo, String fullName, String collegeName, String researchDirections,
            String biography, Integer degreeMask, Long scopeVersionId, boolean canApply) {
            this(teacherId, employeeNo, fullName, collegeName, researchDirections, biography, degreeMask, scopeVersionId, canApply, null);
        }
        public TeacherRow(Long teacherId, String employeeNo, String fullName, String collegeName, String researchDirections,
            String biography, Integer degreeMask, Long scopeVersionId, boolean canApply, String avatarStorageKey) {
            this.teacherId = teacherId; this.employeeNo = employeeNo; this.fullName = fullName;
            this.collegeName = collegeName;
            this.researchDirections = researchDirections; this.biography = biography; this.degreeMask = degreeMask;
            this.scopeVersionId = scopeVersionId; this.canApply = canApply;
            this.educationExperience = null; this.workExperience = null; this.courses = null; this.researchAndAchievements = null;
            this.avatarStorageKey = avatarStorageKey;
        }
        public TeacherRow(Long teacherId, String employeeNo, String fullName, String collegeName, String researchDirections,
            String biography, Integer degreeMask, Long scopeVersionId, boolean canApply, String educationExperience,
            String workExperience, String courses, String researchAndAchievements, String avatarStorageKey) {
            this.teacherId = teacherId; this.employeeNo = employeeNo; this.fullName = fullName;
            this.collegeName = collegeName; this.researchDirections = researchDirections; this.biography = biography;
            this.degreeMask = degreeMask; this.scopeVersionId = scopeVersionId; this.canApply = canApply;
            this.educationExperience = educationExperience; this.workExperience = workExperience;
            this.courses = courses; this.researchAndAchievements = researchAndAchievements;
            this.avatarStorageKey = avatarStorageKey;
        }
        public Long getTeacherId() { return teacherId; }
        public String getEmployeeNo() { return employeeNo; }
        public String getFullName() { return fullName; }
        public String getCollegeName() { return collegeName; }
        public String getResearchDirections() { return researchDirections; }
        public String getBiography() { return biography; }
        public Integer getDegreeMask() { return degreeMask; }
        public Long getScopeVersionId() { return scopeVersionId; }
        public boolean isCanApply() { return canApply; }
        public String getEducationExperience() { return educationExperience; }
        public String getWorkExperience() { return workExperience; }
        public String getCourses() { return courses; }
        public String getResearchAndAchievements() { return researchAndAchievements; }
        public String getAvatarStorageKey() { return avatarStorageKey; }
    }

    private static class ScopeMajorRow {
        private final Long scopeVersionId;
        private final AllowedMajorVO major;
        private ScopeMajorRow(Long scopeVersionId, AllowedMajorVO major) {
            this.scopeVersionId = scopeVersionId; this.major = major;
        }
    }
}
