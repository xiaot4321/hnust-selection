package cn.hnust.selection.repository;

import cn.hnust.selection.vo.StudentIdentityCorrectionCommandVO;
import cn.hnust.selection.vo.StudentIdentityCorrectionVO;
import cn.hnust.selection.vo.StudentProfileVO;
import cn.hnust.selection.vo.AdminIdentityCorrectionVO;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Optional;

@Repository
public class StudentIdentityCorrectionRepository {
    private final JdbcTemplate jdbcTemplate;

    public StudentIdentityCorrectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Integer> lockStudentClassification(Long studentId, Long accountId) {
        try {
            Integer version = jdbcTemplate.queryForObject(
                "SELECT classification_version FROM student WHERE id = ? AND account_id = ? FOR UPDATE",
                Integer.class, studentId, accountId);
            return Optional.ofNullable(version);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public boolean majorExists(Long majorId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM major WHERE id = ?", Integer.class, majorId);
        return count != null && count.intValue() > 0;
    }

    public Optional<Operation> findOperation(Long accountId, String actionCode, String requestId) {
        List<Operation> rows = jdbcTemplate.query(
            "SELECT operation.request_fingerprint, operation.result_code, audit.object_id " +
                "FROM business_operation operation LEFT JOIN audit_event audit " +
                "ON audit.business_operation_id = operation.id AND audit.action_code = operation.action_code " +
                "WHERE operation.actor_account_id = ? AND operation.action_code = ? AND operation.request_id = ? " +
                "ORDER BY audit.id LIMIT 1",
            (rs, rowNum) -> {
                long rawId = rs.getLong("object_id");
                Long objectId = rs.wasNull() ? null : Long.valueOf(rawId);
                return new Operation(rs.getString("request_fingerprint"), rs.getString("result_code"), objectId);
            }, accountId, actionCode, requestId);
        return rows.isEmpty() ? Optional.<Operation>empty() : Optional.of(rows.get(0));
    }

    public Long insertOperation(Long accountId, String actionCode, String requestId,
                                String fingerprint, int classificationVersion) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO business_operation " +
                    "(actor_account_id, actor_kind, action_code, request_id, request_fingerprint, " +
                    "result_code, started_at, completed_at, classification_version) " +
                    "VALUES (?, 'STUDENT', ?, ?, ?, 'IN_PROGRESS', UTC_TIMESTAMP(3), NULL, ?)",
                Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, accountId);
            statement.setString(2, actionCode);
            statement.setString(3, requestId);
            statement.setString(4, fingerprint);
            statement.setInt(5, classificationVersion);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("Database did not return an ID for business_operation");
        return Long.valueOf(key.longValue());
    }

