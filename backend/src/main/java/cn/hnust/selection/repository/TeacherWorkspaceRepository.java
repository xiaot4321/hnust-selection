package cn.hnust.selection.repository;

import cn.hnust.selection.request.SendTeacherApplicationNoticeRequest;
import cn.hnust.selection.vo.TeacherApplicationVO;
import cn.hnust.selection.vo.TeacherBatchSummaryVO;
import cn.hnust.selection.vo.TeacherDecisionVO;
import cn.hnust.selection.vo.TeacherProfileVO;
import cn.hnust.selection.vo.TeacherSupplementApplicationVO;
import cn.hnust.selection.vo.TeacherBulkOperationVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 导师工作台 JDBC 访问层。调用方负责角色、窗口、状态及事务规则。 */
@Repository
public class TeacherWorkspaceRepository {
    private final JdbcTemplate jdbc;
    public TeacherWorkspaceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<TeacherProfileVO> findProfile(Long teacherId) {
        String sql = "SELECT teacher.id, teacher.employee_no, teacher.full_name, " +
            "published.research_directions AS published_directions, published.biography AS published_biography, " +
            "latest.review_status, latest.review_comment, latest.research_directions AS submitted_directions, " +
            "latest.biography AS submitted_biography, COALESCE(latest.version_no, 0) AS version_no, " +
            "COALESCE(latest.row_version, 0) AS profile_row_version " +
            "FROM teacher LEFT JOIN teacher_public_profile_version published " +
            "ON published.id = teacher.current_public_profile_version_id AND published.published_at IS NOT NULL " +
            "LEFT JOIN teacher_public_profile_version latest ON latest.teacher_id = teacher.id " +
            "AND latest.version_no = (SELECT MAX(candidate.version_no) FROM teacher_public_profile_version candidate " +
            "WHERE candidate.teacher_id = teacher.id) WHERE teacher.id = ?";
        List<TeacherProfileVO> rows = jdbc.query(sql, (rs, n) -> {
            TeacherProfileVO value = new TeacherProfileVO();
            value.setTeacherId(rs.getLong("id")); value.setEmployeeNo(rs.getString("employee_no"));
            value.setFullName(rs.getString("full_name"));
            value.setPublishedResearchDirections(rs.getString("published_directions"));
            value.setPublishedBiography(rs.getString("published_biography"));
            value.setReviewStatus(rs.getString("review_status")); value.setReviewComment(rs.getString("review_comment"));
            value.setSubmittedResearchDirections(rs.getString("submitted_directions"));
            value.setSubmittedBiography(rs.getString("submitted_biography")); value.setVersionNo(rs.getInt("version_no"));
            value.setEtag(profileEtag(value.getTeacherId(), value.getVersionNo(), rs.getLong("profile_row_version"))); return value;
        }, teacherId);
        return rows.isEmpty() ? Optional.<TeacherProfileVO>empty() : Optional.of(rows.get(0));
    }

    public boolean lockTeacher(Long teacherId, Long accountId) {
        List<Long> rows = jdbc.query("SELECT id FROM teacher WHERE id = ? AND account_id = ? FOR UPDATE",
            (rs, n) -> rs.getLong(1), teacherId, accountId);
        return !rows.isEmpty();
    }

    public void updateDraftProfile(Long teacherId, int versionNo, String directions, String biography) {
        jdbc.update("UPDATE teacher_public_profile_version SET research_directions = ?, biography = ?, " +
            "review_status = 'PENDING_REVIEW', submitted_at = UTC_TIMESTAMP(3), review_comment = NULL, " +
            "row_version = row_version + 1 WHERE teacher_id = ? AND version_no = ? AND review_status = 'DRAFT'",
            directions, biography, teacherId, versionNo);
    }

    public void insertProfileSubmission(Long teacherId, int versionNo, String directions, String biography, Long accountId) {
        jdbc.update("INSERT INTO teacher_public_profile_version (teacher_id, version_no, research_directions, biography, " +
            "review_status, submitted_at) VALUES (?, ?, ?, ?, 'PENDING_REVIEW', UTC_TIMESTAMP(3))",
            teacherId, versionNo, directions, biography);
    }

    public List<TeacherApplicationVO> listRoundApplications(Long teacherId, Long batchId, int roundNo, int pageNo, int pageSize) {
        String sql = "SELECT application.id AS application_id, batch.id AS batch_id, batch.name AS batch_name, ? AS round_no, " +
            "item.preference_order, snapshot.student_no, snapshot.full_name, snapshot.major_name_snapshot AS major_name, " +
            "snapshot.degree_type, snapshot.biography, snapshot.resume_file_id, application.application_status, " +
            "DATE_FORMAT(application.entered_review_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS entered_review_at, " +
            "DATE_FORMAT(application.decided_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS processed_at " +
            "FROM round_application application JOIN batch_stage stage ON stage.id = application.stage_id " +
            "JOIN selection_batch batch ON batch.id = stage.batch_id JOIN preference_item item ON item.id = application.preference_item_id " +
            "JOIN application_profile_snapshot snapshot ON snapshot.id = application.active_snapshot_id " +
            "WHERE application.teacher_id = ? AND batch.id = ? AND stage.stage_code = ? " +
            "ORDER BY application.entered_review_at, snapshot.student_no, application.id LIMIT ? OFFSET ?";
        long offset = ((long) pageNo - 1L) * pageSize;
        return jdbc.query(sql, (rs, n) -> {
            TeacherApplicationVO value = new TeacherApplicationVO();
            value.setApplicationId(rs.getLong("application_id")); value.setBatchId(rs.getLong("batch_id"));
            value.setBatchName(rs.getString("batch_name")); value.setRoundNo(rs.getInt("round_no"));
            value.setPreferenceOrder(rs.getInt("preference_order")); value.setStudentNo(rs.getString("student_no"));
            value.setFullName(rs.getString("full_name")); value.setMajorName(rs.getString("major_name"));
            value.setDegreeType(rs.getString("degree_type")); value.setBiography(rs.getString("biography"));
            long fileId = rs.getLong("resume_file_id"); value.setResumeFileId(rs.wasNull() ? null : Long.valueOf(fileId));
            value.setStatus(rs.getString("application_status")); value.setEnteredReviewAt(rs.getString("entered_review_at"));
            value.setProcessedAt(rs.getString("processed_at")); return value;
        }, Integer.valueOf(roundNo), teacherId, batchId, "ROUND_" + roundNo, Integer.valueOf(pageSize), Long.valueOf(offset));
    }

