package cn.hnust.selection.repository;

import cn.hnust.selection.vo.StudentIdentityConfirmationVO;
import org.springframework.dao.EmptyResultDataAccessException;
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
public class StudentIdentityConfirmationRepository {
    private final JdbcTemplate jdbcTemplate;
    public StudentIdentityConfirmationRepository(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    public Optional<StageWindow> lockFillingStage(Long batchId) {
        List<StageWindow> rows = jdbcTemplate.query(
            "SELECT batch.batch_status, stage.stage_status, COALESCE(stage.effective_start_at, stage.planned_start_at) AS effective_start_at, COALESCE(stage.effective_end_at, stage.planned_end_at) AS effective_end_at " +
                "FROM selection_batch batch JOIN batch_stage stage ON stage.batch_id = batch.id " +
                "WHERE batch.id = ? AND stage.stage_code = 'FILLING' FOR UPDATE",
            (rs, rowNum) -> new StageWindow(rs.getString("batch_status"), rs.getString("stage_status"),
                rs.getTimestamp("effective_start_at"), rs.getTimestamp("effective_end_at")), batchId);
        return rows.isEmpty() ? Optional.<StageWindow>empty() : Optional.of(rows.get(0));
    }

    public Timestamp databaseUtcNow() {
        return jdbcTemplate.queryForObject("SELECT UTC_TIMESTAMP(3)", Timestamp.class);
    }

    public Optional<BatchStudentState> lockBatchStudent(Long batchId, Long studentId) {
        List<BatchStudentState> rows = jdbcTemplate.query(
            "SELECT participant.id, participant.eligibility_snapshot, participant.account_enabled_snapshot " +
                "FROM batch_student participant JOIN selection_batch batch ON batch.id = participant.batch_id " +
                "JOIN student ON student.id = participant.student_id AND student.college_id = batch.college_id " +
                "WHERE participant.batch_id = ? AND participant.student_id = ? FOR UPDATE",
            (rs, rowNum) -> new BatchStudentState(rs.getLong("id"), rs.getString("eligibility_snapshot"),
                rs.getBoolean("account_enabled_snapshot")), batchId, studentId);
        return rows.isEmpty() ? Optional.<BatchStudentState>empty() : Optional.of(rows.get(0));
    }
    public Optional<Integer> lockCurrentClassification(Long studentId, Long accountId) {
        try {
            Integer version = jdbcTemplate.queryForObject(
                "SELECT student.classification_version FROM student JOIN account ON account.id = student.account_id " +
                    "WHERE student.id = ? AND student.account_id = ? AND account.account_status = 'ACTIVE' FOR UPDATE",
                Integer.class, studentId, accountId);
            return Optional.ofNullable(version);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public Optional<Operation> findOperation(Long accountId, String requestId) {
        List<Operation> rows = jdbcTemplate.query(
            "SELECT operation.request_fingerprint, operation.result_code, operation.classification_version, " +
                "audit.object_id, DATE_FORMAT(audit.occurred_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS confirmed_at " +
                "FROM business_operation operation LEFT JOIN audit_event audit " +
                "ON audit.business_operation_id = operation.id AND audit.action_code = operation.action_code " +
                "WHERE operation.actor_account_id = ? AND operation.action_code = 'STUDENT_IDENTITY_CONFIRM' " +
                "AND operation.request_id = ? ORDER BY audit.id LIMIT 1",
            (rs, rowNum) -> {
                long rawObjectId = rs.getLong("object_id");
                Long objectId = rs.wasNull() ? null : Long.valueOf(rawObjectId);
                int rawVersion = rs.getInt("classification_version");
                Integer version = rs.wasNull() ? null : Integer.valueOf(rawVersion);
                return new Operation(rs.getString("request_fingerprint"), rs.getString("result_code"),
                    version, objectId, rs.getString("confirmed_at"));
            }, accountId, requestId);
        return rows.isEmpty() ? Optional.<Operation>empty() : Optional.of(rows.get(0));
    }

    public Long insertOperation(Long accountId, Long batchId, String requestId, String fingerprint, int version) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO business_operation (actor_account_id, actor_kind, action_code, batch_id, request_id, " +
                    "request_fingerprint, result_code, started_at, classification_version) " +
                    "VALUES (?, 'STUDENT', 'STUDENT_IDENTITY_CONFIRM', ?, ?, ?, 'IN_PROGRESS', UTC_TIMESTAMP(3), ?)",
                Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, accountId);
            statement.setLong(2, batchId);
            statement.setString(3, requestId);
            statement.setString(4, fingerprint);
            statement.setInt(5, version);
            return statement;
        }, holder);
        Number id = holder.getKey();
        if (id == null) throw new IllegalStateException("Database did not return an operation ID");
        return Long.valueOf(id.longValue());
    }

