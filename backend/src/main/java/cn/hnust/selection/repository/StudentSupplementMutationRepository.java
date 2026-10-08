package cn.hnust.selection.repository;

import cn.hnust.selection.vo.SupplementApplicationVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class StudentSupplementMutationRepository {
    private final JdbcTemplate jdbcTemplate;

    public StudentSupplementMutationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<SupplementWindow> lockWindow(Long batchId) {
        List<SupplementWindow> rows = jdbcTemplate.query(
            "SELECT batch.batch_status, window.id AS window_id, window.window_status, " +
                "window.effective_start_at, window.effective_end_at " +
                "FROM selection_batch batch JOIN supplement_window window ON window.batch_id = batch.id " +
                "WHERE batch.id = ? FOR UPDATE",
            (rs, rowNum) -> new SupplementWindow(rs.getString("batch_status"), rs.getLong("window_id"),
                rs.getString("window_status"), rs.getTimestamp("effective_start_at"),
                rs.getTimestamp("effective_end_at")), batchId);
        return rows.isEmpty() ? Optional.<SupplementWindow>empty() : Optional.of(rows.get(0));
    }

    public Timestamp databaseUtcNow() {
        return jdbcTemplate.queryForObject("SELECT UTC_TIMESTAMP(3)", Timestamp.class);
    }

    public boolean lockStudent(Long studentId, Long accountId) {
        List<Long> rows = jdbcTemplate.query(
            "SELECT id FROM student WHERE id = ? AND account_id = ? FOR UPDATE",
            (rs, rowNum) -> Long.valueOf(rs.getLong("id")), studentId, accountId);
        return !rows.isEmpty();
    }

    public Optional<Participant> lockParticipant(Long batchId, Long studentId, Long accountId) {
        String sql = "SELECT participant.id AS batch_student_id, participant.match_status, participant.eligibility_snapshot, " +
            "participant.account_enabled_snapshot, batch.academic_year_id, student.student_no, student.full_name, " +
            "student.major_id, major.name AS major_name, student.degree_type, student.classification_version, " +
            "account.account_status, profile.biography, profile.resume_file_id " +
            "FROM batch_student participant JOIN selection_batch batch ON batch.id = participant.batch_id " +
            "JOIN student ON student.id = participant.student_id AND student.college_id = batch.college_id " +
            "JOIN account ON account.id = student.account_id " +
            "JOIN major ON major.id = student.major_id AND major.college_id = student.college_id " +
            "LEFT JOIN student_profile_version profile ON profile.student_id = student.id AND profile.version_no = " +
            "(SELECT MAX(profile_latest.version_no) FROM student_profile_version profile_latest WHERE profile_latest.student_id = student.id) " +
            "WHERE participant.batch_id = ? AND participant.student_id = ? AND student.account_id = ? FOR UPDATE";
        List<Participant> rows = jdbcTemplate.query(sql, (rs, rowNum) -> {
            long rawResumeId = rs.getLong("resume_file_id");
            Long resumeId = rs.wasNull() ? null : Long.valueOf(rawResumeId);
            return new Participant(rs.getLong("batch_student_id"), rs.getString("match_status"),
                rs.getString("eligibility_snapshot"), rs.getBoolean("account_enabled_snapshot"),
                rs.getLong("academic_year_id"), rs.getString("student_no"), rs.getString("full_name"),
                rs.getLong("major_id"), rs.getString("major_name"), rs.getString("degree_type"),
                Integer.valueOf(rs.getInt("classification_version")), rs.getString("account_status"),
                rs.getString("biography"), resumeId);
        }, batchId, studentId, accountId);
        return rows.isEmpty() ? Optional.<Participant>empty() : Optional.of(rows.get(0));
    }

    public boolean hasPendingApplication(Long studentId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM student_pending_supplement_slot WHERE student_id = ?", Integer.class, studentId);
        return count != null && count.intValue() > 0;
    }

    public boolean hasActiveYearRelation(Long studentId, Long academicYearId) {
        List<Long> rows = jdbcTemplate.query(
            "SELECT relation_id FROM student_year_match_slot WHERE student_id = ? AND academic_year_id = ? FOR UPDATE",
            (rs, rowNum) -> Long.valueOf(rs.getLong("relation_id")), studentId, academicYearId);
        return !rows.isEmpty();
    }

    public Optional<EligibleTeacher> findEligibleTeacher(Long batchId, Long windowId, Long teacherId,
                                                         Long majorId, Timestamp now) {
        String sql = "SELECT quota.id AS quota_id, quota.teacher_id, quota.occupied_count, quota.quota_limit, " +
            "scope.allowed_degree_mask, slot.scope_version_id, teacher.full_name AS teacher_name, teacher.employee_no, " +
            "EXISTS (SELECT 1 FROM teacher_allowed_major allowed WHERE allowed.scope_version_id = slot.scope_version_id AND allowed.major_id = ?) AS major_allowed " +
            "FROM batch_teacher_quota quota " +
            "JOIN teacher_application_scope_slot slot ON slot.batch_id = quota.batch_id AND slot.teacher_id = quota.teacher_id " +
            "JOIN teacher_application_scope_version scope ON scope.id = slot.scope_version_id " +
            "AND scope.batch_id = quota.batch_id AND scope.teacher_id = quota.teacher_id " +
            "JOIN selection_batch batch ON batch.id = quota.batch_id " +
            "JOIN teacher ON teacher.id = quota.teacher_id AND teacher.college_id = batch.college_id " +
            "JOIN account teacher_account ON teacher_account.id = teacher.account_id " +
            "JOIN teacher_public_profile_version profile ON profile.id = teacher.current_public_profile_version_id " +
            "AND profile.teacher_id = teacher.id " +
            "JOIN supplement_window window ON window.id = ? AND window.batch_id = batch.id " +
            "JOIN annual_eligibility_slot eligibility_slot ON eligibility_slot.academic_year_id = batch.academic_year_id " +
            "AND eligibility_slot.college_id = batch.college_id AND eligibility_slot.teacher_id = teacher.id " +
            "JOIN annual_eligibility eligibility ON eligibility.id = eligibility_slot.eligibility_id " +
            "AND eligibility.eligibility_status = 'ELIGIBLE' AND eligibility.valid_to IS NULL " +
            "AND (eligibility.valid_from IS NULL OR eligibility.valid_from <= ?) " +
            "WHERE quota.batch_id = ? AND quota.teacher_id = ? AND slot.frozen_at IS NOT NULL " +
            "AND scope.frozen_at IS NOT NULL AND teacher_account.account_status = 'ACTIVE' " +
            "AND profile.published_at IS NOT NULL FOR UPDATE";
        List<EligibleTeacher> rows = jdbcTemplate.query(sql, (rs, rowNum) -> new EligibleTeacher(
            rs.getLong("quota_id"), rs.getLong("teacher_id"), rs.getString("teacher_name"),
            rs.getString("employee_no"), rs.getBoolean("major_allowed"), rs.getInt("allowed_degree_mask"),
            rs.getLong("scope_version_id"), rs.getInt("occupied_count"), rs.getInt("quota_limit")),
            majorId, windowId, now, batchId, teacherId);
        return rows.isEmpty() ? Optional.<EligibleTeacher>empty() : Optional.of(rows.get(0));
    }

    public Optional<Operation> findOperation(Long accountId, String requestId) {
        List<Operation> rows = jdbcTemplate.query(
            "SELECT operation.request_fingerprint, operation.result_code, audit.object_id " +
                "FROM business_operation operation LEFT JOIN audit_event audit " +
                "ON audit.business_operation_id = operation.id AND audit.action_code = operation.action_code " +
                "WHERE operation.actor_account_id = ? AND operation.action_code = 'STUDENT_SUPPLEMENT_APPLY' " +
                "AND operation.request_id = ? ORDER BY audit.id LIMIT 1",
            (rs, rowNum) -> {
                long rawObjectId = rs.getLong("object_id");
                Long objectId = rs.wasNull() ? null : Long.valueOf(rawObjectId);
                return new Operation(rs.getString("request_fingerprint"), rs.getString("result_code"), objectId);
            }, accountId, requestId);
        return rows.isEmpty() ? Optional.<Operation>empty() : Optional.of(rows.get(0));
    }

    public Optional<Operation> findOperationCurrent(Long accountId, String requestId) {
        List<Operation> rows = jdbcTemplate.query(
            "SELECT operation.request_fingerprint, operation.result_code, audit.object_id " +
                "FROM business_operation operation LEFT JOIN audit_event audit " +
                "ON audit.business_operation_id = operation.id AND audit.action_code = operation.action_code " +
                "WHERE operation.actor_account_id = ? AND operation.action_code = 'STUDENT_SUPPLEMENT_APPLY' " +
                "AND operation.request_id = ? ORDER BY audit.id LIMIT 1 FOR UPDATE",
            (rs, rowNum) -> {
                long rawObjectId = rs.getLong("object_id");
                Long objectId = rs.wasNull() ? null : Long.valueOf(rawObjectId);
                return new Operation(rs.getString("request_fingerprint"), rs.getString("result_code"), objectId);
            }, accountId, requestId);
        return rows.isEmpty() ? Optional.<Operation>empty() : Optional.of(rows.get(0));
    }

    public Long insertOperation(Long accountId, Long batchId, String requestId, String fingerprint,
                                int classificationVersion, Long scopeVersionId) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO business_operation (actor_account_id, actor_kind, action_code, batch_id, request_id, " +
                    "request_fingerprint, result_code, started_at, classification_version, scope_version_id) " +
                    "VALUES (?, 'STUDENT', 'STUDENT_SUPPLEMENT_APPLY', ?, ?, ?, 'IN_PROGRESS', UTC_TIMESTAMP(3), ?, ?)",
                Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, accountId);
            statement.setLong(2, batchId);
            statement.setString(3, requestId);
            statement.setString(4, fingerprint);
            statement.setInt(5, classificationVersion);
            statement.setLong(6, scopeVersionId);
            return statement;
        }, holder);
        Number id = holder.getKey();
        if (id == null) throw new IllegalStateException("Database did not return an operation ID");
        return Long.valueOf(id.longValue());
    }

    public Long insertApplication(Long batchStudentId, Long windowId, EligibleTeacher teacher, String studentNo) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO supplement_application (batch_student_id, supplement_window_id, teacher_id, " +
                    "batch_teacher_quota_id, application_status, close_reason, submitted_at, decided_by, decided_at, " +
                    "sort_submitted_at, sort_student_no, active_snapshot_id) " +
                    "VALUES (?, ?, ?, ?, 'IN_REVIEW', NULL, UTC_TIMESTAMP(3), NULL, NULL, UTC_TIMESTAMP(3), ?, NULL)",
                Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, batchStudentId);
            statement.setLong(2, windowId);
            statement.setLong(3, teacher.getTeacherId());
            statement.setLong(4, teacher.getQuotaId());
            statement.setString(5, studentNo);
            return statement;
        }, holder);
        Number id = holder.getKey();
        if (id == null) throw new IllegalStateException("Database did not return a supplement application ID");
        return Long.valueOf(id.longValue());
    }

    public Long insertSnapshot(Long applicationId, Participant participant) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO application_profile_snapshot (round_application_id, supplement_application_id, execution_cycle, " +
                    "captured_at, capture_reason, full_name, student_no, major_id, major_name_snapshot, degree_type, biography, resume_file_id) " +
                    "VALUES (NULL, ?, 1, UTC_TIMESTAMP(3), 'SUBMISSION', ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, applicationId);
            statement.setString(2, participant.getFullName());
            statement.setString(3, participant.getStudentNo());
            statement.setLong(4, participant.getMajorId());
            statement.setString(5, participant.getMajorName());
            statement.setString(6, participant.getDegreeType());
            statement.setString(7, participant.getBiography());
            if (participant.getResumeFileId() == null) statement.setNull(8, java.sql.Types.BIGINT);
            else statement.setLong(8, participant.getResumeFileId());
            return statement;
        }, holder);
        Number id = holder.getKey();
        if (id == null) throw new IllegalStateException("Database did not return an application snapshot ID");
        return Long.valueOf(id.longValue());
    }

    public void setActiveSnapshot(Long applicationId, Long snapshotId) {
        int changed = jdbcTemplate.update(
            "UPDATE supplement_application SET active_snapshot_id = ? WHERE id = ?", snapshotId, applicationId);
        if (changed != 1) throw new IllegalStateException("Supplement application snapshot was not attached");
    }

    public void claimPendingSlot(Long studentId, Long applicationId) {
        jdbcTemplate.update(
            "INSERT INTO student_pending_supplement_slot (student_id, supplement_application_id, claimed_at) " +
                "VALUES (?, ?, UTC_TIMESTAMP(3))", studentId, applicationId);
    }

    public void insertApplicationEvent(Long applicationId, Long accountId, Long operationId) {
        jdbcTemplate.update(
            "INSERT INTO application_event (object_type, object_id, action_code, old_status, new_status, actor_kind, " +
                "actor_account_id, occurred_at, business_operation_id) " +
                "VALUES ('SUPPLEMENT_APPLICATION', ?, 'SUPPLEMENT_SUBMITTED', NULL, 'IN_REVIEW', 'STUDENT', ?, UTC_TIMESTAMP(3), ?)",
            applicationId, accountId, operationId);
    }

    public void insertAudit(Long applicationId, Long operationId, Long accountId, Long batchId,
                            Long batchStudentId, Long teacherId, int classificationVersion) {
        jdbcTemplate.update(
            "INSERT INTO audit_event (actor_account_id, actor_kind, actor_role, scope_basis, object_type, object_id, " +
                "action_code, after_values_text, occurred_at, business_operation_id) " +
                "VALUES (?, 'STUDENT', 'STUDENT', ?, 'SUPPLEMENT_APPLICATION', ?, 'STUDENT_SUPPLEMENT_APPLY', ?, UTC_TIMESTAMP(3), ?)",
            accountId, "batchId=" + batchId + ";batchStudentId=" + batchStudentId + ";classificationVersion=" + classificationVersion,
            applicationId, "{\"applicationId\":" + applicationId + ",\"teacherId\":" + teacherId + ",\"status\":\"IN_REVIEW\"}",
            operationId);
    }

    public void completeOperation(Long operationId) {
        int changed = jdbcTemplate.update(
            "UPDATE business_operation SET result_code = 'OK', completed_at = UTC_TIMESTAMP(3) WHERE id = ?", operationId);
        if (changed != 1) throw new IllegalStateException("Business operation row was not completed");
    }

    public Optional<SupplementApplicationVO> findCommand(Long applicationId) {
        List<SupplementApplicationVO> rows = jdbcTemplate.query(
            "SELECT application.id, application.teacher_id, teacher.full_name AS teacher_name, " +
                "DATE_FORMAT(application.submitted_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS submitted_at " +
                "FROM supplement_application application JOIN teacher ON teacher.id = application.teacher_id " +
                "WHERE application.id = ?",
            (rs, rowNum) -> {
                SupplementApplicationVO result = new SupplementApplicationVO();
                result.setApplicationId(rs.getLong("id"));
                result.setTeacherId(rs.getLong("teacher_id"));
                result.setTeacherName(rs.getString("teacher_name"));
                result.setSubmittedAt(rs.getString("submitted_at"));
                result.setStatus("IN_REVIEW");
                result.setProcessedAt(null);
                return result;
            }, applicationId);
        return rows.isEmpty() ? Optional.<SupplementApplicationVO>empty() : Optional.of(rows.get(0));
    }

    public static class SupplementWindow {
        private final String batchStatus;
        private final Long id;
        private final String status;
        private final Timestamp startAt;
        private final Timestamp endAt;

        public SupplementWindow(String batchStatus, Long id, String status, Timestamp startAt, Timestamp endAt) {
            this.batchStatus = batchStatus;
            this.id = id;
            this.status = status;
            this.startAt = startAt;
            this.endAt = endAt;
        }
        public String getBatchStatus() { return batchStatus; }
        public Long getId() { return id; }
        public String getStatus() { return status; }
        public Timestamp getStartAt() { return startAt; }
        public Timestamp getEndAt() { return endAt; }
    }

    public static class Participant {
        private final Long batchStudentId;
        private final String matchStatus;
        private final String eligibilitySnapshot;
        private final boolean accountEnabled;
        private final Long academicYearId;
        private final String studentNo;
        private final String fullName;
        private final Long majorId;
        private final String majorName;
        private final String degreeType;
        private final Integer classificationVersion;
        private final String accountStatus;
        private final String biography;
        private final Long resumeFileId;

        public Participant(Long batchStudentId, String matchStatus, String eligibilitySnapshot, boolean accountEnabled,
            Long academicYearId, String studentNo, String fullName, Long majorId, String majorName,
            String degreeType, Integer classificationVersion, String accountStatus, String biography, Long resumeFileId) {
            this.batchStudentId = batchStudentId;
            this.matchStatus = matchStatus;
            this.eligibilitySnapshot = eligibilitySnapshot;
            this.accountEnabled = accountEnabled;
            this.academicYearId = academicYearId;
            this.studentNo = studentNo;
            this.fullName = fullName;
            this.majorId = majorId;
            this.majorName = majorName;
            this.degreeType = degreeType;
            this.classificationVersion = classificationVersion;
            this.accountStatus = accountStatus;
            this.biography = biography;
            this.resumeFileId = resumeFileId;
        }
        public Long getBatchStudentId() { return batchStudentId; }
        public String getMatchStatus() { return matchStatus; }
        public String getEligibilitySnapshot() { return eligibilitySnapshot; }
        public boolean isAccountEnabled() { return accountEnabled; }
        public Long getAcademicYearId() { return academicYearId; }
        public String getStudentNo() { return studentNo; }
        public String getFullName() { return fullName; }
        public Long getMajorId() { return majorId; }
        public String getMajorName() { return majorName; }
        public String getDegreeType() { return degreeType; }
        public Integer getClassificationVersion() { return classificationVersion; }
        public String getAccountStatus() { return accountStatus; }
        public String getBiography() { return biography; }
        public Long getResumeFileId() { return resumeFileId; }
    }

    public static class EligibleTeacher {
        private final Long quotaId;
        private final Long teacherId;
        private final String teacherName;
        private final String employeeNo;
        private final boolean majorAllowed;
        private final int degreeMask;
        private final Long scopeVersionId;
        private final int occupied;
        private final int quotaLimit;

        public EligibleTeacher(Long quotaId, Long teacherId, String teacherName, String employeeNo,
            boolean majorAllowed, int degreeMask, Long scopeVersionId, int occupied, int quotaLimit) {
            this.quotaId = quotaId;
            this.teacherId = teacherId;
            this.teacherName = teacherName;
            this.employeeNo = employeeNo;
            this.majorAllowed = majorAllowed;
            this.degreeMask = degreeMask;
            this.scopeVersionId = scopeVersionId;
            this.occupied = occupied;
            this.quotaLimit = quotaLimit;
        }
        public Long getQuotaId() { return quotaId; }
        public Long getTeacherId() { return teacherId; }
        public String getTeacherName() { return teacherName; }
        public String getEmployeeNo() { return employeeNo; }
        public boolean isMajorAllowed() { return majorAllowed; }
        public int getDegreeMask() { return degreeMask; }
        public Long getScopeVersionId() { return scopeVersionId; }
        public int getOccupied() { return occupied; }
        public int getQuotaLimit() { return quotaLimit; }
    }

    public static class Operation {
        private final String fingerprint;
        private final String resultCode;
        private final Long objectId;

        public Operation(String fingerprint, String resultCode, Long objectId) {
            this.fingerprint = fingerprint;
            this.resultCode = resultCode;
            this.objectId = objectId;
        }
        public String getFingerprint() { return fingerprint; }
        public String getResultCode() { return resultCode; }
        public Long getObjectId() { return objectId; }
    }
}
