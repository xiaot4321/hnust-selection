package cn.hnust.selection.repository;

import cn.hnust.selection.entity.SelectionBatchEntity;
import cn.hnust.selection.entity.TeacherQuotaEntity;
import cn.hnust.selection.entity.TeacherScopeVersionEntity;
import cn.hnust.selection.vo.AcademicYearOptionVO;
import cn.hnust.selection.vo.BatchStatisticsVO;
import cn.hnust.selection.vo.BatchStageVO;
import cn.hnust.selection.vo.BatchTeacherQuotaVO;
import cn.hnust.selection.vo.CollegeOptionVO;
import cn.hnust.selection.vo.MajorVO;
import cn.hnust.selection.vo.SelectionBatchSummaryVO;
import cn.hnust.selection.vo.TeacherScopeBatchOptionVO;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 批次、阶段、名额和导师可报范围的数据访问层。
 *
 * <p>此类只执行参数化 SQL、行锁和结果映射，不判断调用者是否有权操作对象，也不决定业务状态转换。
 * 并发关键数据的锁定由 Service 在事务中调用本类方法；阶段时间统一使用数据库 UTC，避免应用主机时钟偏差。</p>
 */
@Repository
public class SelectionBatchRepository {
    private final JdbcTemplate jdbc;

    public SelectionBatchRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<CollegeOptionVO> listActiveColleges() {
        return jdbc.query("SELECT id, college_code, name FROM college WHERE is_active = TRUE ORDER BY college_code, id",
            (rs, n) -> new CollegeOptionVO(rs.getLong("id"), rs.getString("college_code"), rs.getString("name")));
    }

    public Optional<CollegeOptionVO> findActiveCollege(Long collegeId) {
        try {
            return Optional.of(jdbc.queryForObject("SELECT id, college_code, name FROM college " +
                "WHERE id = ? AND is_active = TRUE", (rs, n) -> new CollegeOptionVO(rs.getLong("id"),
                rs.getString("college_code"), rs.getString("name")), collegeId));
        } catch (EmptyResultDataAccessException ex) { return Optional.empty(); }
    }

    public List<AcademicYearOptionVO> listAcademicYears() {
        return jdbc.query("SELECT id, year_code, display_name FROM academic_year ORDER BY year_code DESC, id DESC",
            (rs, n) -> new AcademicYearOptionVO(rs.getLong("id"), rs.getString("year_code"),
                rs.getString("display_name")));
    }

    public boolean academicYearExists(Long yearId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM academic_year WHERE id = ?", Integer.class, yearId);
        return count != null && count.intValue() == 1;
    }

    /** 锁住学年主记录，使同学院/学年的并发批次创建可以串行判断追加批次规则。 */
    public boolean lockAcademicYear(Long yearId) {
        try {
            jdbc.queryForObject("SELECT id FROM academic_year WHERE id = ? FOR UPDATE", Long.class, yearId);
            return true;
        } catch (EmptyResultDataAccessException ex) { return false; }
    }

    /** 当前事务中锁定同一学院/学年的已有批次行，并判断创建请求是否属于追加批次。 */
    public boolean hasPriorBatch(Long collegeId, Long yearId) {
        List<Long> batchIds = jdbc.query("SELECT id FROM selection_batch WHERE college_id = ? " +
            "AND academic_year_id = ? FOR UPDATE", (rs, n) -> rs.getLong(1), collegeId, yearId);
        return !batchIds.isEmpty();
    }

