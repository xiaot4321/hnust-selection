package cn.hnust.selection.repository;

import cn.hnust.selection.vo.StudentBatchSummaryVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class StudentBatchRepository {
    private final JdbcTemplate jdbcTemplate;

    public StudentBatchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final String ELIGIBILITY =
        "EXISTS (SELECT 1 FROM annual_eligibility eligibility " +
            "WHERE eligibility.academic_year_id = batch.academic_year_id " +
            "AND eligibility.college_id = student.college_id " +
            "AND eligibility.student_id = student.id AND eligibility.teacher_id IS NULL " +
            "AND eligibility.eligibility_status = 'ELIGIBLE' AND eligibility.valid_to IS NULL " +
            "AND (eligibility.valid_from IS NULL OR eligibility.valid_from <= UTC_TIMESTAMP(3)))";

    private static final String STAGE_JOIN =
        "LEFT JOIN batch_stage stage ON stage.id = COALESCE(batch.current_stage_id, " +
            "(SELECT candidate.id FROM batch_stage candidate WHERE candidate.batch_id = batch.id " +
            "ORDER BY candidate.stage_order, candidate.id LIMIT 1)) " +
        "LEFT JOIN supplement_window supplement ON supplement.batch_id = batch.id " +
        "LEFT JOIN preference_submission current_submission ON current_submission.id = batch_student.current_submission_id ";

    private static final String VISIBLE_WHERE =
        "batch.college_id = student.college_id AND batch.batch_status <> 'DRAFT' " +
            "AND (" + ELIGIBILITY + " OR batch_student.id IS NOT NULL)";

    public List<StudentBatchSummaryVO> findVisibleBatches(Long studentId, Long accountId, int pageNo, int pageSize) {
        long offset = ((long) pageNo - 1L) * pageSize;
        String sql = "SELECT batch.id AS batch_id, batch.name AS batch_name, year.id AS academic_year_id, " +
            "year.display_name AS academic_year_name, batch.batch_status, batch.supplement_planned, " +
            "stage.stage_code, " +
            "CASE WHEN batch.batch_status = 'SCHEDULED' THEN 'NOT_STARTED' " +
            "WHEN stage.stage_code = 'FILLING' AND COALESCE(stage.effective_start_at, stage.planned_start_at) > UTC_TIMESTAMP(3) " +
            "THEN 'WAITING_FILLING' ELSE stage.stage_status END AS stage_status, " +
            "DATE_FORMAT(CASE WHEN stage.stage_code = 'SUPPLEMENT' THEN COALESCE(supplement.effective_start_at, stage.effective_start_at, stage.planned_start_at) " +
            "ELSE COALESCE(stage.effective_start_at, stage.planned_start_at) END, '%Y-%m-%dT%H:%i:%s.%fZ') AS stage_start_at, " +
            "DATE_FORMAT(CASE WHEN stage.stage_code = 'SUPPLEMENT' THEN COALESCE(supplement.effective_end_at, stage.effective_end_at, stage.planned_end_at) " +
            "ELSE COALESCE(stage.effective_end_at, stage.planned_end_at) END, '%Y-%m-%dT%H:%i:%s.%fZ') AS stage_end_at, " +
            "COALESCE(batch_student.preference_status, 'NOT_SUBMITTED') AS preference_status, " +
            "batch_student.match_status, " +
            "CASE WHEN batch_student.match_status = 'UNMATCHED' THEN batch_student.match_reason ELSE NULL END AS match_reason, " +
            "student.classification_version AS identity_classification_version, batch_student.confirmed_classification_version, " +
            "IF(batch.batch_status = 'ACTIVE' AND account.account_status = 'ACTIVE' AND batch_student.id IS NOT NULL " +
            "AND stage.stage_code = 'FILLING' AND stage.stage_status = 'OPEN' AND COALESCE(stage.effective_start_at, stage.planned_start_at) <= UTC_TIMESTAMP(3) " +
            "AND COALESCE(stage.effective_end_at, stage.planned_end_at) > UTC_TIMESTAMP(3) " +
            "AND batch_student.eligibility_snapshot = 'ELIGIBLE' " +
            "AND (batch_student.confirmed_classification_version IS NULL OR batch_student.confirmed_classification_version <> student.classification_version), TRUE, FALSE) AS can_confirm_identity, " +
            "IF(batch.batch_status = 'ACTIVE' AND account.account_status = 'ACTIVE' AND batch_student.id IS NOT NULL " +
            "AND stage.stage_code = 'FILLING' AND stage.stage_status = 'OPEN' AND COALESCE(stage.effective_start_at, stage.planned_start_at) <= UTC_TIMESTAMP(3) " +
            "AND COALESCE(stage.effective_end_at, stage.planned_end_at) > UTC_TIMESTAMP(3) " +
            "AND batch_student.eligibility_snapshot = 'ELIGIBLE' " +
            "AND batch_student.confirmed_classification_version = student.classification_version " +
            "AND (batch_student.match_status IS NULL OR batch_student.match_status <> 'MATCHED') " +
            "AND batch_student.preference_status IN ('NOT_SUBMITTED','SUBMITTED','WITHDRAWN'), TRUE, FALSE) AS can_submit_preferences, " +
            "IF(batch.batch_status = 'ACTIVE' AND account.account_status = 'ACTIVE' AND batch_student.id IS NOT NULL " +
            "AND stage.stage_code = 'FILLING' AND stage.stage_status = 'OPEN' AND COALESCE(stage.effective_start_at, stage.planned_start_at) <= UTC_TIMESTAMP(3) " +
            "AND COALESCE(stage.effective_end_at, stage.planned_end_at) > UTC_TIMESTAMP(3) " +
            "AND batch_student.preference_status = 'SUBMITTED' AND current_submission.locked_at IS NULL, TRUE, FALSE) AS can_withdraw_preferences, " +
            "IF(batch.batch_status = 'ACTIVE' AND account.account_status = 'ACTIVE' AND batch_student.id IS NOT NULL " +
            "AND batch_student.match_status = 'UNMATCHED' AND supplement.window_status = 'OPEN' " +
            "AND supplement.effective_start_at <= UTC_TIMESTAMP(3) AND supplement.effective_end_at > UTC_TIMESTAMP(3) " +
            "AND NOT EXISTS (SELECT 1 FROM student_pending_supplement_slot pending WHERE pending.student_id = student.id), TRUE, FALSE) AS can_apply_supplement " +
            "FROM student student JOIN account account ON account.id = student.account_id " +
            "JOIN selection_batch batch ON batch.college_id = student.college_id " +
            "JOIN academic_year year ON year.id = batch.academic_year_id " +
            "LEFT JOIN batch_student batch_student ON batch_student.batch_id = batch.id AND batch_student.student_id = student.id " +
            STAGE_JOIN +
            "WHERE student.id = ? AND student.account_id = ? AND " + VISIBLE_WHERE + " " +
            "ORDER BY CASE batch.batch_status WHEN 'ACTIVE' THEN 0 WHEN 'SCHEDULED' THEN 1 WHEN 'PAUSED' THEN 2 ELSE 3 END, " +
            "year.year_code DESC, batch.id DESC LIMIT ? OFFSET ?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            StudentBatchSummaryVO item = new StudentBatchSummaryVO();
            item.setBatchId(rs.getLong("batch_id"));
            item.setBatchName(rs.getString("batch_name"));
            item.setAcademicYearId(rs.getLong("academic_year_id"));
            item.setAcademicYearName(rs.getString("academic_year_name"));
            item.setBatchStatus(rs.getString("batch_status"));
            item.setSupplementPlanned(rs.getBoolean("supplement_planned"));
            String stageCode = rs.getString("stage_code");
            if (stageCode != null) {
                item.setCurrentStage(new StudentBatchSummaryVO.StudentStageSummary(stageCode,
                    rs.getString("stage_status"), rs.getString("stage_start_at"), rs.getString("stage_end_at")));
            }
            item.setPreferenceStatus(rs.getString("preference_status"));
            item.setMatchStatus(rs.getString("match_status"));
            item.setMatchReason(rs.getString("match_reason"));
            item.setIdentityClassificationVersion(rs.getInt("identity_classification_version"));
            int rawConfirmedVersion = rs.getInt("confirmed_classification_version");
            item.setConfirmedClassificationVersion(rs.wasNull() ? null : Integer.valueOf(rawConfirmedVersion));
            item.setActions(new StudentBatchSummaryVO.StudentBatchActions(
                rs.getBoolean("can_confirm_identity"), rs.getBoolean("can_submit_preferences"),
                rs.getBoolean("can_withdraw_preferences"), rs.getBoolean("can_apply_supplement")));
            return item;
        }, studentId, accountId, pageSize, offset);
    }

    public long countVisibleBatches(Long studentId, Long accountId) {
        String sql = "SELECT COUNT(DISTINCT batch.id) FROM student student " +
            "JOIN account account ON account.id = student.account_id " +
            "JOIN selection_batch batch ON batch.college_id = student.college_id " +
            "LEFT JOIN batch_student batch_student ON batch_student.batch_id = batch.id AND batch_student.student_id = student.id " +
            "WHERE student.id = ? AND student.account_id = ? AND " + VISIBLE_WHERE;
        Long count = jdbcTemplate.queryForObject(sql, Long.class, studentId, accountId);
        return count == null ? 0L : count.longValue();
    }
}

