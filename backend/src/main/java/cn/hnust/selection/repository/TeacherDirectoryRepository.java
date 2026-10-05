package cn.hnust.selection.repository;

import cn.hnust.selection.vo.AllowedMajorVO;
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
        "JOIN supplement_teacher permission ON permission.supplement_window_id = supplement.id " +
        "AND permission.batch_teacher_quota_id = quota.id " +
        "AND permission.permission_version = (SELECT MAX(permission_latest.permission_version) " +
        "FROM supplement_teacher permission_latest WHERE permission_latest.supplement_window_id = permission.supplement_window_id " +
        "AND permission_latest.batch_teacher_quota_id = permission.batch_teacher_quota_id) " +
        "WHERE supplement.batch_id = batch.id AND supplement.window_status = 'OPEN' " +
        "AND supplement.effective_start_at <= UTC_TIMESTAMP(3) AND UTC_TIMESTAMP(3) < supplement.effective_end_at " +
        "AND permission.revoked_at IS NULL AND permission.allowed_from <= UTC_TIMESTAMP(3)) " +
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
        "JOIN account teacher_account ON teacher_account.id = teacher.account_id " +
        "JOIN teacher_public_profile_version profile ON profile.id = teacher.current_public_profile_version_id " +
        "AND profile.teacher_id = teacher.id " +
        "JOIN teacher_application_scope_slot slot ON slot.batch_id = batch.id AND slot.teacher_id = teacher.id " +
        "JOIN teacher_application_scope_version scope ON scope.id = slot.scope_version_id " +
        "AND scope.batch_id = batch.id AND scope.teacher_id = teacher.id " +
        "WHERE batch.id = ? AND teacher_account.account_status = 'ACTIVE' AND profile.published_at IS NOT NULL " +
        "AND slot.frozen_at IS NOT NULL AND scope.frozen_at IS NOT NULL ";

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

    public List<TeacherRow> findTeachers(Long batchId, Long studentId, String keyword, String researchDirection,
        Long majorId, Integer degreeBit, Boolean canApply, int pageNo, int pageSize) {
        StringBuilder sql = new StringBuilder("SELECT teacher.id AS teacher_id, teacher.employee_no, teacher.full_name, " +
            "profile.research_directions, profile.biography, scope.allowed_degree_mask, slot.scope_version_id, ");
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
            rs.getString("research_directions"), rs.getString("biography"), rs.getInt("allowed_degree_mask"),
            rs.getLong("scope_version_id"), rs.getBoolean("can_apply")), args.toArray());
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
        StringBuilder sql = new StringBuilder("SELECT teacher.id AS teacher_id, teacher.employee_no, teacher.full_name, " +
            "profile.research_directions, profile.biography, scope.allowed_degree_mask, slot.scope_version_id, ");
        sql.append("CASE WHEN ").append(CAN_APPLY).append(" THEN 1 ELSE 0 END AS can_apply");
        sql.append(FROM_AND_WHERE).append(" AND teacher.id = ?");
        List<TeacherRow> rows = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new TeacherRow(
            rs.getLong("teacher_id"), rs.getString("employee_no"), rs.getString("full_name"),
            rs.getString("research_directions"), rs.getString("biography"), rs.getInt("allowed_degree_mask"),
            rs.getLong("scope_version_id"), rs.getBoolean("can_apply")), studentId, batchId, teacherId);
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
            sql.append(" AND (teacher.full_name LIKE ? OR teacher.employee_no LIKE ?)");
            String pattern = "%" + keyword + "%";
            args.add(pattern);
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
        private final String researchDirections;
        private final String biography;
        private final Integer degreeMask;
        private final Long scopeVersionId;
        private final boolean canApply;
        public TeacherRow(Long teacherId, String employeeNo, String fullName, String researchDirections,
            String biography, Integer degreeMask, Long scopeVersionId, boolean canApply) {
            this.teacherId = teacherId; this.employeeNo = employeeNo; this.fullName = fullName;
            this.researchDirections = researchDirections; this.biography = biography; this.degreeMask = degreeMask;
            this.scopeVersionId = scopeVersionId; this.canApply = canApply;
        }
        public Long getTeacherId() { return teacherId; }
        public String getEmployeeNo() { return employeeNo; }
        public String getFullName() { return fullName; }
        public String getResearchDirections() { return researchDirections; }
        public String getBiography() { return biography; }
        public Integer getDegreeMask() { return degreeMask; }
        public Long getScopeVersionId() { return scopeVersionId; }
        public boolean isCanApply() { return canApply; }
    }

    private static class ScopeMajorRow {
        private final Long scopeVersionId;
        private final AllowedMajorVO major;
        private ScopeMajorRow(Long scopeVersionId, AllowedMajorVO major) {
            this.scopeVersionId = scopeVersionId; this.major = major;
        }
    }
}