    public long countRoundApplications(Long teacherId, Long batchId, int roundNo) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM round_application application " +
            "JOIN batch_stage stage ON stage.id = application.stage_id WHERE application.teacher_id = ? " +
            "AND stage.batch_id = ? AND stage.stage_code = ?", Long.class, teacherId, batchId, "ROUND_" + roundNo);
        return count == null ? 0 : count.longValue();
    }

    public List<BulkSortItem> sortRoundBulkItems(Long teacherId, Long batchId, int roundNo, List<Long> applicationIds) {
        if (applicationIds == null || applicationIds.isEmpty()) return new ArrayList<BulkSortItem>();
        StringBuilder placeholders = marks(applicationIds.size());
        List<Object> args = new ArrayList<Object>(); args.add(teacherId); args.add(batchId); args.add("ROUND_" + roundNo); args.addAll(applicationIds);
        return jdbc.query("SELECT application.id AS application_id, application.sort_submitted_at, application.sort_student_no " +
            "FROM round_application application JOIN batch_stage stage ON stage.id = application.stage_id " +
            "WHERE application.teacher_id = ? AND stage.batch_id = ? AND stage.stage_code = ? AND application.id IN (" + placeholders + ") " +
            "ORDER BY application.sort_submitted_at, application.sort_student_no, application.id",
            (rs, n) -> new BulkSortItem(rs.getLong("application_id"), rs.getTimestamp("sort_submitted_at"), rs.getString("sort_student_no")), args.toArray());
    }

    public List<BulkSortItem> sortSupplementBulkItems(Long teacherId, Long batchId, List<Long> applicationIds) {
        if (applicationIds == null || applicationIds.isEmpty()) return new ArrayList<BulkSortItem>();
        StringBuilder placeholders = marks(applicationIds.size());
        List<Object> args = new ArrayList<Object>(); args.add(teacherId); args.add(batchId); args.addAll(applicationIds);
        return jdbc.query("SELECT application.id AS application_id, application.sort_submitted_at, application.sort_student_no " +
            "FROM supplement_application application JOIN supplement_window window ON window.id = application.supplement_window_id " +
            "WHERE application.teacher_id = ? AND window.batch_id = ? AND application.id IN (" + placeholders + ") " +
            "ORDER BY application.sort_submitted_at, application.sort_student_no, application.id",
            (rs, n) -> new BulkSortItem(rs.getLong("application_id"), rs.getTimestamp("sort_submitted_at"), rs.getString("sort_student_no")), args.toArray());
    }

    public void insertBulkItems(Long operationId, String applicationType, List<BulkSortItem> items) {
        int index = 1;
        for (BulkSortItem item : items) jdbc.update("INSERT INTO bulk_operation_item(business_operation_id, application_type, application_id, " +
            "sort_submitted_at, sort_student_no, execution_order, item_result) VALUES (?, ?, ?, ?, ?, ?, 'PENDING')",
            operationId, applicationType, item.applicationId, item.submittedAt, item.studentNo, Integer.valueOf(index++));
    }

    public void updateBulkItem(Long operationId, int order, String result, String failureReason) {
        jdbc.update("UPDATE bulk_operation_item SET item_result = ?, failure_reason = ? WHERE business_operation_id = ? AND execution_order = ?",
            result, failureReason, operationId, Integer.valueOf(order));
    }

    public void failBulkOperation(Long operationId, String reason) {
        jdbc.update("UPDATE bulk_operation_item SET item_result = 'FAILED', failure_reason = ? " +
            "WHERE business_operation_id = ? AND item_result = 'PENDING'", reason, operationId);
        jdbc.update("UPDATE business_operation SET result_code = 'FAILED', completed_at = UTC_TIMESTAMP(3), " +
            "row_version = row_version + 1 WHERE id = ? AND result_code = 'IN_PROGRESS'", operationId);
    }

    public Optional<String> findBulkOperationStatus(Long operationId, Long accountId) {
        List<String> rows = jdbc.query("SELECT result_code FROM business_operation WHERE id = ? AND actor_account_id = ? " +
            "AND action_code IN ('TEACHER_ROUND_BATCH_DECISIONS','TEACHER_SUPPLEMENT_BATCH_DECISIONS')", (rs, n) -> rs.getString(1), operationId, accountId);
        return rows.isEmpty() ? Optional.<String>empty() : Optional.of(rows.get(0));
    }

    public List<TeacherBulkOperationVO.Item> listBulkOperationItems(Long operationId) {
        return jdbc.query("SELECT item.application_id, item.execution_order, item.item_result, item.failure_reason, relation.id AS relation_id " +
            "FROM bulk_operation_item item LEFT JOIN matching_relation relation ON relation.source_id = item.application_id " +
            "AND relation.relation_status = 'LOCKED' AND ((item.application_type = 'ROUND_APPLICATION' " +
            "AND relation.source_type IN ('ROUND_1','ROUND_2','ROUND_3')) OR (item.application_type = 'SUPPLEMENT_APPLICATION' " +
            "AND relation.source_type = 'SUPPLEMENT')) WHERE item.business_operation_id = ? ORDER BY item.execution_order", (rs, n) -> {
            TeacherBulkOperationVO.Item item = new TeacherBulkOperationVO.Item(); item.setApplicationId(rs.getLong("application_id"));
            item.setExecutionOrder(rs.getInt("execution_order")); item.setItemResult(rs.getString("item_result"));
            item.setCode(rs.getString("failure_reason")); long relationId = rs.getLong("relation_id");
            item.setRelationId(rs.wasNull() ? null : Long.valueOf(relationId)); return item;
        }, operationId);
    }

    private static StringBuilder marks(int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) { if (i > 0) result.append(','); result.append('?'); }
        return result;
    }

    public boolean ownsBatch(Long teacherId, Long batchId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM batch_teacher_quota WHERE teacher_id = ? AND batch_id = ?",
            Integer.class, teacherId, batchId);
        return count != null && count.intValue() > 0;
    }

    /** 与批次调度的首锁顺序一致，避免处理申请时反向等待批次锁。 */
    public boolean lockBatchForTeacher(Long teacherId, Long batchId) {
        List<Long> rows = jdbc.query("SELECT batch.id FROM selection_batch batch JOIN batch_teacher_quota quota " +
            "ON quota.batch_id = batch.id AND quota.teacher_id = ? WHERE batch.id = ? FOR UPDATE",
            (rs, n) -> rs.getLong(1), teacherId, batchId);
        return !rows.isEmpty();
    }

    public Optional<StageContext> lockRoundStage(Long teacherId, Long batchId, int roundNo) {
        List<StageContext> rows = jdbc.query("SELECT batch.batch_status, batch.current_stage_id, stage.id AS stage_id, " +
            "stage.stage_status, stage.effective_start_at, stage.effective_end_at, stage.execution_cycle " +
            "FROM selection_batch batch JOIN batch_stage stage ON stage.batch_id = batch.id AND stage.stage_code = ? " +
            "JOIN batch_teacher_quota quota ON quota.batch_id = batch.id AND quota.teacher_id = ? " +
            "WHERE batch.id = ? FOR UPDATE", (rs, n) -> new StageContext(rs.getString("batch_status"),
                rs.getLong("current_stage_id"), rs.getLong("stage_id"), rs.getString("stage_status"),
                rs.getTimestamp("effective_start_at"), rs.getTimestamp("effective_end_at"), rs.getInt("execution_cycle")),
            "ROUND_" + roundNo, teacherId, batchId);
        return rows.isEmpty() ? Optional.<StageContext>empty() : Optional.of(rows.get(0));
    }

    public Optional<StageContext> findRoundStage(Long teacherId, Long batchId, int roundNo) {
        List<StageContext> rows = jdbc.query("SELECT batch.batch_status, batch.current_stage_id, stage.id AS stage_id, " +
            "stage.stage_status, stage.effective_start_at, stage.effective_end_at, stage.execution_cycle " +
            "FROM selection_batch batch JOIN batch_stage stage ON stage.batch_id = batch.id AND stage.stage_code = ? " +
            "JOIN batch_teacher_quota quota ON quota.batch_id = batch.id AND quota.teacher_id = ? WHERE batch.id = ?",
            (rs, n) -> new StageContext(rs.getString("batch_status"), nullableLong(rs, "current_stage_id"),
                rs.getLong("stage_id"), rs.getString("stage_status"), rs.getTimestamp("effective_start_at"),
                rs.getTimestamp("effective_end_at"), rs.getInt("execution_cycle")), "ROUND_" + roundNo, teacherId, batchId);
        return rows.isEmpty() ? Optional.<StageContext>empty() : Optional.of(rows.get(0));
    }

    public String stageStatus(Long stageId) {
        List<String> rows = jdbc.query("SELECT stage_status FROM batch_stage WHERE id = ?", (rs, n) -> rs.getString(1), stageId);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public java.sql.Timestamp databaseUtcNow() { return jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)", java.sql.Timestamp.class); }
    public Long frozenScopeVersion(Long batchId, Long teacherId) {
        List<Long> rows = jdbc.query("SELECT slot.scope_version_id FROM teacher_application_scope_slot slot " +
            "JOIN teacher_application_scope_version scope ON scope.id = slot.scope_version_id " +
            "WHERE slot.batch_id = ? AND slot.teacher_id = ? AND scope.frozen_at IS NOT NULL", (rs, n) -> rs.getLong(1), batchId, teacherId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Optional<RoundApplicationContext> lockRoundApplication(Long teacherId, Long batchId, Long applicationId) {
        String sql = "SELECT application.id AS application_id, application.batch_student_id, application.preference_item_id, " +
            "application.stage_id, application.teacher_id, application.application_status, application.execution_cycle, " +
            "stage.stage_code, stage.execution_cycle AS stage_cycle, batch.academic_year_id, batch.current_stage_id, batch.batch_status, " +
            "participant.student_id, participant.match_status, participant.current_relation_id, student.account_id, " +
            "student.student_no, student.major_id, student.degree_type, student.classification_version, " +
            "quota.id AS quota_id, quota.quota_limit, quota.occupied_count, item.scope_version_id, item.major_id AS applied_major_id, " +
            "item.degree_type AS applied_degree_type " +
            "FROM round_application application JOIN batch_stage stage ON stage.id = application.stage_id " +
            "JOIN selection_batch batch ON batch.id = stage.batch_id " +
            "JOIN batch_student participant ON participant.id = application.batch_student_id " +
            "JOIN student ON student.id = participant.student_id " +
            "JOIN preference_item item ON item.id = application.preference_item_id " +
            "JOIN batch_teacher_quota quota ON quota.batch_id = batch.id AND quota.teacher_id = application.teacher_id " +
            "WHERE application.id = ? AND application.teacher_id = ? AND batch.id = ? FOR UPDATE";
        List<RoundApplicationContext> rows = jdbc.query(sql, (rs, n) -> new RoundApplicationContext(
            rs.getLong("application_id"), rs.getLong("batch_student_id"), rs.getLong("preference_item_id"),
            rs.getLong("stage_id"), rs.getLong("teacher_id"), rs.getString("application_status"),
            rs.getInt("execution_cycle"), rs.getString("stage_code"), rs.getInt("stage_cycle"),
            rs.getLong("academic_year_id"), rs.getLong("current_stage_id"), rs.getString("batch_status"),
            rs.getLong("student_id"), rs.getString("match_status"), nullableLong(rs, "current_relation_id"),
            rs.getLong("account_id"), rs.getString("student_no"), rs.getLong("major_id"), rs.getString("degree_type"),
            rs.getInt("classification_version"), rs.getLong("quota_id"), rs.getInt("quota_limit"),
            rs.getInt("occupied_count"), rs.getLong("scope_version_id"), rs.getLong("applied_major_id"),
            rs.getString("applied_degree_type")), applicationId, teacherId, batchId);
        return rows.isEmpty() ? Optional.<RoundApplicationContext>empty() : Optional.of(rows.get(0));
    }

    public Optional<SupplementContext> lockSupplementApplication(Long teacherId, Long batchId, Long applicationId) {
        String sql = "SELECT application.id AS application_id, application.batch_student_id, application.supplement_window_id, " +
            "application.application_status, application.teacher_id, batch.batch_status, batch.current_stage_id, " +
            "batch.academic_year_id, stage.id AS stage_id, stage.stage_status, stage.execution_cycle, " +
            "window.window_status, window.effective_start_at, window.effective_end_at, application.submitted_at, participant.student_id, " +
            "participant.match_status, participant.current_relation_id, student.account_id, student.student_no, " +
            "student.major_id, student.degree_type, student.classification_version, quota.id AS quota_id, " +
            "quota.quota_limit, quota.occupied_count, application.batch_teacher_quota_id " +
            "FROM supplement_application application JOIN supplement_window window ON window.id = application.supplement_window_id " +
            "JOIN batch_stage stage ON stage.id = window.stage_id JOIN selection_batch batch ON batch.id = window.batch_id " +
            "JOIN batch_student participant ON participant.id = application.batch_student_id " +
            "JOIN student ON student.id = participant.student_id " +
            "JOIN batch_teacher_quota quota ON quota.id = application.batch_teacher_quota_id " +
            "WHERE application.id = ? AND application.teacher_id = ? AND batch.id = ? FOR UPDATE";
        List<SupplementContext> rows = jdbc.query(sql, (rs, n) -> new SupplementContext(
            rs.getLong("application_id"), rs.getLong("batch_student_id"), rs.getLong("supplement_window_id"),
            rs.getString("application_status"), rs.getLong("teacher_id"), rs.getString("batch_status"),
            rs.getLong("current_stage_id"), rs.getLong("academic_year_id"), rs.getLong("stage_id"),
            rs.getString("stage_status"), rs.getInt("execution_cycle"), rs.getString("window_status"),
            rs.getTimestamp("effective_start_at"), rs.getTimestamp("effective_end_at"), rs.getTimestamp("submitted_at"),
            rs.getLong("student_id"),
            rs.getString("match_status"), nullableLong(rs, "current_relation_id"), rs.getLong("account_id"),
            rs.getString("student_no"), rs.getLong("major_id"), rs.getString("degree_type"),
            rs.getInt("classification_version"), rs.getLong("quota_id"), rs.getInt("quota_limit"),
            rs.getInt("occupied_count"), rs.getLong("batch_teacher_quota_id")), applicationId, teacherId, batchId);
        return rows.isEmpty() ? Optional.<SupplementContext>empty() : Optional.of(rows.get(0));
    }

    public boolean hadSupplementEligibilityAtSubmission(Long windowId, Long quotaId, java.sql.Timestamp submittedAt) {
        List<Long> rows = jdbc.query("SELECT eligibility.id FROM supplement_window window " +
            "JOIN selection_batch batch ON batch.id = window.batch_id " +
            "JOIN batch_teacher_quota quota ON quota.id = ? AND quota.batch_id = batch.id " +
            "JOIN teacher ON teacher.id = quota.teacher_id AND teacher.college_id = batch.college_id " +
            "JOIN annual_eligibility eligibility ON eligibility.academic_year_id = batch.academic_year_id " +
            "AND eligibility.college_id = batch.college_id AND eligibility.teacher_id = teacher.id " +
            "AND eligibility.student_id IS NULL AND eligibility.eligibility_status = 'ELIGIBLE' " +
            "AND (eligibility.valid_from IS NULL OR eligibility.valid_from <= ?) " +
            "AND (eligibility.valid_to IS NULL OR ? < eligibility.valid_to) " +
            "WHERE window.id = ? FOR UPDATE", (rs, n) -> rs.getLong(1),
            quotaId, submittedAt, submittedAt, windowId);
        return !rows.isEmpty();
    }

    public boolean scopeAllows(Long batchId, Long teacherId, Long scopeVersionId, Long majorId, String degreeType) {
        int bit = "ACADEMIC_MASTER".equals(degreeType) ? 1 : ("PROFESSIONAL_MASTER".equals(degreeType) ? 2 : 0);
        if (bit == 0) return false;
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM teacher_application_scope_slot slot " +
            "JOIN teacher_application_scope_version scope ON scope.id = slot.scope_version_id " +
            "JOIN teacher_allowed_major allowed ON allowed.scope_version_id = scope.id " +
            "WHERE slot.batch_id = ? AND slot.teacher_id = ? AND slot.scope_version_id = ? " +
            "AND scope.frozen_at IS NOT NULL AND (scope.allowed_degree_mask & ?) <> 0 AND allowed.major_id = ?",
            Integer.class, batchId, teacherId, scopeVersionId, Integer.valueOf(bit), majorId);
        return count != null && count.intValue() > 0;
    }

    public boolean hasYearMatch(Long studentId, Long academicYearId) {
        List<Long> rows = jdbc.query("SELECT relation_id FROM student_year_match_slot " +
            "WHERE student_id = ? AND academic_year_id = ? FOR UPDATE", (rs, n) -> rs.getLong(1), studentId, academicYearId);
        return !rows.isEmpty();
    }

    public Optional<Operation> findOperation(Long actorId, String action, String requestId) {
        List<Operation> rows = jdbc.query("SELECT id, request_fingerprint, result_code FROM business_operation " +
            "WHERE actor_account_id = ? AND action_code = ? AND request_id = ? FOR UPDATE",
            (rs, n) -> new Operation(rs.getLong("id"), rs.getString("request_fingerprint"), rs.getString("result_code")),
            actorId, action, requestId);
        return rows.isEmpty() ? Optional.<Operation>empty() : Optional.of(rows.get(0));
    }

    public long insertOperation(Long accountId, Long batchId, String action, String requestId, String fingerprint) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("INSERT INTO business_operation " +
                "(actor_account_id, actor_kind, action_code, batch_id, request_id, request_fingerprint, result_code, started_at) " +
                "VALUES (?, 'TEACHER', ?, ?, ?, ?, 'IN_PROGRESS', UTC_TIMESTAMP(3))", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, accountId); ps.setString(2, action); ps.setObject(3, batchId); ps.setString(4, requestId);
            ps.setString(5, fingerprint); return ps;
        }, key);
        Number id = key.getKey(); if (id == null) throw new IllegalStateException("No operation id returned");
        return id.longValue();
    }

    public void completeOperation(Long operationId) {
        jdbc.update("UPDATE business_operation SET result_code = 'OK', completed_at = UTC_TIMESTAMP(3) " +
            "WHERE id = ? AND result_code = 'IN_PROGRESS'", operationId);
    }

    public int updateQuotaOccupied(Long quotaId, int expectedOccupied, int delta) {
        return jdbc.update("UPDATE batch_teacher_quota SET occupied_count = occupied_count + ?, row_version = row_version + 1 " +
            "WHERE id = ? AND occupied_count = ? AND occupied_count + ? <= quota_limit",
            Integer.valueOf(delta), quotaId, Integer.valueOf(expectedOccupied), Integer.valueOf(delta));
    }

    public Long insertRelation(RoundApplicationContext app, Long operationId, String sourceType, Long sourceId) {
        return insertRelation(app.batchStudentId, app.quotaId, app.academicYearId, operationId, sourceType, sourceId);
    }
    public Long insertRelation(SupplementContext app, Long operationId, String sourceType, Long sourceId) {
        return insertRelation(app.batchStudentId, app.quotaId, app.academicYearId, operationId, sourceType, sourceId);
    }
    private Long insertRelation(Long batchStudentId, Long quotaId, Long yearId, Long operationId, String sourceType, Long sourceId) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("INSERT INTO matching_relation " +
                "(batch_student_id, batch_teacher_quota_id, academic_year_id, relation_status, source_type, source_id, " +
                "created_by_operation_id, locked_at) VALUES (?, ?, ?, 'LOCKED', ?, ?, ?, UTC_TIMESTAMP(3))", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchStudentId); ps.setLong(2, quotaId); ps.setLong(3, yearId); ps.setString(4, sourceType);
            ps.setLong(5, sourceId); ps.setLong(6, operationId); return ps;
        }, key);
        Number id = key.getKey(); if (id == null) throw new IllegalStateException("No matching relation id returned");
        return id.longValue();
    }

    public void applyMatch(Long studentId, Long yearId, Long batchStudentId, Long relationId,
                           String oldStatus, String sourceType, Long sourceId, Long operationId, Integer roundNo, Integer preferenceOrder) {
        jdbc.update("INSERT INTO student_year_match_slot(student_id, academic_year_id, relation_id, claimed_at) " +
            "VALUES (?, ?, ?, UTC_TIMESTAMP(3))", studentId, yearId, relationId);
        jdbc.update("UPDATE batch_student SET current_relation_id = ?, match_status = 'MATCHED', match_reason = NULL, " +
            "match_changed_at = UTC_TIMESTAMP(3), row_version = row_version + 1 WHERE id = ?", relationId, batchStudentId);
        jdbc.update("INSERT INTO student_match_event(batch_student_id, old_status, new_status, reason_code, actual_round, " +
            "actual_preference_order, source_type, source_id, occurred_at, business_operation_id) " +
            "VALUES (?, ?, 'MATCHED', ?, ?, ?, ?, ?, UTC_TIMESTAMP(3), ?)", batchStudentId,
            oldStatus, "ADMITTED", roundNo, preferenceOrder, sourceType, sourceId, operationId);
    }

    public void decideRound(Long applicationId, String decision, String closeReason) {
        jdbc.update("UPDATE round_application SET application_status = ?, close_reason = ?, decided_at = UTC_TIMESTAMP(3), " +
            "row_version = row_version + 1 WHERE id = ? AND application_status = 'IN_REVIEW'",
            "ADMIT".equals(decision) ? "ADMITTED" : "NOT_ADMITTED", "ADMIT".equals(decision) ? null : closeReason, applicationId);
    }
    public void decideSupplement(Long applicationId, String decision, Long accountId, String closeReason) {
        jdbc.update("UPDATE supplement_application SET application_status = ?, close_reason = ?, decided_by = ?, decided_at = UTC_TIMESTAMP(3), " +
            "row_version = row_version + 1 WHERE id = ? AND application_status = 'IN_REVIEW'",
            "ADMIT".equals(decision) ? "ADMITTED" : "REJECTED", "ADMIT".equals(decision) ? null : closeReason, accountId, applicationId);
    }
    public void releasePendingSupplementSlot(Long studentId) {
        jdbc.update("DELETE FROM student_pending_supplement_slot WHERE student_id = ?", studentId);
    }

    public void insertApplicationEvent(String objectType, Long applicationId, int cycle, String decision, String closeReason, Long accountId, Long operationId) {
        String newStatus = "ADMIT".equals(decision) ? "ADMITTED" : ("ROUND_APPLICATION".equals(objectType) ? "NOT_ADMITTED" : "REJECTED");
        jdbc.update("INSERT INTO application_event(object_type, object_id, execution_cycle, action_code, old_status, new_status, close_reason, " +
            "actor_kind, actor_account_id, occurred_at, business_operation_id) VALUES (?, ?, ?, ?, 'IN_REVIEW', ?, ?, 'TEACHER', ?, " +
            "UTC_TIMESTAMP(3), ?)", objectType, applicationId, Integer.valueOf(cycle), "TEACHER_DECISION", newStatus, closeReason, accountId, operationId);
    }
    public void insertQuotaLedger(Long quotaId, int limit, int occupiedBefore, int occupiedAfter,
                                  Long relationId, Long operationId, Long accountId) {
        jdbc.update("INSERT INTO quota_ledger(batch_teacher_quota_id, change_type, limit_before, limit_after, " +
            "occupied_before, occupied_after, delta, relation_id, business_operation_id, actor_account_id, occurred_at) " +
            "VALUES (?, 'RELATION_LOCKED', ?, ?, ?, ?, 1, ?, ?, ?, UTC_TIMESTAMP(3))", quotaId,
            Integer.valueOf(limit), Integer.valueOf(limit), Integer.valueOf(occupiedBefore), Integer.valueOf(occupiedAfter),
            relationId, operationId, accountId);
    }

    public void insertNotice(Long accountId, Long batchId, Long operationId, String type, String title, String body) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("INSERT INTO site_notice(sender_account_id, batch_id, notice_type, " +
                "title, body, source_operation_id, created_at, visible_at) VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                Statement.RETURN_GENERATED_KEYS);
            ps.setObject(1, accountId); ps.setObject(2, batchId); ps.setString(3, type); ps.setString(4, title);
            ps.setString(5, body); ps.setObject(6, operationId); return ps;
        }, key);
        Number id = key.getKey(); if (id == null) throw new IllegalStateException("No notice id returned");
        // Decision notifications are delivered to one student; direct messages use the bulk overload below.
    }
    public Long insertNoticeRecord(Long accountId, Long batchId, Long operationId, String type, String title, String body) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("INSERT INTO site_notice(sender_account_id, batch_id, notice_type, " +
                "title, body, source_operation_id, created_at, visible_at) VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                Statement.RETURN_GENERATED_KEYS);
            ps.setObject(1, accountId); ps.setObject(2, batchId); ps.setString(3, type); ps.setString(4, title);
            ps.setString(5, body); ps.setObject(6, operationId); return ps;
        }, key);
        Number id = key.getKey(); if (id == null) throw new IllegalStateException("No notice id returned");
        return Long.valueOf(id.longValue());
    }
    public int addNoticeRecipient(Long noticeId, Long recipientAccountId) {
        return jdbc.update("INSERT INTO notice_recipient(notice_id, account_id, recipient_status, delivered_at) " +
            "VALUES (?, ?, 'DELIVERED', UTC_TIMESTAMP(3))", noticeId, recipientAccountId);
    }
    public void insertAudit(Long accountId, Long batchId, String type, Long objectId, String action,
                            String before, String after, Long operationId) {
        jdbc.update("INSERT INTO audit_event(actor_account_id, actor_kind, actor_role, scope_basis, object_type, object_id, " +
            "action_code, before_values_text, after_values_text, occurred_at, business_operation_id) " +
            "VALUES (?, 'TEACHER', 'TEACHER', ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(3), ?)", accountId,
            "batchId=" + batchId, type, objectId, action, before, after, operationId);
    }

    public List<TeacherSupplementApplicationVO> listSupplementApplications(Long teacherId, Long batchId, int pageNo, int pageSize) {
        long offset = ((long) pageNo - 1L) * pageSize;
        String sql = "SELECT application.id, batch.id AS batch_id, batch.name AS batch_name, snapshot.student_no, snapshot.full_name, " +
            "snapshot.major_name_snapshot AS major_name, snapshot.degree_type, snapshot.biography, snapshot.resume_file_id, " +
            "application.application_status, DATE_FORMAT(application.submitted_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS submitted_at, " +
            "DATE_FORMAT(application.decided_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS processed_at " +
            "FROM supplement_application application JOIN supplement_window window ON window.id = application.supplement_window_id " +
            "JOIN selection_batch batch ON batch.id = window.batch_id JOIN application_profile_snapshot snapshot " +
            "ON snapshot.id = application.active_snapshot_id WHERE application.teacher_id = ? AND batch.id = ? " +
            "ORDER BY application.submitted_at, snapshot.student_no, application.id LIMIT ? OFFSET ?";
        return jdbc.query(sql, (rs, n) -> {
            TeacherSupplementApplicationVO value = new TeacherSupplementApplicationVO();
            value.setApplicationId(rs.getLong("id")); value.setBatchId(rs.getLong("batch_id"));
            value.setBatchName(rs.getString("batch_name")); value.setStudentNo(rs.getString("student_no"));
            value.setFullName(rs.getString("full_name")); value.setMajorName(rs.getString("major_name"));
            value.setDegreeType(rs.getString("degree_type")); value.setBiography(rs.getString("biography"));
            long fileId = rs.getLong("resume_file_id"); value.setResumeFileId(rs.wasNull() ? null : Long.valueOf(fileId));
            value.setStatus(rs.getString("application_status")); value.setSubmittedAt(rs.getString("submitted_at"));
            value.setProcessedAt(rs.getString("processed_at")); return value;
        }, teacherId, batchId, Integer.valueOf(pageSize), Long.valueOf(offset));
    }
    public long countSupplementApplications(Long teacherId, Long batchId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM supplement_application WHERE teacher_id = ? " +
            "AND supplement_window_id IN (SELECT id FROM supplement_window WHERE batch_id = ?)", Long.class, teacherId, batchId);
        return count == null ? 0L : count.longValue();
    }
    public boolean hasSupplementWorkAccess(Long teacherId, Long batchId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM batch_teacher_quota quota " +
            "JOIN supplement_window window ON window.batch_id = quota.batch_id " +
            "JOIN selection_batch batch ON batch.id = quota.batch_id " +
            "WHERE window.batch_id = ? AND quota.teacher_id = ? AND window.window_status = 'OPEN' " +
            "AND window.effective_start_at <= UTC_TIMESTAMP(3) AND UTC_TIMESTAMP(3) < window.effective_end_at " +
            "AND (EXISTS (SELECT 1 FROM annual_eligibility_slot eligibility_slot " +
            "JOIN annual_eligibility eligibility ON eligibility.id = eligibility_slot.eligibility_id " +
            "AND eligibility.eligibility_status = 'ELIGIBLE' AND eligibility.valid_to IS NULL " +
            "AND (eligibility.valid_from IS NULL OR eligibility.valid_from <= UTC_TIMESTAMP(3)) " +
            "WHERE eligibility_slot.academic_year_id = batch.academic_year_id " +
            "AND eligibility_slot.college_id = batch.college_id AND eligibility_slot.teacher_id = quota.teacher_id) " +
            "OR EXISTS (SELECT 1 FROM supplement_application application " +
            "JOIN annual_eligibility historic ON historic.academic_year_id = batch.academic_year_id " +
            "AND historic.college_id = batch.college_id AND historic.teacher_id = quota.teacher_id " +
            "AND historic.student_id IS NULL AND historic.eligibility_status = 'ELIGIBLE' " +
            "AND (historic.valid_from IS NULL OR historic.valid_from <= application.submitted_at) " +
            "AND (historic.valid_to IS NULL OR application.submitted_at < historic.valid_to) " +
            "WHERE application.supplement_window_id = window.id AND application.batch_teacher_quota_id = quota.id " +
            "AND application.application_status = 'IN_REVIEW'))", Integer.class,
            batchId, teacherId);
        return count != null && count.intValue() > 0;
    }

    public Optional<QuotaSnapshot> currentQuota(Long quotaId) {
        List<QuotaSnapshot> rows = jdbc.query("SELECT quota_limit, occupied_count FROM batch_teacher_quota WHERE id = ?",
            (rs, n) -> new QuotaSnapshot(rs.getInt("quota_limit"), rs.getInt("occupied_count")), quotaId);
        return rows.isEmpty() ? Optional.<QuotaSnapshot>empty() : Optional.of(rows.get(0));
    }

    public Optional<Long> findAuditObject(Long operationId, String action) {
        List<Long> rows = jdbc.query("SELECT object_id FROM audit_event WHERE business_operation_id = ? AND action_code = ? " +
            "ORDER BY id DESC LIMIT 1", (rs, n) -> rs.getLong(1), operationId, action);
        return rows.isEmpty() ? Optional.<Long>empty() : Optional.of(rows.get(0));
    }

    public int countNoticeRecipients(Long noticeId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM notice_recipient WHERE notice_id = ?", Integer.class, noticeId);
        return count == null ? 0 : count.intValue();
    }

    public Optional<TeacherBatchSummaryVO> findSummary(Long teacherId, Long batchId) {
        List<TeacherBatchSummaryVO> rows = jdbc.query("SELECT batch.id, batch.name, batch.batch_status, stage.stage_code, " +
            "quota.quota_limit, quota.occupied_count FROM selection_batch batch JOIN batch_teacher_quota quota " +
            "ON quota.batch_id = batch.id AND quota.teacher_id = ? LEFT JOIN batch_stage stage " +
            "ON stage.id = batch.current_stage_id WHERE batch.id = ?", (rs, n) -> {
            TeacherBatchSummaryVO item = new TeacherBatchSummaryVO(); item.setBatchId(rs.getLong("id"));
            item.setBatchName(rs.getString("name")); item.setBatchStatus(rs.getString("batch_status"));
            item.setCurrentStage(rs.getString("stage_code")); item.setQuotaLimit(rs.getInt("quota_limit"));
            item.setOccupiedCount(rs.getInt("occupied_count")); item.setRemainingCount(rs.getInt("quota_limit") - rs.getInt("occupied_count"));
            return item;
        }, teacherId, batchId);
        if (rows.isEmpty()) return Optional.empty();
        TeacherBatchSummaryVO summary = rows.get(0);
        summary.setMatchedStudents(jdbc.query("SELECT relation.id AS relation_id, student.student_no, student.full_name, " +
            "major.name AS major_name, student.degree_type, relation.source_type, " +
            "DATE_FORMAT(relation.locked_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS locked_at " +
            "FROM matching_relation relation JOIN batch_teacher_quota quota ON quota.id = relation.batch_teacher_quota_id " +
            "JOIN batch_student participant ON participant.id = relation.batch_student_id " +
            "JOIN student ON student.id = participant.student_id JOIN major ON major.id = student.major_id " +
            "WHERE quota.teacher_id = ? AND quota.batch_id = ? AND relation.relation_status = 'LOCKED' " +
            "ORDER BY relation.locked_at, student.student_no", (rs, n) -> {
            TeacherBatchSummaryVO.MatchedStudent item = new TeacherBatchSummaryVO.MatchedStudent();
            item.setRelationId(rs.getLong("relation_id")); item.setStudentNo(rs.getString("student_no"));
            item.setFullName(rs.getString("full_name")); item.setMajorName(rs.getString("major_name"));
            item.setDegreeType(rs.getString("degree_type")); item.setSource(rs.getString("source_type"));
            item.setLockedAt(rs.getString("locked_at")); return item;
        }, teacherId, batchId));
        return Optional.of(summary);
    }

    public List<Long> findNoticeRecipients(Long teacherId, Long batchId,
        List<SendTeacherApplicationNoticeRequest.ApplicationReference> references) {
        List<Long> accountIds = new ArrayList<Long>();
        for (SendTeacherApplicationNoticeRequest.ApplicationReference reference : references) {
            String sql;
            if ("ROUND".equals(reference.getApplicationType())) {
                sql = "SELECT student.account_id FROM round_application application JOIN batch_stage stage ON stage.id = application.stage_id " +
                    "JOIN batch_student participant ON participant.id = application.batch_student_id " +
                    "JOIN student ON student.id = participant.student_id WHERE application.id = ? " +
                    "AND application.teacher_id = ? AND stage.batch_id = ?";
            } else if ("SUPPLEMENT".equals(reference.getApplicationType())) {
                sql = "SELECT student.account_id FROM supplement_application application JOIN supplement_window window " +
                    "ON window.id = application.supplement_window_id JOIN batch_student participant " +
                    "ON participant.id = application.batch_student_id JOIN student ON student.id = participant.student_id " +
                    "WHERE application.id = ? AND application.teacher_id = ? AND window.batch_id = ?";
            } else return new ArrayList<Long>();
            List<Long> found = jdbc.query(sql, (rs, n) -> rs.getLong(1), reference.getApplicationId(), teacherId, batchId);
            if (found.size() != 1) return new ArrayList<Long>();
            if (!accountIds.contains(found.get(0))) accountIds.add(found.get(0));
        }
        return accountIds;
    }

    public boolean accountHasActiveTeacher(Long teacherId, Long accountId) {
        List<Long> rows = jdbc.query("SELECT teacher.id FROM teacher JOIN account ON account.id = teacher.account_id " +
            "WHERE teacher.id = ? AND teacher.account_id = ? AND account.account_status = 'ACTIVE' FOR UPDATE",
            (rs, n) -> rs.getLong(1), teacherId, accountId);
        return !rows.isEmpty();
    }

    private static Long nullableLong(java.sql.ResultSet rs, String label) throws java.sql.SQLException {
        long value = rs.getLong(label); return rs.wasNull() ? null : Long.valueOf(value);
    }
    public static String profileEtag(Long teacherId, int version, long rowVersion) {
        return "\"teacher-profile-" + teacherId + "-" + version + "-" + rowVersion + "\"";
    }

    public static class Operation {
        public final Long id; public final String fingerprint; public final String resultCode;
        public Operation(Long id, String fingerprint, String resultCode) { this.id = id; this.fingerprint = fingerprint; this.resultCode = resultCode; }
    }
    public static class BulkSortItem {
        public final Long applicationId; public final java.sql.Timestamp submittedAt; public final String studentNo;
        public BulkSortItem(Long id, java.sql.Timestamp submittedAt, String studentNo) {
            this.applicationId = id; this.submittedAt = submittedAt; this.studentNo = studentNo;
        }
    }
    public static class QuotaSnapshot {
        public final Integer quotaLimit; public final Integer occupiedCount;
        public QuotaSnapshot(int limit, int occupied) { this.quotaLimit = Integer.valueOf(limit); this.occupiedCount = Integer.valueOf(occupied); }
    }
    public static class StageContext {
        public final String batchStatus; public final Long currentStageId; public final Long stageId; public final String stageStatus;
        public final java.sql.Timestamp startAt; public final java.sql.Timestamp endAt; public final Integer cycle;
        public StageContext(String b, Long c, Long s, String status, java.sql.Timestamp start, java.sql.Timestamp end, int cycle) {
            this.batchStatus=b; this.currentStageId=c; this.stageId=s; this.stageStatus=status; this.startAt=start; this.endAt=end; this.cycle=cycle;
        }
    }
    public static class RoundApplicationContext {
        public final Long applicationId, batchStudentId, preferenceItemId, stageId, teacherId, academicYearId, currentStageId;
        public final String status, stageCode, batchStatus, matchStatus, studentNo, degreeType, appliedDegreeType;
        public final Integer cycle, stageCycle, classificationVersion, quotaLimit, occupiedCount;
        public final Long studentId, currentRelationId, accountId, majorId, quotaId, scopeVersionId, appliedMajorId;
        public RoundApplicationContext(Long applicationId, Long batchStudentId, Long preferenceItemId, Long stageId, Long teacherId,
            String status, int cycle, String stageCode, int stageCycle, Long academicYearId, Long currentStageId, String batchStatus,
            Long studentId, String matchStatus, Long currentRelationId, Long accountId, String studentNo, Long majorId, String degreeType,
            int classificationVersion, Long quotaId, int quotaLimit, int occupiedCount, Long scopeVersionId, Long appliedMajorId,
            String appliedDegreeType) {
            this.applicationId=applicationId; this.batchStudentId=batchStudentId; this.preferenceItemId=preferenceItemId; this.stageId=stageId;
            this.teacherId=teacherId; this.status=status; this.cycle=cycle; this.stageCode=stageCode; this.stageCycle=stageCycle;
            this.academicYearId=academicYearId; this.currentStageId=currentStageId; this.batchStatus=batchStatus; this.studentId=studentId;
            this.matchStatus=matchStatus; this.currentRelationId=currentRelationId; this.accountId=accountId; this.studentNo=studentNo;
            this.majorId=majorId; this.degreeType=degreeType; this.classificationVersion=classificationVersion; this.quotaId=quotaId;
            this.quotaLimit=quotaLimit; this.occupiedCount=occupiedCount; this.scopeVersionId=scopeVersionId; this.appliedMajorId=appliedMajorId;
            this.appliedDegreeType=appliedDegreeType;
        }
    }
    public static class SupplementContext {
        public final Long applicationId, batchStudentId, windowId, teacherId, currentStageId, academicYearId, stageId, studentId;
        public final String status, batchStatus, stageStatus, windowStatus, matchStatus, studentNo, degreeType;
        public final Integer cycle, quotaLimit, occupiedCount, classificationVersion;
        public final java.sql.Timestamp startAt, endAt, submittedAt;
        public final Long currentRelationId, accountId, majorId, quotaId;
        public SupplementContext(Long applicationId, Long batchStudentId, Long windowId, String status, Long teacherId,
            String batchStatus, Long currentStageId, Long academicYearId, Long stageId, String stageStatus, int cycle,
            String windowStatus, java.sql.Timestamp startAt, java.sql.Timestamp endAt, java.sql.Timestamp submittedAt,
            Long studentId, String matchStatus,
            Long currentRelationId, Long accountId, String studentNo, Long majorId, String degreeType, int classificationVersion,
            Long quotaId, int quotaLimit, int occupiedCount, Long ignoredQuotaId) {
            this.applicationId=applicationId; this.batchStudentId=batchStudentId; this.windowId=windowId; this.status=status;
            this.teacherId=teacherId; this.batchStatus=batchStatus; this.currentStageId=currentStageId; this.academicYearId=academicYearId;
            this.stageId=stageId; this.stageStatus=stageStatus; this.cycle=cycle; this.windowStatus=windowStatus; this.startAt=startAt;
            this.endAt=endAt; this.submittedAt=submittedAt; this.studentId=studentId; this.matchStatus=matchStatus; this.currentRelationId=currentRelationId;
            this.accountId=accountId; this.studentNo=studentNo; this.majorId=majorId; this.degreeType=degreeType;
            this.classificationVersion=classificationVersion; this.quotaId=quotaId; this.quotaLimit=quotaLimit; this.occupiedCount=occupiedCount;
        }
    }
}