    public void confirm(Long batchStudentId, int version) {
        int changed = jdbcTemplate.update(
            "UPDATE batch_student SET confirmed_classification_version = ?, identity_confirmed_at = UTC_TIMESTAMP(3), " +
                "row_version = row_version + 1 WHERE id = ?", version, batchStudentId);
        if (changed != 1) throw new IllegalStateException("Student batch row was not updated");
    }

    public void insertAudit(Long operationId, Long accountId, Long batchId, Long batchStudentId,
                            int version, String requestId) {
        jdbcTemplate.update(
            "INSERT INTO audit_event (actor_account_id, actor_kind, actor_role, scope_basis, object_type, object_id, " +
                "action_code, before_values_text, after_values_text, occurred_at, business_operation_id) " +
                "VALUES (?, 'STUDENT', 'STUDENT', ?, 'BATCH_STUDENT', ?, 'STUDENT_IDENTITY_CONFIRM', NULL, ?, UTC_TIMESTAMP(3), ?)",
            accountId, "batchId=" + batchId + ";studentBatchId=" + batchStudentId, batchStudentId,
            "{\"classificationVersion\":" + version + ",\"requestId\":\"" + requestId + "\"}", operationId);
    }

    public void completeOperation(Long operationId) {
        int changed = jdbcTemplate.update("UPDATE business_operation SET result_code = 'OK', completed_at = UTC_TIMESTAMP(3) WHERE id = ?", operationId);
        if (changed != 1) throw new IllegalStateException("Business operation row was not completed");
    }

    public Optional<StudentIdentityConfirmationVO> findConfirmation(Long batchStudentId) {
        List<StudentIdentityConfirmationVO> rows = jdbcTemplate.query(
            "SELECT confirmed_classification_version, DATE_FORMAT(identity_confirmed_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS confirmed_at " +
                "FROM batch_student WHERE id = ?",
            (rs, rowNum) -> new StudentIdentityConfirmationVO(rs.getInt("confirmed_classification_version"),
                rs.getString("confirmed_at")), batchStudentId);
        return rows.isEmpty() ? Optional.<StudentIdentityConfirmationVO>empty() : Optional.of(rows.get(0));
    }

    public static class StageWindow {
        private final String batchStatus;
        private final String stageStatus;
        private final Timestamp startAt;
        private final Timestamp endAt;
        public StageWindow(String batchStatus, String stageStatus, Timestamp startAt, Timestamp endAt) {
            this.batchStatus = batchStatus; this.stageStatus = stageStatus; this.startAt = startAt; this.endAt = endAt;
        }
        public String getBatchStatus() { return batchStatus; }
        public String getStageStatus() { return stageStatus; }
        public Timestamp getStartAt() { return startAt; }
        public Timestamp getEndAt() { return endAt; }
    }
    public static class BatchStudentState {
        private final Long id;
        private final String eligibilitySnapshot;
        private final boolean accountEnabled;
        public BatchStudentState(Long id, String eligibilitySnapshot, boolean accountEnabled) {
            this.id = id; this.eligibilitySnapshot = eligibilitySnapshot; this.accountEnabled = accountEnabled;
        }
        public Long getId() { return id; }
        public String getEligibilitySnapshot() { return eligibilitySnapshot; }
        public boolean isAccountEnabled() { return accountEnabled; }
    }
    public static class Operation {
        private final String fingerprint; private final String resultCode; private final Integer version;
        private final Long batchStudentId; private final String confirmedAt;
        public Operation(String fingerprint, String resultCode, Integer version, Long batchStudentId, String confirmedAt) {
            this.fingerprint = fingerprint; this.resultCode = resultCode; this.version = version;
            this.batchStudentId = batchStudentId; this.confirmedAt = confirmedAt;
        }
        public String getFingerprint() { return fingerprint; }
        public String getResultCode() { return resultCode; }
        public Integer getVersion() { return version; }
        public Long getBatchStudentId() { return batchStudentId; }
        public String getConfirmedAt() { return confirmedAt; }
    }
}




