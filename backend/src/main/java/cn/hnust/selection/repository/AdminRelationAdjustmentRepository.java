package cn.hnust.selection.repository;

import cn.hnust.selection.vo.AdminMatchingRelationVO;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

@Repository
public class AdminRelationAdjustmentRepository {
    private final JdbcTemplate jdbc;
    public AdminRelationAdjustmentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<AdminMatchingRelationVO> list(Long batchId, int pageNo, int pageSize) {
        long offset = (long) (pageNo - 1) * pageSize;
        return jdbc.query(viewSql("WHERE relation.batch_student_id IN (SELECT id FROM batch_student WHERE batch_id = ?) " +
            "ORDER BY relation.locked_at DESC, relation.id DESC LIMIT ? OFFSET ?"),
            (rs, n) -> mapView(rs), batchId, Integer.valueOf(pageSize), Long.valueOf(offset));
    }

    public long count(Long batchId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM matching_relation relation JOIN batch_student participant " +
            "ON participant.id = relation.batch_student_id WHERE participant.batch_id = ?", Long.class, batchId);
        return count == null ? 0L : count.longValue();
    }

    public Optional<Target> lockTarget(Long relationId) {
        List<Target> rows = jdbc.query("SELECT relation.id, relation.batch_student_id, relation.academic_year_id, " +
            "relation.batch_teacher_quota_id, relation.relation_status, relation.row_version, batch.id AS batch_id, " +
            "batch.college_id, batch.batch_status, batch_student.student_id, batch_student.match_status, " +
            "batch_student.match_reason, batch_student.current_relation_id, student.major_id, student.degree_type " +
            "FROM matching_relation relation JOIN batch_student ON batch_student.id = relation.batch_student_id " +
            "JOIN selection_batch batch ON batch.id = batch_student.batch_id " +
            "JOIN student ON student.id = batch_student.student_id WHERE relation.id = ? FOR UPDATE",
            (rs, n) -> {
                long current = rs.getLong("current_relation_id"); Long currentId = rs.wasNull() ? null : Long.valueOf(current);
                return new Target(rs.getLong("id"), rs.getLong("batch_student_id"), rs.getLong("academic_year_id"),
                    rs.getLong("batch_teacher_quota_id"), rs.getString("relation_status"), rs.getLong("row_version"),
                    rs.getLong("batch_id"), rs.getLong("college_id"), rs.getString("batch_status"),
                    rs.getLong("student_id"), rs.getString("match_status"), rs.getString("match_reason"), currentId,
                    rs.getLong("major_id"), rs.getString("degree_type"));
            }, relationId);
        return rows.isEmpty() ? Optional.<Target>empty() : Optional.of(rows.get(0));
    }

    /** Lock the old and new quota rows in one deterministic ID order for reassignments. */
    public Map<Long, QuotaAccount> lockQuotaAccounts(Long originalQuotaId, Long replacementQuotaId) {
        TreeSet<Long> ids = new TreeSet<Long>();
        ids.add(originalQuotaId);
        if (replacementQuotaId != null) ids.add(replacementQuotaId);
        List<Long> ordered = new ArrayList<Long>(ids);
        String sql = "SELECT id, batch_id, teacher_id, quota_limit, occupied_count FROM batch_teacher_quota WHERE id IN (" +
            placeholders(ordered.size()) + ") ORDER BY id FOR UPDATE";
        List<QuotaAccount> rows = jdbc.query(sql, (rs, n) -> new QuotaAccount(rs.getLong("id"), rs.getLong("batch_id"),
            rs.getLong("teacher_id"), rs.getInt("quota_limit"), rs.getInt("occupied_count")), ordered.toArray());
        Map<Long, QuotaAccount> result = new LinkedHashMap<Long, QuotaAccount>();
        for (QuotaAccount row : rows) result.put(row.id, row);
        return result;
    }

    /** Read the target teacher's frozen scope and quota identity. A missing row means no confirmed freeze exists. */
    public Optional<FrozenTeacherScope> findFrozenTeacherScope(Target target, Long teacherId) {
        List<FrozenTeacherScope> rows = jdbc.query("SELECT quota.id AS quota_id, scope.allowed_degree_mask, " +
            "EXISTS (SELECT 1 FROM teacher_allowed_major allowed WHERE allowed.scope_version_id = slot.scope_version_id " +
            "AND allowed.major_id = ?) AS major_allowed FROM batch_teacher_quota quota " +
            "JOIN teacher_application_scope_slot slot ON slot.batch_id = quota.batch_id AND slot.teacher_id = quota.teacher_id " +
            "JOIN teacher_application_scope_version scope ON scope.id = slot.scope_version_id " +
            "JOIN teacher ON teacher.id = quota.teacher_id JOIN selection_batch batch ON batch.id = quota.batch_id " +
            "AND batch.college_id = teacher.college_id JOIN account teacher_account ON teacher_account.id = teacher.account_id " +
            "JOIN teacher_public_profile_version profile ON profile.id = teacher.current_public_profile_version_id " +
            "WHERE quota.batch_id = ? AND quota.teacher_id = ? AND slot.frozen_at IS NOT NULL " +
            "AND scope.frozen_at IS NOT NULL AND teacher_account.account_status = 'ACTIVE' " +
            "AND profile.published_at IS NOT NULL",
            (rs, n) -> new FrozenTeacherScope(rs.getLong("quota_id"), rs.getInt("allowed_degree_mask"), rs.getBoolean("major_allowed")),
            target.majorId, target.batchId, teacherId);
        return rows.isEmpty() ? Optional.<FrozenTeacherScope>empty() : Optional.of(rows.get(0));
    }

    private String placeholders(int count) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < count; i++) { if (i > 0) value.append(','); value.append('?'); }
        return value.toString();
    }

    public Optional<AdminMatchingRelationVO> findView(Long relationId) {
        List<AdminMatchingRelationVO> rows = jdbc.query(viewSql("WHERE relation.id = ?"), (rs, n) -> mapView(rs), relationId);
        return rows.isEmpty() ? Optional.<AdminMatchingRelationVO>empty() : Optional.of(rows.get(0));
    }

    private String viewSql(String where) {
        return "SELECT relation.id AS relation_id, batch.id AS batch_id, batch.college_id, batch.batch_status, " +
            "relation.relation_status, relation.row_version, relation.locked_at, batch_student.match_reason, batch_student.student_id, " +
            "student.student_no, student.full_name AS student_name, major.name AS major_name, quota.teacher_id, " +
            "teacher.employee_no AS teacher_employee_no, teacher.full_name AS teacher_name " +
            "FROM matching_relation relation JOIN batch_student ON batch_student.id = relation.batch_student_id " +
            "JOIN selection_batch batch ON batch.id = batch_student.batch_id JOIN student ON student.id = batch_student.student_id " +
            "LEFT JOIN major ON major.id = student.major_id JOIN batch_teacher_quota quota ON quota.id = relation.batch_teacher_quota_id " +
            "JOIN teacher ON teacher.id = quota.teacher_id " + where;
    }

    private AdminMatchingRelationVO mapView(java.sql.ResultSet rs) throws java.sql.SQLException {
        AdminMatchingRelationVO value = new AdminMatchingRelationVO();
        value.setRelationId(rs.getLong("relation_id")); value.setBatchId(rs.getLong("batch_id"));
        value.setCollegeId(rs.getLong("college_id")); value.setBatchStatus(rs.getString("batch_status"));
        value.setRelationStatus(rs.getString("relation_status")); value.setRowVersion(Long.valueOf(rs.getLong("row_version")));
        value.setLockedAt(rs.getTimestamp("locked_at").toInstant().toString()); value.setStudentId(rs.getLong("student_id"));
        value.setStudentNo(rs.getString("student_no")); value.setStudentName(rs.getString("student_name"));
        value.setMatchReason(rs.getString("match_reason"));
        value.setMajorName(rs.getString("major_name")); value.setTeacherId(rs.getLong("teacher_id"));
        value.setTeacherEmployeeNo(rs.getString("teacher_employee_no")); value.setTeacherName(rs.getString("teacher_name"));
        value.setEtag(etag(value.getRelationId(), value.getRowVersion().longValue())); return value;
    }

    public Optional<Operation> findOperation(Long actorId, String key) {
        try {
            return Optional.of(jdbc.queryForObject("SELECT id, request_fingerprint, result_code FROM business_operation " +
                "WHERE actor_account_id = ? AND action_code = 'RELATION_ADJUSTMENT' AND request_id = ?",
                (rs, n) -> new Operation(rs.getLong("id"), rs.getString("request_fingerprint"), rs.getString("result_code")), actorId, key));
        } catch (EmptyResultDataAccessException ex) { return Optional.empty(); }
    }

    public Long insertOperation(Long actorId, Target target, String key, String fingerprint) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("INSERT INTO business_operation(actor_account_id, actor_kind, action_code, " +
                "college_id, batch_id, request_id, request_fingerprint, result_code, started_at) " +
                "VALUES (?, 'ADMIN', 'RELATION_ADJUSTMENT', ?, ?, ?, ?, 'IN_PROGRESS', UTC_TIMESTAMP(3))", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, actorId); ps.setLong(2, target.collegeId); ps.setLong(3, target.batchId);
            ps.setString(4, key); ps.setString(5, fingerprint); return ps;
        }, holder);
        Number value = holder.getKey(); if (value == null) throw new IllegalStateException("Missing relation operation ID");
        return Long.valueOf(value.longValue());
    }

    public boolean decrementQuota(QuotaAccount quota) {
        return jdbc.update("UPDATE batch_teacher_quota SET occupied_count = occupied_count - 1, row_version = row_version + 1 " +
            "WHERE id = ? AND occupied_count = ? AND occupied_count > 0", quota.id, Integer.valueOf(quota.occupiedCount)) == 1;
    }
    public boolean incrementQuota(QuotaAccount quota) {
        return jdbc.update("UPDATE batch_teacher_quota SET occupied_count = occupied_count + 1, row_version = row_version + 1 " +
            "WHERE id = ? AND occupied_count = ? AND occupied_count < quota_limit", quota.id, Integer.valueOf(quota.occupiedCount)) == 1;
    }
    public boolean revokeRelation(Target target) {
        return jdbc.update("UPDATE matching_relation SET relation_status = 'REVOKED', revoked_at = UTC_TIMESTAMP(3), " +
            "row_version = row_version + 1 WHERE id = ? AND relation_status = 'LOCKED' AND row_version = ?",
            target.relationId, Long.valueOf(target.rowVersion)) == 1;
    }
    public int releaseYearSlot(Target target) {
        return jdbc.update("DELETE FROM student_year_match_slot WHERE student_id = ? AND academic_year_id = ? AND relation_id = ?",
            target.studentId, target.academicYearId, target.relationId);
    }
    public boolean markUnmatched(Target target) {
        return jdbc.update("UPDATE batch_student SET current_relation_id = NULL, match_status = 'UNMATCHED', " +
            "match_reason = 'RELATION_REVOKED', match_changed_at = UTC_TIMESTAMP(3), row_version = row_version + 1 " +
            "WHERE id = ? AND current_relation_id = ? AND match_status = 'MATCHED'", target.batchStudentId, target.relationId) == 1;
    }
    public boolean restoreRelation(Target target) {
        return jdbc.update("UPDATE matching_relation SET relation_status = 'LOCKED', revoked_at = NULL, " +
            "row_version = row_version + 1 WHERE id = ? AND relation_status = 'REVOKED' AND row_version = ?",
            target.relationId, Long.valueOf(target.rowVersion)) == 1;
    }
    public void insertYearSlot(Target target) {
        jdbc.update("INSERT INTO student_year_match_slot(student_id, academic_year_id, relation_id, claimed_at) " +
            "VALUES (?, ?, ?, UTC_TIMESTAMP(3))", target.studentId, target.academicYearId, target.relationId);
    }
    public int replaceYearSlot(Target target, Long newRelationId) {
        return jdbc.update("UPDATE student_year_match_slot SET relation_id = ?, claimed_at = UTC_TIMESTAMP(3) " +
            "WHERE student_id = ? AND academic_year_id = ? AND relation_id = ?", newRelationId, target.studentId,
            target.academicYearId, target.relationId);
    }
    public boolean markMatchedFromRevoke(Target target) {
        return jdbc.update("UPDATE batch_student SET current_relation_id = ?, match_status = 'MATCHED', match_reason = NULL, " +
            "match_changed_at = UTC_TIMESTAMP(3), row_version = row_version + 1 WHERE id = ? AND current_relation_id IS NULL " +
            "AND match_status = 'UNMATCHED' AND match_reason = 'RELATION_REVOKED'", target.relationId, target.batchStudentId) == 1;
    }
    public boolean replaceCurrentRelation(Target target, Long newRelationId) {
        return jdbc.update("UPDATE batch_student SET current_relation_id = ?, match_reason = NULL, " +
            "match_changed_at = UTC_TIMESTAMP(3), row_version = row_version + 1 WHERE id = ? AND current_relation_id = ? " +
            "AND match_status = 'MATCHED'", newRelationId, target.batchStudentId, target.relationId) == 1;
    }
    public Long insertAdjustment(Target target, String type, Long actorId, Long operationId, String reason, String comment,
        Long newTeacherId, Long newRelationId) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("INSERT INTO relation_adjustment(batch_id, student_id, adjustment_type, " +
                "old_relation_id, new_relation_id, old_teacher_id, new_teacher_id, reason, approval_comment, actor_account_id, occurred_at, business_operation_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(3), ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, target.batchId); ps.setLong(2, target.studentId); ps.setString(3, type);
            ps.setLong(4, target.relationId);
            if (newRelationId == null) ps.setNull(5, java.sql.Types.BIGINT); else ps.setLong(5, newRelationId.longValue());
            ps.setLong(6, target.teacherId);
            if (newTeacherId == null) ps.setNull(7, java.sql.Types.BIGINT); else ps.setLong(7, newTeacherId.longValue());
            ps.setString(8, reason); ps.setString(9, comment); ps.setLong(10, actorId); ps.setLong(11, operationId); return ps;
        }, holder);
        Number value = holder.getKey(); if (value == null) throw new IllegalStateException("Missing relation adjustment ID");
        return Long.valueOf(value.longValue());
    }
    public void setAdjustmentNewRelation(Long adjustmentId, Long relationId) {
        jdbc.update("UPDATE relation_adjustment SET new_relation_id = ? WHERE id = ?", relationId, adjustmentId);
    }
    public Long insertReplacementRelation(Target target, QuotaAccount newQuota, Long operationId, Long adjustmentId) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("INSERT INTO matching_relation(batch_student_id, batch_teacher_quota_id, " +
                "academic_year_id, relation_status, source_type, source_id, created_by_operation_id, original_relation_id, locked_at) " +
                "VALUES (?, ?, ?, 'LOCKED', 'RELATION_ADJUSTMENT', ?, ?, ?, UTC_TIMESTAMP(3))", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, target.batchStudentId); ps.setLong(2, newQuota.id); ps.setLong(3, target.academicYearId);
            ps.setLong(4, adjustmentId); ps.setLong(5, operationId); ps.setLong(6, target.relationId); return ps;
        }, holder);
        Number value = holder.getKey(); if (value == null) throw new IllegalStateException("Missing replacement relation ID");
        return Long.valueOf(value.longValue());
    }
    public void insertMatchEvent(Target target, Long operationId, Long adjustmentId, String oldStatus, String newStatus, String reasonCode) {
        jdbc.update("INSERT INTO student_match_event(batch_student_id, old_status, new_status, reason_code, source_type, source_id, " +
            "occurred_at, business_operation_id) VALUES (?, ?, ?, ?, 'RELATION_ADJUSTMENT', ?, UTC_TIMESTAMP(3), ?)",
            target.batchStudentId, oldStatus, newStatus, reasonCode, adjustmentId, operationId);
    }
    public void insertQuotaLedger(Target target, QuotaAccount quota, String changeType, int delta, Long relationId,
        Long actorId, Long operationId, String reason) {
        jdbc.update("INSERT INTO quota_ledger(batch_teacher_quota_id, change_type, limit_before, limit_after, occupied_before, " +
            "occupied_after, delta, relation_id, business_operation_id, actor_account_id, reason, occurred_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(3))", quota.id, changeType,
            Integer.valueOf(quota.quotaLimit), Integer.valueOf(quota.quotaLimit), Integer.valueOf(quota.occupiedCount),
            Integer.valueOf(quota.occupiedCount + delta), Integer.valueOf(delta), relationId, operationId, actorId, reason);
    }
    public void insertAudit(Target target, Long actorId, Long operationId, String actionCode, String before, String after, String reason, String comment) {
        jdbc.update("INSERT INTO audit_event(actor_account_id, actor_kind, actor_role, scope_basis, object_type, object_id, " +
            "action_code, before_values_text, after_values_text, reason, approval_comment, occurred_at, business_operation_id) " +
            "VALUES (?, 'ADMIN', 'ADMIN', ?, 'MATCHING_RELATION', ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(3), ?)",
            actorId, "BATCH_MANAGER;college=" + target.collegeId + ";batch=" + target.batchId, target.relationId, actionCode,
            before, after, reason, comment, operationId);
    }
    public void notifyAdjustment(Target target, String type, Long newTeacherId, Long operationId) {
        String body = "REVOKE".equals(type) ? "管理员已撤销当前师生关系，请查看互选进度。" :
            ("RESTORE".equals(type) ? "管理员已恢复师生关系，请查看互选进度。" : "管理员已调整师生关系，请查看互选进度。");
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("INSERT INTO site_notice(sender_account_id, scope_college_id, batch_id, " +
                "notice_type, title, body, source_operation_id, created_at, visible_at) " +
                "VALUES (NULL, ?, ?, 'RELATION_ADJUSTMENT', '师生关系已调整', ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, target.collegeId); ps.setLong(2, target.batchId); ps.setString(3, body); ps.setLong(4, operationId); return ps;
        }, holder);
        Number notice = holder.getKey(); if (notice == null) throw new IllegalStateException("Missing relation notice ID");
        jdbc.update("INSERT IGNORE INTO notice_recipient(notice_id, account_id, recipient_status, delivered_at) " +
            "SELECT ?, account_id, 'DELIVERED', UTC_TIMESTAMP(3) FROM student WHERE id = ?", notice.longValue(), target.studentId);
        jdbc.update("INSERT IGNORE INTO notice_recipient(notice_id, account_id, recipient_status, delivered_at) " +
            "SELECT ?, teacher.account_id, 'DELIVERED', UTC_TIMESTAMP(3) FROM teacher WHERE id = ?", notice.longValue(), target.teacherId);
        if (newTeacherId != null && !newTeacherId.equals(target.teacherId)) {
            jdbc.update("INSERT IGNORE INTO notice_recipient(notice_id, account_id, recipient_status, delivered_at) " +
                "SELECT ?, teacher.account_id, 'DELIVERED', UTC_TIMESTAMP(3) FROM teacher WHERE id = ?", notice.longValue(), newTeacherId);
        }
    }
    public void completeOperation(Long operationId) {
        jdbc.update("UPDATE business_operation SET result_code = 'OK', completed_at = UTC_TIMESTAMP(3) WHERE id = ?", operationId);
    }

    public static String etag(Long id, long version) { return "relation-" + id + "-" + version; }
    public static class Target {
        public final Long relationId, batchStudentId, academicYearId, quotaId, batchId, collegeId, studentId, currentRelationId, majorId;
        public final String relationStatus, batchStatus, matchStatus, matchReason, degreeType; public final long rowVersion;
        public Long teacherId; public int quotaLimit, occupiedCount;
        Target(Long relationId, Long batchStudentId, Long academicYearId, Long quotaId, String relationStatus, long rowVersion,
            Long batchId, Long collegeId, String batchStatus, Long studentId, String matchStatus, String matchReason,
            Long currentRelationId, Long majorId, String degreeType) {
            this.relationId=relationId; this.batchStudentId=batchStudentId; this.academicYearId=academicYearId;
            this.quotaId=quotaId; this.relationStatus=relationStatus; this.rowVersion=rowVersion; this.batchId=batchId;
            this.collegeId=collegeId; this.batchStatus=batchStatus; this.studentId=studentId; this.matchStatus=matchStatus;
            this.matchReason=matchReason; this.currentRelationId=currentRelationId; this.majorId=majorId; this.degreeType=degreeType;
        }
    }
    public static class QuotaAccount {
        public final Long id, batchId, teacherId; public final int quotaLimit, occupiedCount;
        QuotaAccount(Long id, Long batchId, Long teacherId, int quotaLimit, int occupiedCount) {
            this.id=id; this.batchId=batchId; this.teacherId=teacherId; this.quotaLimit=quotaLimit; this.occupiedCount=occupiedCount;
        }
    }
    public static class FrozenTeacherScope {
        public final Long quotaId; public final int allowedDegreeMask; public final boolean majorAllowed;
        FrozenTeacherScope(Long quotaId, int allowedDegreeMask, boolean majorAllowed) {
            this.quotaId=quotaId; this.allowedDegreeMask=allowedDegreeMask; this.majorAllowed=majorAllowed;
        }
    }
    public static class Operation {
        public final Long id; public final String fingerprint, resultCode;
        Operation(Long id, String fingerprint, String resultCode) { this.id=id; this.fingerprint=fingerprint; this.resultCode=resultCode; }
    }
}