    public Long insertCorrectionRequest(Long studentId, int classificationVersion, Long requestedMajorId,
                                        String requestedDegreeType, String explanation) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO student_identity_correction_request " +
                    "(student_id, submitted_at, current_classification_version, requested_major_id, " +
                    "requested_degree_type, student_explanation, request_status, handled_by, handled_at, " +
                    "handling_comment, resulting_revision_id) " +
                    "VALUES (?, UTC_TIMESTAMP(3), ?, ?, ?, ?, 'PENDING', NULL, NULL, NULL, NULL)",
                Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, studentId);
            statement.setInt(2, classificationVersion);
            setNullableLong(statement, 3, requestedMajorId);
            statement.setString(4, requestedDegreeType);
            statement.setString(5, explanation);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("Database did not return an ID for student identity request");
        return Long.valueOf(key.longValue());
    }

    public void insertAuditEvent(Long operationId, Long accountId, Long studentId, Long requestId,
                                 String beforeValues, String afterValues) {
        jdbcTemplate.update(
            "INSERT INTO audit_event (actor_account_id, actor_kind, actor_role, scope_basis, object_type, object_id, " +
                "action_code, before_values_text, after_values_text, reason, approval_comment, occurred_at, business_operation_id) " +
                "VALUES (?, 'STUDENT', 'STUDENT', ?, 'STUDENT_IDENTITY_CORRECTION', ?, " +
                "'STUDENT_IDENTITY_CORRECTION_SUBMIT', ?, ?, NULL, NULL, UTC_TIMESTAMP(3), ?)",
            accountId, "studentId=" + studentId, requestId, beforeValues, afterValues, operationId);
    }

    public void completeOperation(Long operationId) {
        int changed = jdbcTemplate.update(
            "UPDATE business_operation SET result_code = 'OK', completed_at = UTC_TIMESTAMP(3) WHERE id = ?",
            operationId);
        if (changed != 1) throw new IllegalStateException("Business operation row was not completed");
    }

    public Optional<StudentIdentityCorrectionCommandVO> findCommand(Long requestId) {
        List<StudentIdentityCorrectionCommandVO> rows = jdbcTemplate.query(
            "SELECT id, request_status, DATE_FORMAT(submitted_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS submitted_at " +
                "FROM student_identity_correction_request WHERE id = ?",
            (rs, rowNum) -> new StudentIdentityCorrectionCommandVO(rs.getLong("id"),
                rs.getString("request_status"), rs.getString("submitted_at")), requestId);
        return rows.isEmpty() ? Optional.<StudentIdentityCorrectionCommandVO>empty() : Optional.of(rows.get(0));
    }

    public long countOwnRequests(Long studentId) {
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM student_identity_correction_request WHERE student_id = ?", Long.class, studentId);
        return count == null ? 0L : count.longValue();
    }

    public List<StudentIdentityCorrectionVO> listOwnRequests(Long studentId, int pageNo, int pageSize) {
        long offset = ((long) pageNo - 1L) * pageSize;
        String sql = "SELECT request.id AS request_id, " +
            "DATE_FORMAT(request.submitted_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS submitted_at, " +
            "request.current_classification_version, request.requested_major_id, major.major_code, major.name AS major_name, " +
            "request.requested_degree_type, request.student_explanation, request.request_status, " +
            "DATE_FORMAT(request.handled_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS handled_at, " +
            "request.handling_comment, revision.version_no AS resulting_classification_version " +
            "FROM student_identity_correction_request request " +
            "LEFT JOIN major ON major.id = request.requested_major_id " +
            "LEFT JOIN student_classification_revision revision ON revision.id = request.resulting_revision_id " +
            "WHERE request.student_id = ? ORDER BY request.submitted_at DESC, request.id DESC LIMIT ? OFFSET ?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            StudentIdentityCorrectionVO item = new StudentIdentityCorrectionVO();
            item.setRequestId(rs.getLong("request_id"));
            item.setSubmittedAt(rs.getString("submitted_at"));
            item.setCurrentClassificationVersion(rs.getInt("current_classification_version"));
            long rawMajorId = rs.getLong("requested_major_id");
            if (!rs.wasNull()) {
                item.setRequestedMajor(new StudentProfileVO.StudentMajorVO(rawMajorId,
                    rs.getString("major_code"), rs.getString("major_name")));
            }
            item.setRequestedDegreeType(rs.getString("requested_degree_type"));
            item.setStudentExplanation(rs.getString("student_explanation"));
            item.setStatus(rs.getString("request_status"));
            item.setHandledAt(rs.getString("handled_at"));
            item.setHandlingComment(rs.getString("handling_comment"));
            int rawResultingVersion = rs.getInt("resulting_classification_version");
            item.setResultingClassificationVersion(rs.wasNull() ? null : Integer.valueOf(rawResultingVersion));
            return item;
        }, studentId, pageSize, offset);
    }

    public long countAdminRequests(Long collegeId, String status) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM student_identity_correction_request request " +
            "JOIN student student ON student.id = request.student_id WHERE student.college_id = ? " +
            "AND request.request_status = ?", Long.class, collegeId, status);
        return count == null ? 0L : count.longValue();
    }

    public List<AdminIdentityCorrectionVO> listAdminRequests(Long collegeId, String status, int pageNo, int pageSize) {
        long offset = ((long) pageNo - 1L) * pageSize;
        String sql = "SELECT request.id AS request_id, student.id AS student_id, student.student_no, student.full_name, " +
            "student.college_id, college.name AS college_name, DATE_FORMAT(request.submitted_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS submitted_at, " +
            "request.current_classification_version, student.major_id AS current_major_id, current_major.major_code AS current_major_code, " +
            "current_major.name AS current_major_name, student.degree_type AS current_degree_type, request.requested_major_id, " +
            "requested_major.major_code AS requested_major_code, requested_major.name AS requested_major_name, " +
            "request.requested_degree_type, request.student_explanation, request.request_status, request.handling_comment " +
            "FROM student_identity_correction_request request JOIN student student ON student.id = request.student_id " +
            "JOIN college college ON college.id = student.college_id JOIN major current_major ON current_major.id = student.major_id " +
            "LEFT JOIN major requested_major ON requested_major.id = request.requested_major_id " +
            "WHERE student.college_id = ? AND request.request_status = ? " +
            "ORDER BY request.submitted_at ASC, request.id ASC LIMIT ? OFFSET ?";
        return jdbcTemplate.query(sql, (rs, row) -> {
            AdminIdentityCorrectionVO item = new AdminIdentityCorrectionVO();
            item.setRequestId(rs.getLong("request_id")); item.setStudentId(rs.getLong("student_id"));
            item.setStudentNo(rs.getString("student_no")); item.setFullName(rs.getString("full_name"));
            item.setCollegeId(rs.getLong("college_id")); item.setCollegeName(rs.getString("college_name"));
            item.setSubmittedAt(rs.getString("submitted_at")); item.setCurrentClassificationVersion(rs.getInt("current_classification_version"));
            item.setCurrentMajorId(rs.getLong("current_major_id")); item.setCurrentMajorCode(rs.getString("current_major_code"));
            item.setCurrentMajorName(rs.getString("current_major_name")); item.setCurrentDegreeType(rs.getString("current_degree_type"));
            long requestedMajorId = rs.getLong("requested_major_id");
            if (!rs.wasNull()) item.setRequestedMajorId(Long.valueOf(requestedMajorId));
            item.setRequestedMajorCode(rs.getString("requested_major_code")); item.setRequestedMajorName(rs.getString("requested_major_name"));
            item.setRequestedDegreeType(rs.getString("requested_degree_type")); item.setStudentExplanation(rs.getString("student_explanation"));
            item.setStatus(rs.getString("request_status")); item.setHandlingComment(rs.getString("handling_comment"));
            return item;
        }, collegeId, status, pageSize, offset);
    }

    public Optional<IdentityCorrectionRecord> lockAdminRequest(Long requestId) {
        String sql = "SELECT request.id, request.student_id, student.college_id, request.current_classification_version, " +
            "request.requested_major_id, request.requested_degree_type, request.student_explanation, request.request_status, " +
            "student.major_id AS current_major_id, student.degree_type AS current_degree_type, student.classification_version " +
            "FROM student_identity_correction_request request JOIN student student ON student.id = request.student_id " +
            "WHERE request.id = ? FOR UPDATE";
        List<IdentityCorrectionRecord> rows = jdbcTemplate.query(sql, (rs, row) -> {
            long requestedMajorId = rs.getLong("requested_major_id");
            Long requestedMajor = rs.wasNull() ? null : Long.valueOf(requestedMajorId);
            return new IdentityCorrectionRecord(rs.getLong("id"), rs.getLong("student_id"), rs.getLong("college_id"),
                rs.getInt("current_classification_version"), requestedMajor, rs.getString("requested_degree_type"),
                rs.getString("student_explanation"), rs.getString("request_status"), rs.getLong("current_major_id"),
                rs.getString("current_degree_type"), rs.getInt("classification_version"));
        }, requestId);
        return rows.isEmpty() ? Optional.<IdentityCorrectionRecord>empty() : Optional.of(rows.get(0));
    }

    public boolean activeMajorBelongsToCollege(Long majorId, Long collegeId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM major WHERE id = ? AND college_id = ? " +
            "AND is_active = TRUE", Integer.class, majorId, collegeId);
        return count != null && count.intValue() == 1;
    }

    public boolean updateStudentClassification(Long studentId, int expectedVersion, Long majorId, String degreeType) {
        return jdbcTemplate.update("UPDATE student SET major_id = ?, degree_type = ?, classification_version = classification_version + 1, " +
            "row_version = row_version + 1 WHERE id = ? AND classification_version = ?", majorId, degreeType, studentId, expectedVersion) == 1;
    }

    public Long insertClassificationRevision(IdentityCorrectionRecord request, Long majorId, String degreeType,
        String reason, Long actorId) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("INSERT INTO student_classification_revision " +
                "(student_id, version_no, from_major_id, to_major_id, from_degree_type, to_degree_type, basis, reason, " +
                "changed_by, changed_at, correction_request_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(3), ?)",
                Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, request.studentId); statement.setInt(2, request.classificationVersion + 1);
            statement.setLong(3, request.currentMajorId); statement.setLong(4, majorId);
            statement.setString(5, request.currentDegreeType); statement.setString(6, degreeType);
            statement.setString(7, "STUDENT_IDENTITY_CORRECTION_REQUEST:" + request.requestId);
            statement.setString(8, reason); statement.setLong(9, actorId); statement.setLong(10, request.requestId);
            return statement;
        }, holder);
        Number id = holder.getKey();
        if (id == null) throw new IllegalStateException("Database did not return an ID for classification revision");
        return Long.valueOf(id.longValue());
    }

    public List<IdentityBatchImpact> lockBatchImpacts(Long studentId) {
        String sql = "SELECT participant.id AS batch_student_id, batch.id AS batch_id, batch.academic_year_id, " +
            "batch.batch_status, batch.supplement_planned, participant.match_status, participant.current_relation_id, " +
            "filling.actual_started_at, window.window_status " +
            "FROM batch_student participant JOIN selection_batch batch ON batch.id = participant.batch_id " +
            "JOIN batch_stage filling ON filling.batch_id = batch.id AND filling.stage_code = 'FILLING' " +
            "LEFT JOIN supplement_window window ON window.batch_id = batch.id " +
            "WHERE participant.student_id = ? ORDER BY batch.id FOR UPDATE";
        return jdbcTemplate.query(sql, (rs, row) -> {
            long relationId = rs.getLong("current_relation_id");
            boolean relationMissing = rs.wasNull();
            return new IdentityBatchImpact(rs.getLong("batch_student_id"), rs.getLong("batch_id"),
                rs.getLong("academic_year_id"), rs.getString("batch_status"), rs.getBoolean("supplement_planned"),
                rs.getString("match_status"), relationMissing ? null : Long.valueOf(relationId),
                rs.getTimestamp("actual_started_at") != null, rs.getString("window_status"));
        }, studentId);
    }

    /** 将身份纠错对一个已开窗批次的影响写入同一业务事务。 */
    public Long applyIdentityCorrectionToBatch(IdentityBatchImpact impact, Long studentId, Long requestId,
        Long revisionId, int impactNo, Long actorId, Long operationId) {
        Long relationAdjustmentId = null;
        if (impact.currentRelationId != null) {
            RelationRow relation = lockCurrentRelation(impact.currentRelationId, impact.batchStudentId);
            List<QuotaRow> quotas = jdbcTemplate.query("SELECT id, quota_limit, occupied_count FROM batch_teacher_quota " +
                "WHERE id = ? FOR UPDATE", (rs, n) -> new QuotaRow(rs.getLong("id"), rs.getInt("quota_limit"),
                rs.getInt("occupied_count")), relation.quotaId);
            if (quotas.isEmpty() || quotas.get(0).occupiedCount < 1) throw new IllegalStateException("关系名额占用与名额账户不一致");
            QuotaRow quota = quotas.get(0);
            jdbcTemplate.update("UPDATE matching_relation SET relation_status = 'REVOKED', revoked_at = UTC_TIMESTAMP(3), " +
                "row_version = row_version + 1 WHERE id = ? AND relation_status = 'LOCKED'", relation.id);
            jdbcTemplate.update("UPDATE batch_teacher_quota SET occupied_count = occupied_count - 1, row_version = row_version + 1 " +
                "WHERE id = ? AND occupied_count > 0", quota.id);
            jdbcTemplate.update("DELETE FROM student_year_match_slot WHERE student_id = ? AND academic_year_id = ? AND relation_id = ?",
                studentId, impact.academicYearId, relation.id);
            KeyHolder adjustmentKey = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement("INSERT INTO relation_adjustment(batch_id, student_id, " +
                    "adjustment_type, old_relation_id, old_teacher_id, reason, approval_comment, actor_account_id, occurred_at, " +
                    "business_operation_id) VALUES (?, ?, 'REVOKE', ?, ?, ?, ?, ?, UTC_TIMESTAMP(3), ?)", Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, impact.batchId); ps.setLong(2, studentId); ps.setLong(3, relation.id); ps.setLong(4, relation.teacherId);
                ps.setString(5, "学生身份纠错申请 #" + requestId); ps.setString(6, "IDENTITY_CORRECTION");
                ps.setLong(7, actorId); ps.setLong(8, operationId); return ps;
            }, adjustmentKey);
            Number adjustment = adjustmentKey.getKey();
            relationAdjustmentId = adjustment == null ? null : Long.valueOf(adjustment.longValue());
            jdbcTemplate.update("INSERT INTO quota_ledger(batch_teacher_quota_id, change_type, limit_before, limit_after, " +
                "occupied_before, occupied_after, delta, relation_id, business_operation_id, actor_account_id, reason, occurred_at) " +
                "VALUES (?, 'RELATION_REVOKE', ?, ?, ?, ?, -1, ?, ?, ?, ?, UTC_TIMESTAMP(3))", quota.id, quota.limit,
                quota.limit, quota.occupiedCount, quota.occupiedCount - 1, relation.id, operationId, actorId,
                "身份纠错申请 #" + requestId);
        }
        jdbcTemplate.update("INSERT INTO application_event(object_type, object_id, execution_cycle, action_code, old_status, new_status, " +
            "close_reason, actor_kind, actor_account_id, occurred_at, business_operation_id, detail_text) " +
            "SELECT 'ROUND_APPLICATION', id, execution_cycle, 'IDENTITY_CORRECTION_SKIP', application_status, " +
            "'SKIPPED_IDENTITY_CORRECTION', 'IDENTITY_CORRECTION', 'ADMIN', ?, UTC_TIMESTAMP(3), ?, ? " +
            "FROM round_application WHERE batch_student_id = ? AND application_status IN ('WAITING','IN_REVIEW')",
            actorId, operationId, "identityCorrectionRequestId=" + requestId, impact.batchStudentId);
        jdbcTemplate.update("UPDATE round_application SET application_status = 'SKIPPED_IDENTITY_CORRECTION', " +
            "close_reason = 'IDENTITY_CORRECTION', decided_at = UTC_TIMESTAMP(3), row_version = row_version + 1 " +
            "WHERE batch_student_id = ? AND application_status IN ('WAITING','IN_REVIEW')", impact.batchStudentId);
        jdbcTemplate.update("INSERT INTO application_event(object_type, object_id, execution_cycle, action_code, old_status, new_status, " +
            "close_reason, actor_kind, actor_account_id, occurred_at, business_operation_id, detail_text) " +
            "SELECT 'SUPPLEMENT_APPLICATION', id, NULL, 'IDENTITY_CORRECTION_CANCEL', application_status, " +
            "'CANCELLED_BY_IDENTITY_CORRECTION', 'IDENTITY_CORRECTION', 'ADMIN', ?, UTC_TIMESTAMP(3), ?, ? " +
            "FROM supplement_application WHERE batch_student_id = ? AND application_status = 'IN_REVIEW'",
            actorId, operationId, "identityCorrectionRequestId=" + requestId, impact.batchStudentId);
        jdbcTemplate.update("UPDATE supplement_application SET application_status = 'CANCELLED_BY_IDENTITY_CORRECTION', " +
            "close_reason = 'IDENTITY_CORRECTION', decided_at = UTC_TIMESTAMP(3), row_version = row_version + 1 " +
            "WHERE batch_student_id = ? AND application_status = 'IN_REVIEW'", impact.batchStudentId);
        jdbcTemplate.update("DELETE FROM student_pending_supplement_slot WHERE student_id = ?", studentId);
        jdbcTemplate.update("INSERT INTO student_match_event(batch_student_id, old_status, new_status, reason_code, source_type, " +
            "source_id, occurred_at, business_operation_id) VALUES (?, ?, 'UNMATCHED', 'IDENTITY_CORRECTION', " +
            "'IDENTITY_CORRECTION', ?, UTC_TIMESTAMP(3), ?)", impact.batchStudentId, impact.matchStatus, requestId, operationId);
        jdbcTemplate.update("UPDATE batch_student SET match_status = 'UNMATCHED', match_reason = 'IDENTITY_CORRECTION', " +
            "match_changed_at = UTC_TIMESTAMP(3), current_relation_id = NULL, identity_confirmed_at = NULL, " +
            "confirmed_classification_version = NULL, row_version = row_version + 1 WHERE id = ?", impact.batchStudentId);
        jdbcTemplate.update("INSERT INTO student_classification_impact(revision_id, impact_no, batch_id, impact_type, " +
            "preference_submission_id, relation_adjustment_id, occurred_at) VALUES (?, ?, ?, 'BATCH_UNMATCHED', ?, ?, UTC_TIMESTAMP(3))",
            revisionId, impactNo, impact.batchId, null, relationAdjustmentId);
        return relationAdjustmentId;
    }

    public void updateCorrectionRequest(Long requestId, String status, Long actorId, String comment, Long revisionId) {
        jdbcTemplate.update("UPDATE student_identity_correction_request SET request_status = ?, handled_by = ?, " +
            "handled_at = UTC_TIMESTAMP(3), handling_comment = ?, resulting_revision_id = ?, row_version = row_version + 1 " +
            "WHERE id = ? AND request_status = 'PENDING'", status, actorId, comment, revisionId, requestId);
    }

    public void insertAdminOperation(Long operationId, Long actorId, Long collegeId, String action, String key, String fingerprint) {
        jdbcTemplate.update("INSERT INTO business_operation(id, actor_account_id, actor_kind, action_code, college_id, request_id, " +
            "request_fingerprint, result_code, started_at) VALUES (?, ?, 'ADMIN', ?, ?, ?, ?, 'IN_PROGRESS', UTC_TIMESTAMP(3))",
            operationId, actorId, action, collegeId, key, fingerprint);
    }

    public Long insertOperationForAdmin(Long actorId, Long collegeId, String action, String key, String fingerprint) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("INSERT INTO business_operation(actor_account_id, actor_kind, " +
                "action_code, college_id, request_id, request_fingerprint, result_code, started_at) " +
                "VALUES (?, 'ADMIN', ?, ?, ?, ?, 'IN_PROGRESS', UTC_TIMESTAMP(3))", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, actorId); ps.setString(2, action); ps.setLong(3, collegeId); ps.setString(4, key); ps.setString(5, fingerprint);
            return ps;
        }, holder);
        Number id = holder.getKey();
        if (id == null) throw new IllegalStateException("Database did not return an ID for business_operation");
        return Long.valueOf(id.longValue());
    }

    public void insertAdminAudit(Long operationId, Long actorId, Long collegeId, Long objectId, String action,
        String before, String after, String reason, String approvalComment) {
        jdbcTemplate.update("INSERT INTO audit_event(actor_account_id, actor_kind, actor_role, scope_basis, object_type, object_id, " +
            "action_code, before_values_text, after_values_text, reason, approval_comment, occurred_at, business_operation_id) " +
            "VALUES (?, 'ADMIN', 'ADMIN', ?, 'STUDENT_IDENTITY_CORRECTION', ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(3), ?)",
            actorId, "collegeId=" + collegeId, objectId, action, before, after, reason, approvalComment, operationId);
    }

    public void completeAdminOperation(Long operationId) {
        jdbcTemplate.update("UPDATE business_operation SET result_code = 'OK', completed_at = UTC_TIMESTAMP(3), row_version = row_version + 1 " +
            "WHERE id = ? AND result_code = 'IN_PROGRESS'", operationId);
    }

    public Optional<Operation> findAdminDecisionOperation(Long accountId, String actionCode, String key) {
        return findOperation(accountId, actionCode, key);
    }

    private RelationRow lockCurrentRelation(Long relationId, Long batchStudentId) {
        List<RelationRow> rows = jdbcTemplate.query("SELECT relation.id, relation.batch_teacher_quota_id, quota.teacher_id " +
            "FROM matching_relation relation JOIN batch_teacher_quota quota ON quota.id = relation.batch_teacher_quota_id " +
            "WHERE relation.id = ? AND relation.batch_student_id = ? AND relation.relation_status = 'LOCKED' FOR UPDATE",
            (rs, n) -> new RelationRow(rs.getLong("id"), rs.getLong("batch_teacher_quota_id"), rs.getLong("teacher_id")),
            relationId, batchStudentId);
        if (rows.isEmpty()) throw new IllegalStateException("匹配关系已变化");
        return rows.get(0);
    }

    public static class IdentityCorrectionRecord {
        public final Long requestId, studentId, collegeId, currentMajorId;
        public final int requestedVersion, classificationVersion;
        public final Long requestedMajorId;
        public final String requestedDegreeType, explanation, status, currentDegreeType;
        public IdentityCorrectionRecord(Long requestId, Long studentId, Long collegeId, int requestedVersion,
            Long requestedMajorId, String requestedDegreeType, String explanation, String status,
            Long currentMajorId, String currentDegreeType, int classificationVersion) {
            this.requestId=requestId; this.studentId=studentId; this.collegeId=collegeId; this.requestedVersion=requestedVersion;
            this.requestedMajorId=requestedMajorId; this.requestedDegreeType=requestedDegreeType; this.explanation=explanation;
            this.status=status; this.currentMajorId=currentMajorId; this.currentDegreeType=currentDegreeType;
            this.classificationVersion=classificationVersion;
        }
    }
    public static class IdentityBatchImpact {
        public final Long batchStudentId, batchId, academicYearId, currentRelationId;
        public final String batchStatus, matchStatus, supplementWindowStatus;
        public final boolean supplementPlanned, fillingStarted;
        public IdentityBatchImpact(Long batchStudentId, Long batchId, Long academicYearId, String batchStatus,
            boolean supplementPlanned, String matchStatus, Long currentRelationId, boolean fillingStarted, String windowStatus) {
            this.batchStudentId=batchStudentId; this.batchId=batchId; this.academicYearId=academicYearId;
            this.batchStatus=batchStatus; this.supplementPlanned=supplementPlanned; this.matchStatus=matchStatus;
            this.currentRelationId=currentRelationId; this.fillingStarted=fillingStarted; this.supplementWindowStatus=windowStatus;
        }
    }
    private static class RelationRow {
        final Long id, quotaId, teacherId;
        RelationRow(Long id, Long quotaId, Long teacherId) { this.id=id; this.quotaId=quotaId; this.teacherId=teacherId; }
    }
    private static class QuotaRow {
        final Long id; final int limit, occupiedCount;
        QuotaRow(Long id, int limit, int occupiedCount) { this.id=id; this.limit=limit; this.occupiedCount=occupiedCount; }
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws java.sql.SQLException {
        if (value == null) statement.setNull(index, Types.BIGINT);
        else statement.setLong(index, value.longValue());
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