    /**
     * 统计当前仍满足目录条件且已配置名额的导师。
     *
     * <p>有效导师必须有启用账号、已审核发布的公开资料、匹配学院/学年的年度资格槽位以及 ELIGIBLE 资格；
     * 已失去资格的历史名额行不能单独满足发布校验。</p>
     */
    public int countEligibleConfiguredQuotas(Long batchId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM batch_teacher_quota q " +
            "JOIN selection_batch b ON b.id = q.batch_id JOIN teacher t ON t.id = q.teacher_id " +
            "AND t.college_id = b.college_id JOIN account a ON a.id = t.account_id " +
            "AND a.account_status = 'ACTIVE' JOIN teacher_public_profile_version p " +
            "ON p.id = t.current_public_profile_version_id AND p.review_status = 'APPROVED' " +
            "AND p.published_at IS NOT NULL JOIN annual_eligibility_slot aes " +
            "ON aes.academic_year_id = b.academic_year_id AND aes.college_id = b.college_id " +
            "AND aes.teacher_id = t.id " +
            "JOIN annual_eligibility ae ON ae.id = aes.eligibility_id " +
            "AND ae.college_id = b.college_id AND ae.eligibility_status = 'ELIGIBLE' WHERE q.batch_id = ?", Integer.class, batchId);
        return count == null ? 0 : count.intValue();
    }

    /**
     * 按学院及可选批次白名单筛选批次摘要。
     *
     * <p>{@code batchIds == null} 表示调用方已通过学院级授权检查；空集合明确表示没有可访问批次，
     * 直接返回空列表，避免生成空的 SQL IN 子句。</p>
     */
    public List<SelectionBatchSummaryVO> listBatches(Long collegeId, List<Long> batchIds) {
        if (batchIds != null && batchIds.isEmpty()) return Collections.emptyList();
        StringBuilder sql = new StringBuilder("SELECT b.id, b.college_id, c.name AS college_name, " +
            "b.academic_year_id, y.year_code, b.batch_code, b.name, b.batch_status, b.supplement_planned, " +
            "b.append_reason, b.published_at, b.started_at, b.row_version FROM selection_batch b " +
            "JOIN college c ON c.id = b.college_id JOIN academic_year y ON y.id = b.academic_year_id WHERE 1=1");
        List<Object> args = new ArrayList<Object>();
        if (collegeId != null) { sql.append(" AND b.college_id = ?"); args.add(collegeId); }
        if (batchIds != null) {
            sql.append(" AND b.id IN (").append(placeholders(batchIds.size())).append(")");
            args.addAll(batchIds);
        }
        sql.append(" ORDER BY y.year_code DESC, b.created_at DESC, b.id DESC");
        return jdbc.query(sql.toString(), (rs, n) -> summary(rs), args.toArray());
    }

    public Optional<SelectionBatchEntity> findBatch(Long batchId) { return findBatch(batchId, false); }
    public Optional<SelectionBatchEntity> lockBatch(Long batchId) { return findBatch(batchId, true); }

    /** 查询批次投影；写事务通过 {@code forUpdate} 加锁，普通读取不持锁。 */
    private Optional<SelectionBatchEntity> findBatch(Long batchId, boolean forUpdate) {
        String sql = "SELECT id, college_id, academic_year_id, batch_code, name, batch_status, " +
            "supplement_planned, append_reason, created_by, published_at, started_at, frozen_roster_at, row_version " +
            "FROM selection_batch WHERE id = ?" + (forUpdate ? " FOR UPDATE" : "");
        try {
            return Optional.of(jdbc.queryForObject(sql, (rs, n) -> new SelectionBatchEntity(
                rs.getLong("id"), rs.getLong("college_id"), rs.getLong("academic_year_id"),
                rs.getString("batch_code"), rs.getString("name"), rs.getString("batch_status"),
                rs.getBoolean("supplement_planned"), rs.getString("append_reason"), rs.getLong("created_by"),
                rs.getTimestamp("published_at"), rs.getTimestamp("started_at"),
                rs.getTimestamp("frozen_roster_at"), rs.getLong("row_version")), batchId));
        } catch (EmptyResultDataAccessException ex) { return Optional.empty(); }
    }

    /** 只插入 DRAFT 基础行；阶段、幂等操作和审计由调用方在同一事务继续写入。 */
    public Long insertBatch(Long collegeId, Long academicYearId, String code, String name,
                            boolean supplementPlanned, String appendReason, Long actorId) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("INSERT INTO selection_batch " +
                "(college_id, academic_year_id, batch_code, name, batch_status, supplement_planned, " +
                "created_by, append_reason) VALUES (?, ?, ?, ?, 'DRAFT', ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, collegeId); ps.setLong(2, academicYearId); ps.setString(3, code); ps.setString(4, name);
            ps.setBoolean(5, supplementPlanned); ps.setLong(6, actorId); ps.setString(7, appendReason);
            return ps;
        }, key);
        Number id = key.getKey();
        if (id == null) throw new IllegalStateException("selection_batch insert returned no id");
        return id.longValue();
    }

    /** 创建固定的填报、三轮和补选阶段行；未计划补选时保留该行并标记为 NOT_SCHEDULED。 */
    public void insertInitialStages(Long batchId, boolean supplementPlanned) {
        insertStage(batchId, "FILLING", 1);
        insertStage(batchId, "ROUND_1", 2);
        insertStage(batchId, "ROUND_2", 3);
        insertStage(batchId, "ROUND_3", 4);
        insertStage(batchId, "SUPPLEMENT", 5);
        if (!supplementPlanned) {
            jdbc.update("UPDATE batch_stage SET stage_status = 'NOT_SCHEDULED' " +
                "WHERE batch_id = ? AND stage_code = 'SUPPLEMENT'", batchId);
        }
    }

    private void insertStage(Long batchId, String code, int order) {
        jdbc.update("INSERT INTO batch_stage(batch_id, stage_code, stage_order, stage_status, execution_cycle) " +
            "VALUES (?, ?, ?, 'NOT_STARTED', 1)", batchId, code, order);
    }

    /** 按阶段顺序读取计划时间、生效时间和服务端维护的阶段状态。 */
    public List<BatchStageVO> listStages(Long batchId) {
        return jdbc.query("SELECT id, stage_code, stage_order, stage_status, close_reason, planned_start_at, planned_end_at, " +
            "effective_start_at, effective_end_at FROM batch_stage WHERE batch_id = ? ORDER BY stage_order, id",
            (rs, n) -> new BatchStageVO(rs.getLong("id"), rs.getString("stage_code"),
                rs.getInt("stage_order"), rs.getString("stage_status"), rs.getString("close_reason"), instant(rs.getTimestamp("planned_start_at")),
                instant(rs.getTimestamp("planned_end_at")), instant(rs.getTimestamp("effective_start_at")),
                instant(rs.getTimestamp("effective_end_at"))), batchId);
    }

    public Optional<String> stageStatus(Long batchId, String stageCode) {
        try { return Optional.of(jdbc.queryForObject("SELECT stage_status FROM batch_stage " +
            "WHERE batch_id = ? AND stage_code = ?", String.class, batchId, stageCode)); }
        catch (EmptyResultDataAccessException ex) { return Optional.empty(); }
    }

    /** 读取当前阶段主键用于生命周期审计；状态命令执行时批次行已由调用方锁定。 */
    public Long currentStageId(Long batchId) {
        return jdbc.queryForObject("SELECT current_stage_id FROM selection_batch WHERE id = ?", Long.class, batchId);
    }

    /** 读取 MySQL UTC 时间，作为阶段边界和排期变更判定的权威时钟。 */
    public Timestamp utcNow() {
        return jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)", Timestamp.class);
    }

    public Long stageId(Long batchId, String code) {
        try { return jdbc.queryForObject("SELECT id FROM batch_stage WHERE batch_id = ? AND stage_code = ?",
            Long.class, batchId, code); }
        catch (EmptyResultDataAccessException ex) { return null; }
    }

    /** 读取修订号上界；调用方持有批次行锁，再为本次实际变化分配连续新号。 */
    public long nextScheduleRevision(Long batchId) {
        Integer current = jdbc.queryForObject("SELECT COALESCE(MAX(revision_no), 0) FROM schedule_revision " +
            "WHERE batch_id = ?", Integer.class, batchId);
        return (current == null ? 0 : current.intValue()) + 1;
    }

    /**
     * 更新一个常规阶段的计划/生效时间并保存时间修订。
     *
     * <p>调用方必须先通过 Service 校验状态和 If-Match；此方法锁阶段行读取旧值，阶段更新和修订记录依赖外层事务共同提交。</p>
     */
    public void saveStageSchedule(Long batchId, String stageCode, Timestamp start, Timestamp end,
                                  Long actorId, String reason, int revisionNo) {
        Long stageId = stageId(batchId, stageCode);
        Timestamp oldStart = null;
        Timestamp oldEnd = null;
        if (stageId == null) throw new IllegalStateException("batch stage missing");
        List<Timestamp> old = jdbc.query("SELECT planned_start_at, planned_end_at FROM batch_stage " +
            "WHERE id = ? FOR UPDATE", (rs, n) -> {
                List<Timestamp> values = new ArrayList<Timestamp>();
                values.add(rs.getTimestamp("planned_start_at")); values.add(rs.getTimestamp("planned_end_at"));
                return values;
            }, stageId).get(0);
        oldStart = old.get(0); oldEnd = old.get(1);
        jdbc.update("UPDATE batch_stage SET planned_start_at = ?, planned_end_at = ?, " +
            "effective_start_at = ?, effective_end_at = ?, row_version = row_version + 1 WHERE id = ?",
            start, end, start, end, stageId);
        jdbc.update("INSERT INTO schedule_revision(batch_id, stage_id, revision_no, revision_type, old_start_at, " +
            "old_end_at, new_start_at, new_end_at, actor_account_id, reason, occurred_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(3))", batchId, stageId, revisionNo,
            oldStart == null ? "INITIAL" : "ADJUSTMENT", oldStart, oldEnd, start, end, actorId, reason);
    }

    public String stageCloseReason(Long stageId) {
        return jdbc.queryForObject("SELECT close_reason FROM batch_stage WHERE id = ?", String.class, stageId);
    }

    public int stageExecutionCycle(Long stageId) {
        Integer cycle = jdbc.queryForObject("SELECT execution_cycle FROM batch_stage WHERE id = ?", Integer.class, stageId);
        return cycle == null ? 0 : cycle.intValue();
    }

    public int countRoundApplications(Long stageId, int cycle) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM round_application WHERE stage_id = ? AND execution_cycle = ?",
            Integer.class, stageId, Integer.valueOf(cycle));
        return count == null ? 0 : count.intValue();
    }

    public int countRoundApplicationsOutsideReview(Long stageId, int cycle) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM round_application WHERE stage_id = ? " +
            "AND execution_cycle = ? AND application_status <> 'IN_REVIEW'", Integer.class, stageId, Integer.valueOf(cycle));
        return count == null ? 0 : count.intValue();
    }

    public int countSupplementApplications(Long batchId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM supplement_application application " +
            "JOIN supplement_window window ON window.id = application.supplement_window_id WHERE window.batch_id = ?",
            Integer.class, batchId);
        return count == null ? 0 : count.intValue();
    }

    public int countRecoverableDeadlineApplications(Long stageId, int roundNo) {
        String reason = roundNo == 3 ? "ROUND3_EXHAUSTED" : "PREFERENCE_EXHAUSTED";
        String current = "PENDING_ROUND_" + roundNo;
        String next = roundNo < 3 ? "PENDING_ROUND_" + (roundNo + 1) : current;
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM round_application application " +
            "JOIN batch_student participant ON participant.id = application.batch_student_id " +
            "WHERE application.stage_id = ? AND application.application_status = 'NOT_ADMITTED' " +
            "AND application.close_reason = 'ROUND_DEADLINE' AND (participant.match_status IN (?, ?) OR " +
            "(participant.match_status = 'UNMATCHED' AND participant.match_reason = ? AND EXISTS " +
            "(SELECT 1 FROM student_match_event event WHERE event.batch_student_id = participant.id " +
            "AND event.source_type = 'ROUND' AND event.source_id = ? AND event.reason_code = ? " +
            "AND event.new_status = 'UNMATCHED'))) ", Integer.class, stageId, current, next, reason, stageId, reason);
        return count == null ? 0 : count.intValue();
    }

    /** 只恢复系统按截止时间结案且学生仍处于本轮/下轮等待态，或由本轮结案推进为 UNMATCHED 的申请。 */
    public int reopenDeadlineApplications(Long batchId, Long stageId, int roundNo, int newCycle,
        Long actorId, Long operationId) {
        String reason = roundNo == 3 ? "ROUND3_EXHAUSTED" : "PREFERENCE_EXHAUSTED";
        String current = "PENDING_ROUND_" + roundNo;
        String next = roundNo < 3 ? "PENDING_ROUND_" + (roundNo + 1) : current;
        int updated = jdbc.update("UPDATE round_application application JOIN batch_student participant " +
            "ON participant.id = application.batch_student_id SET application.application_status = 'IN_REVIEW', " +
            "application.close_reason = NULL, application.entered_review_at = UTC_TIMESTAMP(3), " +
            "application.decided_at = NULL, application.execution_cycle = ?, application.active_snapshot_id = NULL, " +
            "application.row_version = application.row_version + 1 WHERE application.stage_id = ? " +
            "AND application.application_status = 'NOT_ADMITTED' AND application.close_reason = 'ROUND_DEADLINE' " +
            "AND (participant.match_status IN (?, ?) OR (participant.match_status = 'UNMATCHED' " +
            "AND participant.match_reason = ? AND EXISTS (SELECT 1 FROM student_match_event event " +
            "WHERE event.batch_student_id = participant.id AND event.source_type = 'ROUND' AND event.source_id = ? " +
            "AND event.reason_code = ? AND event.new_status = 'UNMATCHED'))) ",
            Integer.valueOf(newCycle), stageId, current, next, reason, stageId, reason);
        if (updated == 0) return 0;
        jdbc.update("INSERT INTO application_event(object_type, object_id, execution_cycle, action_code, old_status, " +
            "new_status, close_reason, actor_kind, actor_account_id, occurred_at, business_operation_id) " +
            "SELECT 'ROUND_APPLICATION', id, execution_cycle, 'ROUND_REOPENED', 'NOT_ADMITTED', 'IN_REVIEW', " +
            "'ROUND_DEADLINE', 'ADMIN', ?, UTC_TIMESTAMP(3), ? FROM round_application " +
            "WHERE stage_id = ? AND execution_cycle = ? AND application_status = 'IN_REVIEW' " +
            "AND active_snapshot_id IS NULL", actorId, operationId, stageId, Integer.valueOf(newCycle));
        jdbc.update("INSERT INTO student_match_event(batch_student_id, old_status, new_status, reason_code, actual_round, " +
            "actual_preference_order, source_type, source_id, occurred_at, business_operation_id) " +
            "SELECT participant.id, participant.match_status, ?, 'ROUND_REOPENED', ?, ?, 'ROUND', ?, UTC_TIMESTAMP(3), ? " +
            "FROM batch_student participant JOIN round_application application ON application.batch_student_id = participant.id " +
            "WHERE application.stage_id = ? AND application.execution_cycle = ? AND application.application_status = 'IN_REVIEW' " +
            "AND participant.match_status <> ? AND (participant.match_status = ? OR participant.match_status = 'UNMATCHED')",
            current, Integer.valueOf(roundNo), Integer.valueOf(roundNo), stageId, operationId, stageId,
            Integer.valueOf(newCycle), current, next);
        jdbc.update("UPDATE batch_student participant JOIN round_application application " +
            "ON application.batch_student_id = participant.id SET participant.match_status = ?, participant.match_reason = NULL, " +
            "participant.match_changed_at = UTC_TIMESTAMP(3), participant.row_version = participant.row_version + 1 " +
            "WHERE application.stage_id = ? AND application.execution_cycle = ? AND application.application_status = 'IN_REVIEW' " +
            "AND participant.match_status IN (?, 'UNMATCHED')", current, stageId, Integer.valueOf(newCycle), next);
        captureReopenedRoundSnapshots(stageId);
        publishRoundReopened(batchId, stageId, roundNo, actorId, operationId);
        return updated;
    }

    private void captureReopenedRoundSnapshots(Long stageId) {
        jdbc.update("INSERT INTO application_profile_snapshot(round_application_id, supplement_application_id, execution_cycle, " +
            "captured_at, capture_reason, full_name, student_no, major_id, major_name_snapshot, degree_type, biography, resume_file_id) " +
            "SELECT application.id, NULL, application.execution_cycle, UTC_TIMESTAMP(3), 'ROUND_REOPEN', student.full_name, " +
            "student.student_no, student.major_id, major.name, student.degree_type, profile.biography, profile.resume_file_id " +
            "FROM round_application application JOIN batch_student participant ON participant.id = application.batch_student_id " +
            "JOIN student ON student.id = participant.student_id JOIN major ON major.id = student.major_id " +
            "LEFT JOIN student_profile_version profile ON profile.student_id = student.id AND profile.version_no = " +
            "(SELECT MAX(latest.version_no) FROM student_profile_version latest WHERE latest.student_id = student.id) " +
            "WHERE application.stage_id = ? AND application.active_snapshot_id IS NULL AND application.application_status = 'IN_REVIEW'", stageId);
        jdbc.update("UPDATE round_application application JOIN application_profile_snapshot snapshot " +
            "ON snapshot.round_application_id = application.id AND snapshot.execution_cycle = application.execution_cycle " +
            "SET application.active_snapshot_id = snapshot.id WHERE application.stage_id = ? " +
            "AND application.active_snapshot_id IS NULL AND application.application_status = 'IN_REVIEW'", stageId);
    }

    private void publishRoundReopened(Long batchId, Long stageId, int roundNo, Long actorId, Long operationId) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("INSERT INTO site_notice(sender_account_id, batch_id, " +
                "notice_type, title, body, source_operation_id, created_at, visible_at) " +
                "VALUES (?, ?, 'ROUND_REOPENED', ?, ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))", Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, actorId); statement.setLong(2, batchId); statement.setString(3, "第 " + roundNo + " 轮重新开放");
            statement.setString(4, "本轮已重新开放，请在互选进度中查看当前处理状态。"); statement.setLong(5, operationId);
            return statement;
        }, key);
        Number noticeId = key.getKey();
        if (noticeId != null) jdbc.update("INSERT IGNORE INTO notice_recipient(notice_id, account_id, recipient_status, delivered_at) " +
            "SELECT ?, student.account_id, 'DELIVERED', UTC_TIMESTAMP(3) FROM round_application application " +
            "JOIN batch_student participant ON participant.id = application.batch_student_id JOIN student ON student.id = participant.student_id " +
            "WHERE application.stage_id = ? AND application.application_status = 'IN_REVIEW'", noticeId.longValue(), stageId);
    }

    public int reopenStage(Long stageId, Timestamp startAt, Timestamp endAt) {
        return jdbc.update("UPDATE batch_stage SET stage_status = 'OPEN', actual_started_at = ?, actual_closed_at = NULL, " +
            "close_reason = NULL, execution_cycle = execution_cycle + 1, row_version = row_version + 1 " +
            "WHERE id = ? AND stage_status = 'CLOSED'", startAt, stageId);
    }

    /** 回退下游自动生成的待处理申请并增加阶段周期，保留被取代事件和历史快照。 */
    public int supersedeUntouchedRound(Long stageId, int cycle, Long actorId, Long operationId) {
        jdbc.update("INSERT INTO application_event(object_type, object_id, execution_cycle, action_code, old_status, " +
            "new_status, actor_kind, actor_account_id, occurred_at, business_operation_id) " +
            "SELECT 'ROUND_APPLICATION', id, execution_cycle, 'SUPERSEDED_BY_REOPEN', application_status, " +
            "'SUPERSEDED_BY_REOPEN', 'ADMIN', ?, UTC_TIMESTAMP(3), ? FROM round_application " +
            "WHERE stage_id = ? AND execution_cycle = ? AND application_status = 'IN_REVIEW'", actorId, operationId,
            stageId, Integer.valueOf(cycle));
        int updated = jdbc.update("UPDATE round_application SET application_status = 'SUPERSEDED_BY_REOPEN', " +
            "close_reason = 'SUPERSEDED_BY_REOPEN', decided_at = UTC_TIMESTAMP(3), row_version = row_version + 1 " +
            "WHERE stage_id = ? AND execution_cycle = ? AND application_status = 'IN_REVIEW'", stageId, Integer.valueOf(cycle));
        jdbc.update("UPDATE batch_stage SET stage_status = 'NOT_STARTED', actual_started_at = NULL, actual_closed_at = NULL, " +
            "close_reason = NULL, execution_cycle = execution_cycle + 1, row_version = row_version + 1 " +
            "WHERE id = ? AND stage_status IN ('NOT_STARTED', 'OPEN')", stageId);
        return updated;
    }

    public void setCurrentStageForReopen(Long batchId, Long stageId) {
        jdbc.update("UPDATE selection_batch SET current_stage_id = ?, row_version = row_version + 1 WHERE id = ?", stageId, batchId);
    }

    /** 重开期间重置暂停排期基准；原暂停段保留，恢复时只顺延重开后的剩余暂停时长。 */
    public void restartPauseClockForReopen(Long batchId, Long oldPauseEventId, Long stageId, String stageCode,
        Long actorId, String reason, Long operationId) {
        jdbc.update("UPDATE batch_lifecycle_event SET pause_ended_at = UTC_TIMESTAMP(3), " +
            "pause_duration_seconds = TIMESTAMPDIFF(SECOND, pause_started_at, UTC_TIMESTAMP(3)) " +
            "WHERE id = ? AND batch_id = ? AND pause_ended_at IS NULL", oldPauseEventId, batchId);
        jdbc.update("INSERT INTO batch_lifecycle_event(batch_id, action_code, old_status, new_status, stage_id, " +
            "actor_account_id, actor_kind, reason, occurred_at, pause_started_at, frozen_stage_code, business_operation_id) " +
            "VALUES (?, 'ROUND_REOPEN_PAUSE', 'PAUSED', 'PAUSED', ?, ?, 'ADMIN', ?, UTC_TIMESTAMP(3), " +
            "UTC_TIMESTAMP(3), ?, ?)", batchId, stageId, actorId, reason, stageCode, operationId);
    }

    /** 保存补选窗口及其阶段时间；不存在时创建窗口，存在时更新当前计划并追加修订。 */
    public void saveSupplementSchedule(Long batchId, Timestamp start, Timestamp end, Long actorId,
                                       String reason, int revisionNo) {
        Long stageId = stageId(batchId, "SUPPLEMENT");
        if (stageId == null) throw new IllegalStateException("supplement stage missing");
        Long windowId = null;
        Timestamp oldStart = null; Timestamp oldEnd = null;
        try {
            windowId = jdbc.queryForObject("SELECT id FROM supplement_window WHERE batch_id = ? FOR UPDATE",
                Long.class, batchId);
            List<Timestamp> old = jdbc.query("SELECT planned_start_at, planned_end_at FROM supplement_window " +
                "WHERE id = ?", (rs, n) -> {
                    List<Timestamp> values = new ArrayList<Timestamp>();
                    values.add(rs.getTimestamp("planned_start_at")); values.add(rs.getTimestamp("planned_end_at"));
                    return values;
                }, windowId).get(0);
            oldStart = old.get(0); oldEnd = old.get(1);
            jdbc.update("UPDATE supplement_window SET planned_start_at = ?, planned_end_at = ?, " +
                "effective_start_at = ?, effective_end_at = ?, row_version = row_version + 1 WHERE id = ?",
                start, end, start, end, windowId);
        } catch (EmptyResultDataAccessException ex) {
            KeyHolder key = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement("INSERT INTO supplement_window " +
                    "(batch_id, stage_id, planned_start_at, planned_end_at, effective_start_at, effective_end_at, " +
                    "window_status) VALUES (?, ?, ?, ?, ?, ?, 'PLANNED')", Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, batchId); ps.setLong(2, stageId); ps.setTimestamp(3, start); ps.setTimestamp(4, end);
                ps.setTimestamp(5, start); ps.setTimestamp(6, end); return ps;
            }, key);
            Number generated = key.getKey(); windowId = generated == null ? null : generated.longValue();
        }
        jdbc.update("UPDATE batch_stage SET planned_start_at = ?, planned_end_at = ?, effective_start_at = ?, " +
            "effective_end_at = ?, row_version = row_version + 1 WHERE id = ?", start, end, start, end, stageId);
        jdbc.update("INSERT INTO schedule_revision(batch_id, supplement_window_id, revision_no, revision_type, " +
            "old_start_at, old_end_at, new_start_at, new_end_at, actor_account_id, reason, occurred_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(3))", batchId, windowId, revisionNo,
            oldStart == null ? "INITIAL" : "ADJUSTMENT", oldStart, oldEnd, start, end, actorId, reason);
    }

    /** 返回当前未撤销许可的版本号；调用方已在事务中锁定批次。 */
    public Map<Long, Integer> currentSupplementTeacherPermissions(Long batchId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT quota.teacher_id, permission.permission_version " +
            "FROM supplement_window window JOIN batch_teacher_quota quota ON quota.batch_id = window.batch_id " +
            "JOIN supplement_teacher permission ON permission.supplement_window_id = window.id " +
            "AND permission.batch_teacher_quota_id = quota.id AND permission.permission_version = " +
            "(SELECT MAX(latest.permission_version) FROM supplement_teacher latest " +
            "WHERE latest.supplement_window_id = window.id AND latest.batch_teacher_quota_id = quota.id) " +
            "WHERE window.batch_id = ? AND permission.revoked_at IS NULL ORDER BY quota.teacher_id", batchId);
        Map<Long, Integer> result = new java.util.LinkedHashMap<Long, Integer>();
        for (Map<String, Object> row : rows) result.put(((Number) row.get("teacher_id")).longValue(),
            ((Number) row.get("permission_version")).intValue());
        return result;
    }

    /** 更新许可时锁定补选窗口；每次重新授权追加版本，不覆盖被撤销的历史版本。 */
    public void replaceSupplementTeacherPermissions(Long batchId, Set<Long> teacherIds, Long actorId, String reason) {
        Long windowId;
        String windowStatus;
        List<Map<String, Object>> windows = jdbc.queryForList("SELECT id, window_status FROM supplement_window " +
            "WHERE batch_id = ? FOR UPDATE", batchId);
        if (windows.isEmpty()) throw new IllegalStateException("supplement window missing");
        windowId = ((Number) windows.get(0).get("id")).longValue();
        windowStatus = String.valueOf(windows.get(0).get("window_status"));
        if ("CLOSED".equals(windowStatus)) throw new IllegalStateException("supplement window closed");

        Map<Long, Integer> current = currentSupplementTeacherPermissions(batchId);
        for (Map.Entry<Long, Integer> entry : current.entrySet()) {
            if (!teacherIds.contains(entry.getKey())) {
                jdbc.update("UPDATE supplement_teacher SET revoked_at = UTC_TIMESTAMP(3), row_version = row_version + 1 " +
                    "WHERE supplement_window_id = ? AND batch_teacher_quota_id = " +
                    "(SELECT id FROM batch_teacher_quota WHERE batch_id = ? AND teacher_id = ?) " +
                    "AND permission_version = ? AND revoked_at IS NULL", windowId, batchId, entry.getKey(), entry.getValue());
            }
        }
        for (Long teacherId : teacherIds) {
            if (current.containsKey(teacherId)) continue;
            Integer latest = jdbc.queryForObject("SELECT COALESCE(MAX(permission_version), 0) FROM supplement_teacher " +
                "WHERE supplement_window_id = ? AND batch_teacher_quota_id = " +
                "(SELECT id FROM batch_teacher_quota WHERE batch_id = ? AND teacher_id = ?)",
                Integer.class, windowId, batchId, teacherId);
            jdbc.update("INSERT INTO supplement_teacher(supplement_window_id, batch_teacher_quota_id, " +
                "permission_version, allowed_from, granted_by, reason) SELECT ?, id, ?, UTC_TIMESTAMP(3), ?, ? " +
                "FROM batch_teacher_quota WHERE batch_id = ? AND teacher_id = ?", windowId,
                (latest == null ? 0 : latest.intValue()) + 1, actorId, reason, batchId, teacherId);
        }
    }

    public void incrementBatchVersion(Long batchId) {
        jdbc.update("UPDATE selection_batch SET row_version = row_version + 1 WHERE id = ?", batchId);
    }

    public void publishStages(Long batchId) {
        jdbc.update("UPDATE batch_stage SET effective_start_at = planned_start_at, effective_end_at = planned_end_at, " +
            "stage_status = IF(stage_code = 'SUPPLEMENT' AND planned_start_at IS NULL, 'NOT_SCHEDULED', 'NOT_STARTED'), " +
            "row_version = row_version + 1 WHERE batch_id = ?", batchId);
        jdbc.update("UPDATE supplement_window SET effective_start_at = planned_start_at, " +
            "effective_end_at = planned_end_at, window_status = 'PLANNED', row_version = row_version + 1 " +
            "WHERE batch_id = ?", batchId);
    }

    public int countConfiguredQuotas(Long batchId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM batch_teacher_quota WHERE batch_id = ?",
            Integer.class, batchId);
        return count == null ? 0 : count.intValue();
    }

    /**
     * 构造管理员名额表格。
     *
     * <p>只列出当前有效导师；名额以 LEFT JOIN 展示尚未配置的导师，范围状态读取最新版本和唯一冻结槽位。
     * 这是只读展示投影，不替代写操作前的资格检查与名额行锁。</p>
     */
    public List<BatchTeacherQuotaVO> listTeacherQuotas(Long batchId, Long collegeId, Long academicYearId) {
        String sql = "SELECT t.id AS teacher_id, t.employee_no, t.full_name, p.review_status, " +
            "CONCAT('annual_eligibility_id=', ae.id, '; status=ELIGIBLE; account=ACTIVE') AS eligibility_basis, " +
            "q.quota_limit, COALESCE(q.occupied_count,0) AS occupied_count, COALESCE(q.row_version,0) AS row_version, " +
            "(sv.id IS NOT NULL AND sv.scope_source = 'TEACHER') AS scope_configured, " +
            "(ss.scope_version_id IS NOT NULL) AS scope_frozen " +
            "FROM teacher t JOIN account a ON a.id = t.account_id AND a.account_status = 'ACTIVE' " +
            "JOIN teacher_public_profile_version p ON p.id = t.current_public_profile_version_id " +
            "AND p.review_status = 'APPROVED' AND p.published_at IS NOT NULL " +
            "JOIN annual_eligibility_slot aes ON aes.academic_year_id = ? AND aes.teacher_id = t.id " +
            "JOIN annual_eligibility ae ON ae.id = aes.eligibility_id AND ae.eligibility_status = 'ELIGIBLE' " +
            "LEFT JOIN batch_teacher_quota q ON q.batch_id = ? AND q.teacher_id = t.id " +
            "LEFT JOIN teacher_application_scope_version sv ON sv.batch_id = ? AND sv.teacher_id = t.id " +
            "AND sv.version_no = (SELECT MAX(sv2.version_no) FROM teacher_application_scope_version sv2 " +
            "WHERE sv2.batch_id = sv.batch_id AND sv2.teacher_id = sv.teacher_id) " +
            "LEFT JOIN teacher_application_scope_slot ss ON ss.batch_id = ? AND ss.teacher_id = t.id " +
            "WHERE t.college_id = ? ORDER BY t.full_name, t.employee_no, t.id";
        return jdbc.query(sql, (rs, n) -> {
            int occupied = rs.getInt("occupied_count");
            int rawLimit = rs.getInt("quota_limit");
            Integer limit = rs.wasNull() ? null : Integer.valueOf(rawLimit);
            return new BatchTeacherQuotaVO(rs.getLong("teacher_id"), rs.getString("employee_no"),
                rs.getString("full_name"), rs.getString("review_status"), rs.getString("eligibility_basis"),
                limit, occupied, limit == null ? 0 : Math.max(0, limit.intValue() - occupied),
                rs.getLong("row_version"), rs.getBoolean("scope_configured"), rs.getBoolean("scope_frozen"));
        }, academicYearId, batchId, batchId, batchId, collegeId);
    }

    /** 使用冻结名单作为唯一分母，按当前匹配来源、补选和名额投影批次统计。 */
    public BatchStatisticsVO findBatchStatistics(SelectionBatchEntity batch) {
        Long batchId = batch.getId();
        Map<String, Object> counts = jdbc.queryForMap("SELECT COUNT(*) AS roster_count, " +
            "COALESCE(SUM(CASE WHEN match_status = 'MATCHED' THEN 1 ELSE 0 END), 0) AS matched_count, " +
            "COALESCE(SUM(CASE WHEN match_status = 'UNMATCHED' THEN 1 ELSE 0 END), 0) AS unmatched_count, " +
            "COALESCE(SUM(CASE WHEN match_status = 'UNMATCHED' AND match_reason IN ('ROUND3_EXHAUSTED','PREFERENCE_EXHAUSTED') THEN 1 ELSE 0 END), 0) AS normal_unmatched_count, " +
            "COALESCE(SUM(CASE WHEN match_status = 'UNMATCHED' AND match_reason = 'NOT_SUBMITTED' THEN 1 ELSE 0 END), 0) AS not_submitted_count, " +
            "COALESCE(SUM(CASE WHEN match_status = 'UNMATCHED' AND match_reason = 'ALL_REMAINING_PREFERENCES_SKIPPED' THEN 1 ELSE 0 END), 0) AS skipped_count, " +
            "COALESCE(SUM(CASE WHEN match_status = 'UNMATCHED' AND match_reason = 'IDENTITY_CORRECTION' THEN 1 ELSE 0 END), 0) AS identity_correction_count, " +
            "COALESCE(SUM(CASE WHEN match_status = 'UNMATCHED' AND match_reason = 'RELATION_REVOKED' THEN 1 ELSE 0 END), 0) AS relation_correction_count, " +
            "COALESCE(SUM(CASE WHEN match_status = 'UNMATCHED' AND match_reason = 'BATCH_CANCELLED' THEN 1 ELSE 0 END), 0) AS cancelled_count " +
            "FROM batch_student WHERE batch_id = ?", batchId);
        Map<String, Object> quota = jdbc.queryForMap("SELECT COALESCE(SUM(quota_limit),0) AS quota_limit, " +
            "COALESCE(SUM(occupied_count),0) AS occupied_count FROM batch_teacher_quota WHERE batch_id = ?", batchId);
        Long supplementAdmitted = jdbc.queryForObject("SELECT COUNT(*) FROM supplement_application application " +
            "JOIN batch_student participant ON participant.id = application.batch_student_id " +
            "WHERE participant.batch_id = ? AND application.application_status = 'ADMITTED'", Long.class, batchId);
        Long supplementStillUnmatched = null;
        if (batch.isSupplementPlanned() && "COMPLETED".equals(batch.getStatus()) &&
            "CLOSED".equals(supplementWindowStatus(batchId))) {
            supplementStillUnmatched = number(counts, "unmatched_count");
        }
        List<BatchStatisticsVO.RoundStatistics> rounds = new ArrayList<BatchStatisticsVO.RoundStatistics>();
        for (int roundNo = 1; roundNo <= 3; roundNo++) {
            Map<String, Object> round = jdbc.queryForMap("SELECT stage.stage_status, " +
                "COALESCE(SUM(CASE WHEN application.application_status = 'IN_REVIEW' THEN 1 ELSE 0 END),0) AS pending_count, " +
                "COALESCE(SUM(CASE WHEN application.application_status = 'ADMITTED' THEN 1 ELSE 0 END),0) AS admitted_count, " +
                "COALESCE(SUM(CASE WHEN application.application_status = 'NOT_ADMITTED' THEN 1 ELSE 0 END),0) AS not_admitted_count, " +
                "COALESCE(SUM(CASE WHEN application.application_status = 'CANCELLED_BY_BATCH' THEN 1 ELSE 0 END),0) AS cancelled_count " +
                "FROM batch_stage stage LEFT JOIN round_application application ON application.stage_id = stage.id " +
                "WHERE stage.batch_id = ? AND stage.stage_code = ? GROUP BY stage.stage_status", batchId, "ROUND_" + roundNo);
            Long skipped = jdbc.queryForObject("SELECT COUNT(DISTINCT event.batch_student_id) FROM student_match_event event " +
                "JOIN batch_student participant ON participant.id = event.batch_student_id " +
                "WHERE participant.batch_id = ? AND event.reason_code = 'ALL_REMAINING_PREFERENCES_SKIPPED' " +
                "AND event.actual_round = ?", Long.class, batchId, Integer.valueOf(roundNo));
            rounds.add(new BatchStatisticsVO.RoundStatistics(roundNo, (String) round.get("stage_status"),
                number(round, "pending_count"), number(round, "admitted_count"), number(round, "not_admitted_count"),
                skipped == null ? 0L : skipped.longValue(), number(round, "cancelled_count")));
        }
        Long frozenDenominator = batch.getFrozenRosterAt() == null ? null : number(counts, "roster_count");
        return new BatchStatisticsVO(batchId, frozenDenominator, number(counts, "matched_count"),
            number(counts, "unmatched_count"), number(counts, "normal_unmatched_count"),
            number(counts, "not_submitted_count"), number(counts, "skipped_count"),
            number(counts, "identity_correction_count"), number(counts, "relation_correction_count"),
            number(counts, "cancelled_count"), supplementAdmitted == null ? 0L : supplementAdmitted.longValue(),
            supplementStillUnmatched, number(quota, "quota_limit"), number(quota, "occupied_count"), rounds);
    }

    public String supplementWindowStatus(Long batchId) {
        try { return jdbc.queryForObject("SELECT window_status FROM supplement_window WHERE batch_id = ?", String.class, batchId); }
        catch (EmptyResultDataAccessException ex) { return null; }
    }

    private static long number(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    public Optional<BatchTeacherQuotaVO> findTeacherQuotaView(Long batchId, Long collegeId,
        Long academicYearId, Long teacherId) {
        for (BatchTeacherQuotaVO row : listTeacherQuotas(batchId, collegeId, academicYearId)) {
            if (teacherId.equals(row.getTeacherId())) return Optional.of(row);
        }
        return Optional.empty();
    }

    /** 查询导师当前是否满足批次的账号、公开资料、学院及年度资格条件，并生成资格依据快照。 */
    public Optional<String> teacherEligibilityBasis(Long batchId, Long teacherId) {
        String sql = "SELECT CONCAT('annual_eligibility_id=', ae.id, '; status=ELIGIBLE; account=ACTIVE') " +
            "FROM selection_batch b JOIN teacher t ON t.college_id = b.college_id AND t.id = ? " +
            "JOIN account a ON a.id = t.account_id AND a.account_status = 'ACTIVE' " +
            "JOIN teacher_public_profile_version p ON p.id = t.current_public_profile_version_id " +
            "AND p.review_status = 'APPROVED' AND p.published_at IS NOT NULL " +
            "JOIN annual_eligibility_slot aes ON aes.academic_year_id = b.academic_year_id " +
            "AND aes.teacher_id = t.id JOIN annual_eligibility ae ON ae.id = aes.eligibility_id " +
            "AND ae.eligibility_status = 'ELIGIBLE' WHERE b.id = ?";
        try { return Optional.of(jdbc.queryForObject(sql, String.class, teacherId, batchId)); }
        catch (EmptyResultDataAccessException ex) { return Optional.empty(); }
    }

    /** 锁定批次/导师名额账户；录取占用和管理员上限调整必须围绕此行串行执行。 */
    public Optional<TeacherQuotaEntity> lockQuota(Long batchId, Long teacherId) {
        try {
            return Optional.of(jdbc.queryForObject("SELECT id, batch_id, teacher_id, eligibility_basis, " +
                "quota_limit, occupied_count, row_version FROM batch_teacher_quota " +
                "WHERE batch_id = ? AND teacher_id = ? FOR UPDATE", (rs, n) -> new TeacherQuotaEntity(
                rs.getLong("id"), rs.getLong("batch_id"), rs.getLong("teacher_id"),
                rs.getString("eligibility_basis"), rs.getInt("quota_limit"), rs.getInt("occupied_count"),
                rs.getLong("row_version")), batchId, teacherId));
        } catch (EmptyResultDataAccessException ex) { return Optional.empty(); }
    }

    public long insertQuota(Long batchId, Long teacherId, String basis, int limit) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("INSERT INTO batch_teacher_quota " +
                "(batch_id, teacher_id, eligibility_basis, quota_limit, occupied_count) VALUES (?, ?, ?, ?, 0)",
                Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId); ps.setLong(2, teacherId); ps.setString(3, basis); ps.setInt(4, limit); return ps;
        }, key);
        Number id = key.getKey();
        if (id == null) throw new IllegalStateException("batch_teacher_quota insert returned no id");
        return id.longValue();
    }

    /** 以行版本和占用下限执行条件更新；受影响行数为 0 表示版本过期或上限不足。 */
    public boolean updateQuota(Long batchId, Long teacherId, long expectedVersion, String basis, int limit) {
        return jdbc.update("UPDATE batch_teacher_quota SET eligibility_basis = ?, quota_limit = ?, " +
            "row_version = row_version + 1 WHERE batch_id = ? AND teacher_id = ? AND row_version = ? " +
            "AND ? >= occupied_count", basis, limit, batchId, teacherId, expectedVersion, limit) == 1;
    }

    /**
     * 将填报开始时符合条件的学生写入批次冻结名单。
     *
     * <p>要求账号启用且学院/学年资格为 ELIGIBLE，并排除同学年已有有效关系槽位的学生；资格依据写入快照后，
     * 后续人员目录变化不会重算该批次分母。</p>
     */
    public int insertEligibleRoster(Long batchId, Long collegeId, Long academicYearId) {
        return jdbc.update("INSERT INTO batch_student(batch_id, student_id, eligibility_snapshot, " +
            "account_enabled_snapshot, eligibility_basis, preference_status) " +
            "SELECT ?, s.id, 'ELIGIBLE', TRUE, CONCAT('annual_eligibility_id=', ae.id, '; status=ELIGIBLE; " +
            "account=ACTIVE; no_existing_year_match=TRUE'), 'NOT_SUBMITTED' " +
            "FROM student s JOIN account a ON a.id = s.account_id AND a.account_status = 'ACTIVE' " +
            "JOIN annual_eligibility_slot aes ON aes.academic_year_id = ? AND aes.college_id = s.college_id " +
            "AND aes.student_id = s.id " +
            "JOIN annual_eligibility ae ON ae.id = aes.eligibility_id AND ae.college_id = s.college_id " +
            "AND ae.eligibility_status = 'ELIGIBLE' " +
            "LEFT JOIN student_year_match_slot sys ON sys.academic_year_id = ? AND sys.student_id = s.id " +
            "WHERE s.college_id = ? AND sys.student_id IS NULL", batchId, academicYearId,
            academicYearId, collegeId);
    }

    public List<Long> listBatchTeacherIds(Long batchId) {
        return jdbc.query("SELECT teacher_id FROM batch_teacher_quota WHERE batch_id = ? ORDER BY teacher_id",
            (rs, n) -> rs.getLong(1), batchId);
    }

    public List<MajorVO> listActiveMajors(Long collegeId) {
        return jdbc.query("SELECT id, college_id, major_code, name, is_active, valid_from, valid_to, row_version " +
            "FROM major WHERE college_id = ? AND is_active = TRUE ORDER BY major_code, id",
            (rs, n) -> new MajorVO(rs.getLong("id"), rs.getLong("college_id"), rs.getString("major_code"),
                rs.getString("name"), rs.getBoolean("is_active"), date(rs.getDate("valid_from")),
                date(rs.getDate("valid_to")), rs.getLong("row_version")), collegeId);
    }

    public boolean activeMajorsBelongToCollege(Long collegeId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return true;
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM major WHERE college_id = ? AND is_active = TRUE " +
            "AND id IN (" + placeholders(ids.size()) + ")", Integer.class, concat(collegeId, ids));
        return count != null && count.intValue() == ids.size();
    }

    /** 查询导师最新范围版本；写入新版本时锁定当前行以串行化版本递增。 */
    public Optional<TeacherScopeVersionEntity> latestScope(Long batchId, Long teacherId, boolean forUpdate) {
        String sql = "SELECT id, batch_id, teacher_id, version_no, allowed_degree_mask, configured_by, " +
            "configured_at, frozen_at, scope_source, default_all_applied FROM teacher_application_scope_version " +
            "WHERE batch_id = ? AND teacher_id = ? ORDER BY version_no DESC LIMIT 1" + (forUpdate ? " FOR UPDATE" : "");
        try { return Optional.of(jdbc.queryForObject(sql, (rs, n) -> new TeacherScopeVersionEntity(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getLong("teacher_id"), rs.getInt("version_no"),
            rs.getInt("allowed_degree_mask"), rs.getLong("configured_by"), rs.getTimestamp("configured_at"),
            rs.getTimestamp("frozen_at"), rs.getString("scope_source"), rs.getBoolean("default_all_applied")),
            batchId, teacherId)); }
        catch (EmptyResultDataAccessException ex) { return Optional.empty(); }
    }

    public List<Long> listScopeMajorIds(Long scopeVersionId) {
        return jdbc.query("SELECT major_id FROM teacher_allowed_major WHERE scope_version_id = ? ORDER BY major_id",
            (rs, n) -> rs.getLong(1), scopeVersionId);
    }

    public boolean isScopeFrozen(Long batchId, Long teacherId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM teacher_application_scope_slot " +
            "WHERE batch_id = ? AND teacher_id = ?", Integer.class, batchId, teacherId);
        return count != null && count.intValue() > 0;
    }

    /** 插入只追加的范围版本主行；专业快照和冻结槽位由调用方在同一事务补齐。 */
    public Long insertScopeVersion(Long batchId, Long teacherId, int versionNo, int degreeMask,
        Long configuredBy, String source, boolean defaultAll) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("INSERT INTO teacher_application_scope_version " +
                "(batch_id, teacher_id, version_no, allowed_degree_mask, configured_by, configured_at, " +
                "scope_source, default_all_applied) VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(3), ?, ?)",
                Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId); ps.setLong(2, teacherId); ps.setInt(3, versionNo); ps.setInt(4, degreeMask);
            ps.setLong(5, configuredBy); ps.setString(6, source); ps.setBoolean(7, defaultAll); return ps;
        }, key);
        Number id = key.getKey();
        if (id == null) throw new IllegalStateException("teacher scope insert returned no id");
        return id.longValue();
    }

    public void insertAllowedMajors(Long scopeVersionId, List<MajorVO> majors) {
        for (MajorVO major : majors) {
            jdbc.update("INSERT INTO teacher_allowed_major(scope_version_id, major_id, major_code_snapshot, " +
                "major_name_snapshot) VALUES (?, ?, ?, ?)", scopeVersionId, major.getId(),
                major.getMajorCode(), major.getName());
        }
    }

    /** 标记范围版本冻结并占用唯一槽位；唯一键确保同一导师/批次只有一个冻结版本。 */
    public void freezeScopeVersion(Long scopeVersionId, Long batchId, Long teacherId) {
        jdbc.update("UPDATE teacher_application_scope_version SET frozen_at = UTC_TIMESTAMP(3), " +
            "row_version = row_version + 1 WHERE id = ? AND frozen_at IS NULL", scopeVersionId);
        jdbc.update("INSERT INTO teacher_application_scope_slot(batch_id, teacher_id, scope_version_id, frozen_at) " +
            "VALUES (?, ?, ?, UTC_TIMESTAMP(3))", batchId, teacherId, scopeVersionId);
    }

    /** 保存发布时采用的规则文字和文档版本，供后续批次处理追溯当时基线。 */
    public void publishRuleSnapshot(Long batchId, Long actorId) {
        Integer current = jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0) FROM batch_rule_snapshot " +
            "WHERE batch_id = ?", Integer.class, batchId);
        jdbc.update("INSERT INTO batch_rule_snapshot(batch_id, version_no, scope_text, eligibility_basis, " +
            "min_preferences, max_preferences, round_rule_version, source_document_versions, published_by, " +
            "published_at) VALUES (?, ?, ?, ?, 1, 3, 'SPLIT_ROUND_V1', ?, ?, UTC_TIMESTAMP(3))", batchId,
            (current == null ? 0 : current.intValue()) + 1,
            "学生只能填报本批次有名额且同时满足导师冻结学位类型与专业范围的导师。",
            "填报窗口开始时冻结学院内账号启用且学年资格为 ELIGIBLE 的学生，排除学年内已有有效关系者。",
            "requirements 0.25; business-rules 0.15; state-machine 0.12; api-design 1.0; TODO-41/43/44/47/57/58",
            actorId);
    }

    /** 创建 IN_PROGRESS 操作行；成功路径稍后按生成的 operationId 精确完成此行。 */
    public long insertOperation(Long actorId, String actorKind, String action, Long collegeId, Long batchId,
                                String requestId, String fingerprint) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("INSERT INTO business_operation " +
                "(actor_account_id, actor_kind, action_code, college_id, batch_id, request_id, " +
                "request_fingerprint, result_code, started_at, completed_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, 'IN_PROGRESS', UTC_TIMESTAMP(3), NULL)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, actorId); ps.setString(2, actorKind); ps.setString(3, action);
            ps.setObject(4, collegeId); ps.setObject(5, batchId); ps.setString(6, requestId);
            ps.setString(7, fingerprint); return ps;
        }, key);
        Number id = key.getKey();
        if (id == null) throw new IllegalStateException("business_operation insert returned no id");
        return id.longValue();
    }

    public void insertRunningSlot(Long collegeId, Long academicYearId, Long batchId) {
        jdbc.update("INSERT INTO batch_running_slot(college_id, academic_year_id, batch_id, reserved_at) " +
            "VALUES (?, ?, ?, UTC_TIMESTAMP(3))", collegeId, academicYearId, batchId);
    }

    /** 读取幂等键对应的原操作；请求指纹比较和处理中状态处理由 Service 执行。 */
    public Optional<OperationRecord> findOperation(Long actorId, String action, String requestId) {
        if (requestId == null) return Optional.empty();
        try {
            return Optional.of(jdbc.queryForObject("SELECT id, batch_id, request_fingerprint, result_code " +
                "FROM business_operation WHERE actor_account_id = ? AND action_code = ? AND request_id = ?",
                (rs, n) -> new OperationRecord(rs.getLong("id"), rs.getLong("batch_id"),
                    rs.getString("request_fingerprint"), rs.getString("result_code")), actorId, action, requestId));
        } catch (EmptyResultDataAccessException ex) { return Optional.empty(); }
    }

    /** 只完成指定的 IN_PROGRESS 行，避免并发命令误改同一操作者的其他操作记录。 */
    public void completeOperation(long operationId) {
        jdbc.update("UPDATE business_operation SET result_code = 'OK', completed_at = UTC_TIMESTAMP(3) " +
            "WHERE id = ? AND result_code = 'IN_PROGRESS'", operationId);
    }

    public void insertAudit(Long actorId, String actorKind, String actorRole, String scopeBasis,
        String objectType, Long objectId, String action, String beforeValues, String afterValues,
        String reason, Long operationId) {
        jdbc.update("INSERT INTO audit_event(actor_account_id, actor_kind, actor_role, scope_basis, object_type, " +
            "object_id, action_code, before_values_text, after_values_text, reason, occurred_at, business_operation_id) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(3), ?)", actorId, actorKind, actorRole,
            scopeBasis, objectType, objectId, action, beforeValues, afterValues, reason, operationId);
    }

    public void insertLifecycleEvent(Long batchId, String action, String oldStatus, String newStatus,
        Long stageId, Long actorId, String actorKind, String reason, Long operationId) {
        jdbc.update("INSERT INTO batch_lifecycle_event(batch_id, action_code, old_status, new_status, stage_id, " +
            "actor_account_id, actor_kind, reason, occurred_at, business_operation_id) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(3), ?)", batchId, action, oldStatus, newStatus,
            stageId, actorId, actorKind, reason, operationId);
    }

    /** 暂停事件额外保存数据库暂停起点和被冻结阶段，供恢复时精确计算窗口平移量。 */
    public void insertPauseLifecycleEvent(Long batchId, Long stageId, String frozenStageCode, Long actorId,
        String reason, Long operationId) {
        jdbc.update("INSERT INTO batch_lifecycle_event(batch_id, action_code, old_status, new_status, stage_id, " +
            "actor_account_id, actor_kind, reason, occurred_at, pause_started_at, frozen_stage_code, business_operation_id) " +
            "VALUES (?, 'PAUSE', 'ACTIVE', 'PAUSED', ?, ?, 'ADMIN', ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), ?, ?)",
            batchId, stageId, actorId, reason, frozenStageCode, operationId);
    }

    /** 锁定尚未恢复的暂停事件；一批次只允许存在一条未闭合暂停周期。 */
    public Optional<PauseEvent> lockOpenPauseEvent(Long batchId) {
        List<PauseEvent> rows = jdbc.query("SELECT id, pause_started_at FROM batch_lifecycle_event " +
            "WHERE batch_id = ? AND action_code IN ('PAUSE', 'ROUND_REOPEN_PAUSE') AND pause_ended_at IS NULL " +
            "ORDER BY id DESC LIMIT 1 FOR UPDATE", (rs, n) ->
            new PauseEvent(rs.getLong("id"), rs.getTimestamp("pause_started_at")), batchId);
        return rows.isEmpty() ? Optional.<PauseEvent>empty() : Optional.of(rows.get(0));
    }

    /** 结束暂停周期并使用相同数据库时间平移所有未结阶段和尚未关闭的补选窗口。 */
    public int finishPauseAndShiftSchedule(Long batchId, long pauseEventId) {
        int completed = jdbc.update("UPDATE batch_lifecycle_event SET pause_ended_at = UTC_TIMESTAMP(3), " +
            "pause_duration_seconds = TIMESTAMPDIFF(SECOND, pause_started_at, UTC_TIMESTAMP(3)) " +
            "WHERE id = ? AND batch_id = ? AND action_code IN ('PAUSE', 'ROUND_REOPEN_PAUSE') " +
            "AND pause_ended_at IS NULL", pauseEventId, batchId);
        if (completed != 1) return 0;
        jdbc.update("UPDATE batch_stage stage JOIN batch_lifecycle_event event ON event.id = ? " +
            "SET stage.effective_start_at = IF(stage.effective_start_at IS NULL, NULL, " +
            "TIMESTAMPADD(MICROSECOND, TIMESTAMPDIFF(MICROSECOND, event.pause_started_at, event.pause_ended_at), stage.effective_start_at)), " +
            "stage.effective_end_at = IF(stage.effective_end_at IS NULL, NULL, " +
            "TIMESTAMPADD(MICROSECOND, TIMESTAMPDIFF(MICROSECOND, event.pause_started_at, event.pause_ended_at), stage.effective_end_at)), " +
            "stage.row_version = stage.row_version + 1 WHERE stage.batch_id = ? " +
            "AND stage.stage_status NOT IN ('CLOSED', 'NOT_SCHEDULED')", pauseEventId, batchId);
        jdbc.update("UPDATE supplement_window supplement JOIN batch_lifecycle_event event ON event.id = ? " +
            "SET supplement.effective_start_at = TIMESTAMPADD(MICROSECOND, " +
            "TIMESTAMPDIFF(MICROSECOND, event.pause_started_at, event.pause_ended_at), supplement.effective_start_at), " +
            "supplement.effective_end_at = TIMESTAMPADD(MICROSECOND, " +
            "TIMESTAMPDIFF(MICROSECOND, event.pause_started_at, event.pause_ended_at), supplement.effective_end_at), " +
            "supplement.row_version = supplement.row_version + 1 WHERE supplement.batch_id = ? " +
            "AND supplement.window_status IN ('PLANNED', 'OPEN')", pauseEventId, batchId);
        return completed;
    }

    /** 把批次中仍在审核的申请结案，逐项写入不可覆盖的申请事件，并释放补选待处理槽位。 */
    public void cancelPendingApplications(Long batchId, Long actorId, long operationId) {
        jdbc.update("INSERT INTO application_event(object_type, object_id, execution_cycle, action_code, old_status, " +
            "new_status, close_reason, actor_kind, actor_account_id, occurred_at, business_operation_id) " +
            "SELECT 'ROUND_APPLICATION', application.id, application.execution_cycle, 'BATCH_CANCELLED', " +
            "application.application_status, 'CANCELLED_BY_BATCH', 'BATCH_CANCELLED', 'ADMIN', ?, UTC_TIMESTAMP(3), ? " +
            "FROM round_application application JOIN batch_stage stage ON stage.id = application.stage_id " +
            "WHERE stage.batch_id = ? AND application.application_status = 'IN_REVIEW'", actorId, operationId, batchId);
        jdbc.update("UPDATE round_application application JOIN batch_stage stage ON stage.id = application.stage_id " +
            "SET application.application_status = 'CANCELLED_BY_BATCH', application.close_reason = 'BATCH_CANCELLED', " +
            "application.decided_at = UTC_TIMESTAMP(3), application.row_version = application.row_version + 1 " +
            "WHERE stage.batch_id = ? AND application.application_status = 'IN_REVIEW'", batchId);
        jdbc.update("INSERT INTO application_event(object_type, object_id, execution_cycle, action_code, old_status, " +
            "new_status, close_reason, actor_kind, actor_account_id, occurred_at, business_operation_id) " +
            "SELECT 'SUPPLEMENT_APPLICATION', application.id, NULL, 'BATCH_CANCELLED', application.application_status, " +
            "'CANCELLED_BY_BATCH', 'BATCH_CANCELLED', 'ADMIN', ?, UTC_TIMESTAMP(3), ? " +
            "FROM supplement_application application WHERE application.batch_student_id IN " +
            "(SELECT participant.id FROM batch_student participant WHERE participant.batch_id = ?) " +
            "AND application.application_status = 'IN_REVIEW'", actorId, operationId, batchId);
        jdbc.update("DELETE pending_slot FROM student_pending_supplement_slot pending_slot " +
            "JOIN supplement_application application ON application.id = pending_slot.supplement_application_id " +
            "JOIN batch_student participant ON participant.id = application.batch_student_id " +
            "WHERE participant.batch_id = ? AND application.application_status = 'IN_REVIEW'", batchId);
        jdbc.update("UPDATE supplement_application application JOIN batch_student participant " +
            "ON participant.id = application.batch_student_id SET application.application_status = 'CANCELLED_BY_BATCH', " +
            "application.close_reason = 'BATCH_CANCELLED', application.decided_by = ?, " +
            "application.decided_at = UTC_TIMESTAMP(3), application.row_version = application.row_version + 1 " +
            "WHERE participant.batch_id = ? AND application.application_status = 'IN_REVIEW'", actorId, batchId);
    }

    /** 取消批次时对全部未匹配参与者记录批次取消来源；已有锁定关系与其名额不受影响。 */
    public void markUnmatchedByBatchCancellation(Long batchId, long operationId) {
        jdbc.update("INSERT INTO student_match_event(batch_student_id, old_status, new_status, reason_code, " +
            "source_type, source_id, occurred_at, business_operation_id) " +
            "SELECT id, match_status, 'UNMATCHED', 'BATCH_CANCELLED', 'BATCH_LIFECYCLE', ?, UTC_TIMESTAMP(3), ? " +
            "FROM batch_student WHERE batch_id = ? AND (match_status IS NULL OR match_status <> 'MATCHED')",
            batchId, operationId, batchId);
        jdbc.update("UPDATE batch_student SET match_status = 'UNMATCHED', match_reason = 'BATCH_CANCELLED', " +
            "match_changed_at = UTC_TIMESTAMP(3), row_version = row_version + 1 " +
            "WHERE batch_id = ? AND (match_status IS NULL OR match_status <> 'MATCHED')", batchId);
    }

    /** 批次取消後關閉尚未結案階段及補選窗口；原排期與歷史階段不刪除。 */
    public void closeRemainingBatchStages(Long batchId) {
        jdbc.update("UPDATE batch_stage SET stage_status = 'CLOSED', actual_closed_at = UTC_TIMESTAMP(3), " +
            "close_reason = 'BATCH_CANCELLED', row_version = row_version + 1 " +
            "WHERE batch_id = ? AND stage_status NOT IN ('CLOSED', 'NOT_SCHEDULED')", batchId);
        jdbc.update("UPDATE supplement_window SET window_status = 'CLOSED', actual_closed_at = UTC_TIMESTAMP(3), " +
            "close_reason = 'BATCH_CANCELLED', row_version = row_version + 1 " +
            "WHERE batch_id = ? AND window_status IN ('PLANNED', 'OPEN')", batchId);
    }

    /** 已取消批次立即归还学院/学年运行槽位。 */
    public int releaseRunningSlot(Long batchId) {
        return jdbc.update("DELETE FROM batch_running_slot WHERE batch_id = ?", batchId);
    }

    public static final class PauseEvent {
        private final long id;
        private final Timestamp startedAt;
        public PauseEvent(long id, Timestamp startedAt) { this.id = id; this.startedAt = startedAt; }
        public long getId() { return id; }
        public Timestamp getStartedAt() { return startedAt; }
    }

    public int updateBatchMetadataVersion(Long batchId, long expectedVersion, String name, String appendReason) {
        return jdbc.update("UPDATE selection_batch SET name = ?, append_reason = ?, row_version = row_version + 1 " +
            "WHERE id = ? AND row_version = ?", name, appendReason, batchId, expectedVersion);
    }

    public int updateBatchState(Long batchId, String oldStatus, String newStatus, String action) {
        String timestampField = "PUBLISH".equals(action) ? "published_at" :
            ("START".equals(action) ? "started_at" :
                ("CANCEL".equals(action) ? "cancelled_at" :
                    ("ARCHIVE".equals(action) ? "archived_at" : ("UNARCHIVE".equals(action) ? "archived_at" : null))));
        String stamp = timestampField == null ? "" : ", " + timestampField + " = UTC_TIMESTAMP(3)";
        if ("UNARCHIVE".equals(action)) stamp = ", archived_at = NULL";
        return jdbc.update("UPDATE selection_batch SET batch_status = ?, row_version = row_version + 1" + stamp +
            " WHERE id = ? AND batch_status = ?", newStatus, batchId, oldStatus);
    }

    public int updateBatchStage(Long batchId, String stageCode, String status, boolean setCurrent) {
        int changed = jdbc.update("UPDATE batch_stage SET stage_status = ?, " +
            "actual_started_at = IF(? = 'OPEN', COALESCE(actual_started_at, UTC_TIMESTAMP(3)), actual_started_at), " +
            "row_version = row_version + 1 WHERE batch_id = ? AND stage_code = ?", status, status, batchId, stageCode);
        if (setCurrent) {
            Long stageId = stageId(batchId, stageCode);
            jdbc.update("UPDATE selection_batch SET current_stage_id = ?, row_version = row_version + 1 " +
                "WHERE id = ?", stageId, batchId);
        }
        return changed;
    }

    public boolean fillingWindowOpen(Long batchId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM batch_stage WHERE batch_id = ? " +
            "AND stage_code = 'FILLING' AND UTC_TIMESTAMP(3) >= effective_start_at " +
            "AND UTC_TIMESTAMP(3) < effective_end_at", Integer.class, batchId);
        return count != null && count.intValue() == 1;
    }

    public Timestamp fillingWindowStart(Long batchId) {
        try { return jdbc.queryForObject("SELECT effective_start_at FROM batch_stage WHERE batch_id = ? " +
            "AND stage_code = 'FILLING'", Timestamp.class, batchId); }
        catch (EmptyResultDataAccessException ex) { return null; }
    }

    public boolean fillingWindowEnded(Long batchId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM batch_stage WHERE batch_id = ? " +
            "AND stage_code = 'FILLING' AND UTC_TIMESTAMP(3) >= effective_end_at", Integer.class, batchId);
        return count != null && count.intValue() == 1;
    }

    /** 定时开窗条件：批次活动、尚未冻结、阶段等待且当前时刻已到开始但仍早于截止。 */
    public boolean fillingWindowDue(Long batchId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM batch_stage s JOIN selection_batch b " +
            "ON b.id = s.batch_id WHERE b.id = ? AND b.batch_status = 'ACTIVE' AND b.frozen_roster_at IS NULL " +
            "AND s.stage_code = 'FILLING' AND s.stage_status = 'WAITING_FILLING' " +
            "AND UTC_TIMESTAMP(3) >= s.effective_start_at AND UTC_TIMESTAMP(3) < s.effective_end_at",
            Integer.class, batchId);
        return count != null && count.intValue() == 1;
    }

    public List<Long> listActiveBatchIds() {
        return jdbc.query("SELECT id FROM selection_batch WHERE batch_status = 'ACTIVE' ORDER BY id",
            (rs, n) -> rs.getLong(1));
    }

    /**
     * 通过条件更新争抢一次性名单冻结权。
     *
     * <p>首次调用写入冻结时刻并插入名单，返回新增行数；已冻结则返回 -1，调用方可据此避免重复审计。</p>
     */
    public int freezeRosterSnapshot(Long batchId, Long collegeId, Long academicYearId) {
        return jdbc.update("UPDATE selection_batch SET frozen_roster_at = UTC_TIMESTAMP(3), " +
            "row_version = row_version + 1 WHERE id = ? AND frozen_roster_at IS NULL", batchId) == 1
            ? insertEligibleRoster(batchId, collegeId, academicYearId) : -1;
    }

    /** 只从指定导师自己的名额账户列出批次，提供本人范围页面所需的学院和填报时间信息。 */
    public List<TeacherScopeBatchOptionVO> listTeacherScopeBatches(Long teacherId) {
        return jdbc.query("SELECT b.id AS batch_id, b.college_id, c.name AS college_name, y.year_code, b.batch_code, b.name, " +
            "b.batch_status, COALESCE(s.effective_start_at, s.planned_start_at) AS effective_start_at, " +
            "(ss.scope_version_id IS NOT NULL) AS scope_frozen " +
            "FROM batch_teacher_quota q JOIN selection_batch b ON b.id = q.batch_id " +
            "JOIN college c ON c.id = b.college_id JOIN academic_year y ON y.id = b.academic_year_id " +
            "LEFT JOIN batch_stage s ON s.batch_id = b.id AND s.stage_code = 'FILLING' " +
            "LEFT JOIN teacher_application_scope_slot ss ON ss.batch_id = b.id AND ss.teacher_id = q.teacher_id " +
            "WHERE q.teacher_id = ? ORDER BY b.created_at DESC, b.id DESC", (rs, n) -> new TeacherScopeBatchOptionVO(
            rs.getLong("batch_id"), rs.getLong("college_id"), rs.getString("college_name"), rs.getString("year_code"),
            rs.getString("batch_code"), rs.getString("name"), rs.getString("batch_status"),
            instant(rs.getTimestamp("effective_start_at")), rs.getBoolean("scope_frozen")), teacherId);
    }

    public boolean teacherHasBatchQuota(Long batchId, Long teacherId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM batch_teacher_quota WHERE batch_id = ? " +
            "AND teacher_id = ?", Integer.class, batchId, teacherId);
        return count != null && count.intValue() == 1;
    }

    /** 使用数据库 UTC 检查范围编辑是否仍开放，并确认批次尚无冻结范围槽位。 */
    public boolean scopeEditTimeOpen(Long batchId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM selection_batch b JOIN batch_stage s " +
            "ON s.batch_id = b.id AND s.stage_code = 'FILLING' WHERE b.id = ? " +
            "AND b.batch_status IN ('DRAFT','SCHEDULED','ACTIVE') " +
            "AND (COALESCE(s.effective_start_at, s.planned_start_at) IS NULL OR " +
            "UTC_TIMESTAMP(3) < COALESCE(s.effective_start_at, s.planned_start_at)) AND NOT EXISTS (SELECT 1 FROM " +
            "teacher_application_scope_slot x WHERE x.batch_id = b.id)", Integer.class, batchId);
        return count != null && count.intValue() == 1;
    }

    public List<Long> listActiveBatchMajorIds(Long batchId, Long teacherId) {
        return jdbc.query("SELECT m.id FROM major m JOIN teacher t ON t.college_id = m.college_id " +
            "JOIN batch_teacher_quota q ON q.teacher_id = t.id AND q.batch_id = ? " +
            "WHERE t.id = ? AND m.is_active = TRUE ORDER BY m.id", (rs, n) -> rs.getLong(1), batchId, teacherId);
    }

    public List<MajorVO> findActiveMajorsByIds(Long collegeId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        return jdbc.query("SELECT id, college_id, major_code, name, is_active, valid_from, valid_to, row_version " +
            "FROM major WHERE college_id = ? AND is_active = TRUE AND id IN (" + placeholders(ids.size()) + ") " +
            "ORDER BY major_code, id", (rs, n) -> new MajorVO(rs.getLong("id"), rs.getLong("college_id"),
            rs.getString("major_code"), rs.getString("name"), rs.getBoolean("is_active"),
            date(rs.getDate("valid_from")), date(rs.getDate("valid_to")), rs.getLong("row_version")),
            concat(collegeId, ids));
    }

    public void insertScopeAudit(Long actorId, String actorKind, Long batchId, Long scopeId,
                                 String beforeValues, String afterValues) {
        Long collegeId = jdbc.queryForObject("SELECT college_id FROM selection_batch WHERE id = ?", Long.class, batchId);
        jdbc.update("INSERT INTO business_operation(actor_account_id, actor_kind, action_code, college_id, batch_id, " +
            "result_code, started_at, completed_at) VALUES (?, ?, 'TEACHER_SCOPE_SET', ?, ?, 'OK', " +
            "UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))", actorId, actorKind, collegeId, batchId);
        Long operationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO audit_event(actor_account_id, actor_kind, actor_role, scope_basis, object_type, " +
            "object_id, action_code, before_values_text, after_values_text, occurred_at, business_operation_id) " +
            "VALUES (?, ?, 'TEACHER', ?, 'TEACHER_APPLICATION_SCOPE', ?, 'TEACHER_SCOPE_SET', ?, ?, " +
            "UTC_TIMESTAMP(3), ?)", actorId, actorKind, "batchId=" + batchId, scopeId,
            beforeValues, afterValues, operationId);
    }

    public Optional<OperationRecord> findQuotaOperation(Long actorId, String requestId) {
        return findOperation(actorId, "BATCH_TEACHER_QUOTA_SET", requestId);
    }

    private static SelectionBatchSummaryVO summary(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SelectionBatchSummaryVO(rs.getLong("id"), rs.getLong("college_id"),
            rs.getString("college_name"), rs.getLong("academic_year_id"), rs.getString("year_code"),
            rs.getString("batch_code"), rs.getString("name"), rs.getString("batch_status"),
            rs.getBoolean("supplement_planned"), rs.getString("append_reason"),
            instant(rs.getTimestamp("published_at")), instant(rs.getTimestamp("started_at")),
            rs.getLong("row_version"));
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static String date(java.sql.Date value) { return value == null ? null : value.toString(); }
    private static String placeholders(int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) { if (i > 0) out.append(','); out.append('?'); }
        return out.toString();
    }
    private static Object[] concat(Object first, List<Long> rest) {
        Object[] values = new Object[rest.size() + 1]; values[0] = first;
        for (int i = 0; i < rest.size(); i++) values[i + 1] = rest.get(i);
        return values;
    }

    public static final class OperationRecord {
        private final Long id; private final Long batchId; private final String fingerprint; private final String resultCode;
        public OperationRecord(Long id, Long batchId, String fingerprint, String resultCode) {
            this.id = id; this.batchId = batchId; this.fingerprint = fingerprint; this.resultCode = resultCode;
        }
        public Long getId() { return id; }
        public Long getBatchId() { return batchId; }
        public String getFingerprint() { return fingerprint; }
        public String getResultCode() { return resultCode; }
    }
}
