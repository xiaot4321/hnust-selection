package cn.hnust.selection.repository;

import cn.hnust.selection.request.SubmitStudentPreferencesRequest.PreferenceItemRequest;
import cn.hnust.selection.vo.StudentPreferenceCommandVO;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class StudentPreferenceMutationRepository {
    private final JdbcTemplate jdbcTemplate;
    public StudentPreferenceMutationRepository(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    public Optional<StageWindow> lockFillingStage(Long batchId) {
        List<StageWindow> rows = jdbcTemplate.query(
            "SELECT batch.batch_status, stage.stage_status, COALESCE(stage.effective_start_at, stage.planned_start_at) AS start_at, " +
                "COALESCE(stage.effective_end_at, stage.planned_end_at) AS end_at " +
                "FROM selection_batch batch JOIN batch_stage stage ON stage.batch_id = batch.id " +
                "WHERE batch.id = ? AND stage.stage_code = 'FILLING' FOR UPDATE",
            (rs, rowNum) -> new StageWindow(rs.getString("batch_status"), rs.getString("stage_status"),
                rs.getTimestamp("start_at"), rs.getTimestamp("end_at")), batchId);
        return rows.isEmpty() ? Optional.<StageWindow>empty() : Optional.of(rows.get(0));
    }

    public Timestamp databaseUtcNow() { return jdbcTemplate.queryForObject("SELECT UTC_TIMESTAMP(3)", Timestamp.class); }

    public Optional<BatchStudentState> lockBatchStudent(Long batchId, Long studentId) {
        List<BatchStudentState> rows = jdbcTemplate.query(
            "SELECT participant.id, participant.eligibility_snapshot, participant.account_enabled_snapshot, participant.confirmed_classification_version, " +
                "participant.preference_status, participant.match_status, participant.current_submission_id, participant.final_submission_id " +
                "FROM batch_student participant JOIN selection_batch batch ON batch.id = participant.batch_id " +
                "JOIN student ON student.id = participant.student_id AND student.college_id = batch.college_id " +
                "WHERE participant.batch_id = ? AND participant.student_id = ? FOR UPDATE",
            (rs, rowNum) -> {
                long rawCurrent = rs.getLong("current_submission_id"); Long current = rs.wasNull() ? null : Long.valueOf(rawCurrent);
                long rawFinal = rs.getLong("final_submission_id"); Long finalId = rs.wasNull() ? null : Long.valueOf(rawFinal);
                int rawConfirmed = rs.getInt("confirmed_classification_version"); Integer confirmed = rs.wasNull() ? null : Integer.valueOf(rawConfirmed);
                return new BatchStudentState(rs.getLong("id"), rs.getString("eligibility_snapshot"),
                    rs.getBoolean("account_enabled_snapshot"), confirmed, rs.getString("preference_status"),
                    rs.getString("match_status"), current, finalId);
            }, batchId, studentId);
        return rows.isEmpty() ? Optional.<BatchStudentState>empty() : Optional.of(rows.get(0));
    }
    public Optional<StudentClassification> lockStudentClassification(Long studentId, Long accountId) {
        List<StudentClassification> rows = jdbcTemplate.query(
            "SELECT student.classification_version, student.major_id, student.degree_type " +
                "FROM student JOIN account ON account.id = student.account_id " +
                "WHERE student.id = ? AND student.account_id = ? AND account.account_status = 'ACTIVE' FOR UPDATE",
            (rs, rowNum) -> new StudentClassification(rs.getInt("classification_version"),
                rs.getLong("major_id"), rs.getString("degree_type")), studentId, accountId);
        return rows.isEmpty() ? Optional.<StudentClassification>empty() : Optional.of(rows.get(0));
    }

    public Optional<Operation> findOperation(Long accountId, String actionCode, String requestId) {
        List<Operation> rows = jdbcTemplate.query(
            "SELECT operation.request_fingerprint, operation.result_code, audit.object_id " +
                "FROM business_operation operation LEFT JOIN audit_event audit " +
                "ON audit.business_operation_id = operation.id AND audit.action_code = operation.action_code " +
                "WHERE operation.actor_account_id = ? AND operation.action_code = ? AND operation.request_id = ? " +
                "ORDER BY audit.id LIMIT 1",
            (rs, rowNum) -> {
                long raw = rs.getLong("object_id"); Long objectId = rs.wasNull() ? null : Long.valueOf(raw);
                return new Operation(rs.getString("request_fingerprint"), rs.getString("result_code"), objectId);
            }, accountId, actionCode, requestId);
        return rows.isEmpty() ? Optional.<Operation>empty() : Optional.of(rows.get(0));
    }

    public Long insertOperation(Long accountId, Long batchId, String actionCode, String requestId, String fingerprint,
                                Integer classificationVersion) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO business_operation (actor_account_id, actor_kind, action_code, batch_id, request_id, " +
                    "request_fingerprint, result_code, started_at, classification_version) " +
                    "VALUES (?, 'STUDENT', ?, ?, ?, ?, 'IN_PROGRESS', UTC_TIMESTAMP(3), ?)", Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, accountId); statement.setString(2, actionCode); statement.setLong(3, batchId);
            statement.setString(4, requestId); statement.setString(5, fingerprint);
            if (classificationVersion == null) statement.setNull(6, java.sql.Types.INTEGER);
            else statement.setInt(6, classificationVersion.intValue());
            return statement;
        }, holder);
        Number id = holder.getKey();
        if (id == null) throw new IllegalStateException("Database did not return an operation ID");
        return Long.valueOf(id.longValue());
    }

    public int nextVersion(Long batchStudentId) {
        Integer max = jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(version_no), 0) FROM preference_submission WHERE batch_student_id = ?",
            Integer.class, batchStudentId);
        return (max == null ? 0 : max.intValue()) + 1;
    }

    public Optional<TeacherScope> findFrozenTeacherScope(Long batchId, Long teacherId, Long majorId, String degreeType) {
        List<TeacherScope> rows = jdbcTemplate.query(
            "SELECT quota.id AS quota_id, slot.scope_version_id, scope.allowed_degree_mask, " +
                "EXISTS (SELECT 1 FROM teacher_allowed_major allowed WHERE allowed.scope_version_id = slot.scope_version_id AND allowed.major_id = ?) AS major_allowed " +
                "FROM batch_teacher_quota quota JOIN teacher_application_scope_slot slot " +
                "ON slot.batch_id = quota.batch_id AND slot.teacher_id = quota.teacher_id " +
                "JOIN teacher_application_scope_version scope ON scope.id = slot.scope_version_id " +
                "JOIN teacher ON teacher.id = quota.teacher_id JOIN selection_batch batch ON batch.id = quota.batch_id AND batch.college_id = teacher.college_id JOIN account teacher_account ON teacher_account.id = teacher.account_id " +
                "JOIN teacher_public_profile_version profile ON profile.id = teacher.current_public_profile_version_id " +
                "WHERE quota.batch_id = ? AND quota.teacher_id = ? AND slot.frozen_at IS NOT NULL " +
                "AND scope.frozen_at IS NOT NULL AND teacher_account.account_status = 'ACTIVE' " +
                "AND profile.published_at IS NOT NULL " +
                "AND EXISTS (SELECT 1 FROM annual_eligibility_slot eligibility_slot " +
                "JOIN annual_eligibility eligibility ON eligibility.id = eligibility_slot.eligibility_id " +
                "AND eligibility.eligibility_status = 'ELIGIBLE' AND eligibility.valid_to IS NULL " +
                "AND (eligibility.valid_from IS NULL OR eligibility.valid_from <= UTC_TIMESTAMP(3)) " +
                "WHERE eligibility_slot.academic_year_id = batch.academic_year_id " +
                "AND eligibility_slot.college_id = batch.college_id " +
                "AND eligibility_slot.teacher_id = teacher.id)",
            (rs, rowNum) -> new TeacherScope(rs.getLong("quota_id"), rs.getLong("scope_version_id"),
                rs.getInt("allowed_degree_mask"), rs.getBoolean("major_allowed")), majorId, batchId, teacherId);
        return rows.isEmpty() ? Optional.<TeacherScope>empty() : Optional.of(rows.get(0));
    }

    public Long insertSubmission(Long batchStudentId, int versionNo, int itemCount, Long accountId) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO preference_submission (batch_student_id, version_no, item_count, submitted_at, submitted_by, locked_at, submission_status) " +
                    "VALUES (?, ?, ?, UTC_TIMESTAMP(3), ?, NULL, 'SUBMITTED')", Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, batchStudentId); statement.setInt(2, versionNo); statement.setInt(3, itemCount); statement.setLong(4, accountId);
            return statement;
        }, holder);
        Number id = holder.getKey();
        if (id == null) throw new IllegalStateException("Database did not return a preference submission ID");
        return Long.valueOf(id.longValue());
    }

    public void insertItem(Long submissionId, int order, TeacherScope scope, Integer classificationVersion,
                           Long majorId, String degreeType) {
        jdbcTemplate.update(
            "INSERT INTO preference_item (submission_id, preference_order, batch_teacher_quota_id, scope_version_id, " +
                "classification_version, major_id, degree_type) VALUES (?, ?, ?, ?, ?, ?, ?)",
            submissionId, order, scope.getQuotaId(), scope.getScopeVersionId(), classificationVersion, majorId, degreeType);
    }

    public void setCurrentSubmission(Long batchStudentId, Long submissionId) {
        int changed = jdbcTemplate.update(
            "UPDATE batch_student SET current_submission_id = ?, preference_status = 'SUBMITTED', " +
                "row_version = row_version + 1 WHERE id = ?", submissionId, batchStudentId);
        if (changed != 1) throw new IllegalStateException("Batch student preference pointer was not updated");
    }

    public Optional<StudentPreferenceCommandVO> findSubmissionCommand(Long submissionId) {
        List<StudentPreferenceCommandVO> rows = jdbcTemplate.query(
            "SELECT id, version_no, DATE_FORMAT(submitted_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS submitted_at " +
                "FROM preference_submission WHERE id = ?",
            (rs, rowNum) -> new StudentPreferenceCommandVO(rs.getLong("id"), rs.getInt("version_no"),
                "SUBMITTED", rs.getString("submitted_at")), submissionId);
        return rows.isEmpty() ? Optional.<StudentPreferenceCommandVO>empty() : Optional.of(rows.get(0));
    }

    public Optional<Long> lockCurrentSubmission(Long batchStudentId) {
        List<Long> rows = jdbcTemplate.query(
            "SELECT submission.id FROM preference_submission submission JOIN batch_student batch_student " +
                "ON batch_student.current_submission_id = submission.id WHERE batch_student.id = ? " +
                "AND submission.batch_student_id = ? AND submission.submission_status = 'SUBMITTED' " +
                "AND submission.locked_at IS NULL FOR UPDATE",
            (rs, rowNum) -> rs.getLong("id"), batchStudentId, batchStudentId);
        return rows.isEmpty() ? Optional.<Long>empty() : Optional.of(rows.get(0));
    }

    public void withdrawSubmission(Long submissionId) {
        int changed = jdbcTemplate.update(
            "UPDATE preference_submission SET submission_status = 'WITHDRAWN', row_version = row_version + 1 " +
                "WHERE id = ? AND submission_status = 'SUBMITTED' AND locked_at IS NULL", submissionId);
        if (changed != 1) throw new IllegalStateException("Preference submission changed before withdrawal");
    }

    public void clearCurrentSubmission(Long batchStudentId) {
        int changed = jdbcTemplate.update(
            "UPDATE batch_student SET current_submission_id = NULL, preference_status = 'WITHDRAWN', " +
                "row_version = row_version + 1 WHERE id = ?", batchStudentId);
        if (changed != 1) throw new IllegalStateException("Batch student preference state was not updated");
    }

    public void insertApplicationEvent(Long submissionId, String actionCode, String oldStatus, String newStatus,
                                       String reason, Long accountId, Long operationId) {
        jdbcTemplate.update(
            "INSERT INTO application_event (object_type, object_id, action_code, old_status, new_status, actor_kind, " +
                "actor_account_id, occurred_at, business_operation_id, detail_text) " +
                "VALUES ('PREFERENCE_SUBMISSION', ?, ?, ?, ?, 'STUDENT', ?, UTC_TIMESTAMP(3), ?, ?)",
            submissionId, actionCode, oldStatus, newStatus, accountId, operationId, reason);
    }

    public void insertAuditEvent(Long operationId, Long accountId, Long batchId, Long batchStudentId,
                                 String actionCode, Long submissionId, String beforeValues,
                                 String afterValues, String reason) {
        jdbcTemplate.update(
            "INSERT INTO audit_event (actor_account_id, actor_kind, actor_role, scope_basis, object_type, object_id, " +
                "action_code, before_values_text, after_values_text, reason, occurred_at, business_operation_id) " +
                "VALUES (?, 'STUDENT', 'STUDENT', ?, 'PREFERENCE_SUBMISSION', ?, ?, ?, ?, ?, UTC_TIMESTAMP(3), ?)",
            accountId, "batchId=" + batchId + ";batchStudentId=" + batchStudentId,
            submissionId, actionCode, beforeValues, afterValues, reason, operationId);
    }

    public void completeOperation(Long operationId) {
        int changed = jdbcTemplate.update("UPDATE business_operation SET result_code = 'OK', completed_at = UTC_TIMESTAMP(3) WHERE id = ?", operationId);
        if (changed != 1) throw new IllegalStateException("Business operation row was not completed");
    }

    public static class StageWindow {
        private final String batchStatus; private final String stageStatus; private final Timestamp startAt; private final Timestamp endAt;
        public StageWindow(String batchStatus, String stageStatus, Timestamp startAt, Timestamp endAt) {
            this.batchStatus = batchStatus; this.stageStatus = stageStatus; this.startAt = startAt; this.endAt = endAt;
        }
        public String getBatchStatus() { return batchStatus; } public String getStageStatus() { return stageStatus; }
        public Timestamp getStartAt() { return startAt; } public Timestamp getEndAt() { return endAt; }
    }
    public static class BatchStudentState {
        private final Long id; private final String eligibilitySnapshot; private final boolean accountEnabled;
        private final Integer confirmedVersion; private final String preferenceStatus; private final String matchStatus;
        private final Long currentSubmissionId; private final Long finalSubmissionId;
        public BatchStudentState(Long id, String eligibilitySnapshot, boolean accountEnabled, Integer confirmedVersion,
                                 String preferenceStatus, String matchStatus, Long currentSubmissionId, Long finalSubmissionId) {
            this.id = id; this.eligibilitySnapshot = eligibilitySnapshot; this.accountEnabled = accountEnabled;
            this.confirmedVersion = confirmedVersion; this.preferenceStatus = preferenceStatus; this.matchStatus = matchStatus;
            this.currentSubmissionId = currentSubmissionId; this.finalSubmissionId = finalSubmissionId;
        }
        public Long getId() { return id; } public String getEligibilitySnapshot() { return eligibilitySnapshot; }
        public boolean isAccountEnabled() { return accountEnabled; } public Integer getConfirmedVersion() { return confirmedVersion; }
        public String getPreferenceStatus() { return preferenceStatus; } public String getMatchStatus() { return matchStatus; }
        public Long getCurrentSubmissionId() { return currentSubmissionId; } public Long getFinalSubmissionId() { return finalSubmissionId; }
    }
    public static class StudentClassification {
        private final Integer version; private final Long majorId; private final String degreeType;
        public StudentClassification(Integer version, Long majorId, String degreeType) { this.version = version; this.majorId = majorId; this.degreeType = degreeType; }
        public Integer getVersion() { return version; } public Long getMajorId() { return majorId; } public String getDegreeType() { return degreeType; }
    }
    public static class TeacherScope {
        private final Long quotaId; private final Long scopeVersionId; private final int allowedDegreeMask; private final boolean majorAllowed;
        public TeacherScope(Long quotaId, Long scopeVersionId, int allowedDegreeMask, boolean majorAllowed) {
            this.quotaId = quotaId; this.scopeVersionId = scopeVersionId; this.allowedDegreeMask = allowedDegreeMask; this.majorAllowed = majorAllowed;
        }
        public Long getQuotaId() { return quotaId; } public Long getScopeVersionId() { return scopeVersionId; }
        public int getAllowedDegreeMask() { return allowedDegreeMask; } public boolean isMajorAllowed() { return majorAllowed; }
    }
    public static class Operation {
        private final String fingerprint; private final String resultCode; private final Long objectId;
        public Operation(String fingerprint, String resultCode, Long objectId) { this.fingerprint = fingerprint; this.resultCode = resultCode; this.objectId = objectId; }
        public String getFingerprint() { return fingerprint; } public String getResultCode() { return resultCode; }
        public Long getObjectId() { return objectId; }
    }
}



