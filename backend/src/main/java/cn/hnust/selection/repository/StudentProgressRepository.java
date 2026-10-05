package cn.hnust.selection.repository;

import cn.hnust.selection.vo.StudentBatchSummaryVO;
import cn.hnust.selection.vo.StudentProgressVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class StudentProgressRepository {
    private final JdbcTemplate jdbcTemplate;

    public StudentProgressRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<StudentProgressVO> findProgress(Long studentId, Long accountId, Long batchId) {
        String mainSql = "SELECT batch.batch_status, batch_student.preference_status, batch_student.match_status, " +
            "CASE WHEN batch_student.match_status = 'UNMATCHED' THEN batch_student.match_reason ELSE NULL END AS match_reason, " +
            "stage.stage_code, CASE WHEN batch.batch_status = 'SCHEDULED' THEN 'NOT_STARTED' " +
            "WHEN stage.stage_code = 'FILLING' AND COALESCE(stage.effective_start_at, stage.planned_start_at) > UTC_TIMESTAMP(3) " +
            "THEN 'WAITING_FILLING' ELSE stage.stage_status END AS stage_status, " +
            "DATE_FORMAT(CASE WHEN stage.stage_code = 'SUPPLEMENT' THEN COALESCE(supplement.effective_start_at, stage.effective_start_at, stage.planned_start_at) " +
            "ELSE COALESCE(stage.effective_start_at, stage.planned_start_at) END, '%Y-%m-%dT%H:%i:%s.%fZ') AS stage_start_at, " +
            "DATE_FORMAT(CASE WHEN stage.stage_code = 'SUPPLEMENT' THEN COALESCE(supplement.effective_end_at, stage.effective_end_at, stage.planned_end_at) " +
            "ELSE COALESCE(stage.effective_end_at, stage.planned_end_at) END, '%Y-%m-%dT%H:%i:%s.%fZ') AS stage_end_at, " +
            "relation.id AS relation_id, quota.teacher_id AS relation_teacher_id, teacher.full_name AS relation_teacher_name, " +
            "relation.source_type, DATE_FORMAT(relation.locked_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS relation_locked_at " +
            "FROM batch_student JOIN student ON student.id = batch_student.student_id " +
            "JOIN selection_batch batch ON batch.id = batch_student.batch_id " +
            "LEFT JOIN batch_stage stage ON stage.id = COALESCE(batch.current_stage_id, " +
            "(SELECT candidate.id FROM batch_stage candidate WHERE candidate.batch_id = batch.id ORDER BY candidate.stage_order LIMIT 1)) " +
            "LEFT JOIN supplement_window supplement ON supplement.batch_id = batch.id " +
            "LEFT JOIN matching_relation relation ON relation.id = batch_student.current_relation_id AND relation.relation_status = 'LOCKED' " +
            "LEFT JOIN batch_teacher_quota quota ON quota.id = relation.batch_teacher_quota_id " +
            "LEFT JOIN teacher ON teacher.id = quota.teacher_id " +
            "WHERE batch_student.student_id = ? AND student.account_id = ? AND batch_student.batch_id = ? AND batch.college_id = student.college_id";
        List<StudentProgressVO> rows = jdbcTemplate.query(mainSql, (rs, rowNum) -> {
            StudentProgressVO progress = new StudentProgressVO();
            progress.setBatchStatus(rs.getString("batch_status"));
            progress.setPreferenceStatus(rs.getString("preference_status"));
            progress.setMatchStatus(rs.getString("match_status"));
            progress.setMatchReason(rs.getString("match_reason"));
            String stageCode = rs.getString("stage_code");
            if (stageCode != null) progress.setCurrentStage(new StudentBatchSummaryVO.StudentStageSummary(
                stageCode, rs.getString("stage_status"), rs.getString("stage_start_at"), rs.getString("stage_end_at")));
            long relationId = rs.getLong("relation_id");
            if (!rs.wasNull()) {
                StudentProgressVO.CurrentRelation relation = new StudentProgressVO.CurrentRelation();
                relation.setTeacherId(rs.getLong("relation_teacher_id"));
                relation.setTeacherName(rs.getString("relation_teacher_name"));
                relation.setSource(rs.getString("source_type"));
                relation.setLockedAt(rs.getString("relation_locked_at"));
                progress.setCurrentRelation(relation);
            }
            return progress;
        }, studentId, accountId, batchId);
        if (rows.isEmpty()) return Optional.empty();
        StudentProgressVO progress = rows.get(0);
        progress.setRounds(findRounds(studentId, accountId, batchId, progress.getBatchStatus()));
        return Optional.of(progress);
    }

    private List<StudentProgressVO.StudentRoundProgressVO> findRounds(Long studentId, Long accountId,
                                                                       Long batchId, String batchStatus) {
        String sql = "SELECT CASE stage.stage_code WHEN 'ROUND_1' THEN 1 WHEN 'ROUND_2' THEN 2 ELSE 3 END AS round_no, " +
            "stage.stage_code, stage.stage_status, stage.actual_closed_at, " +
            "item.preference_order, quota.teacher_id, teacher.full_name AS teacher_name, application.application_status, " +
            "DATE_FORMAT(application.decided_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS processed_at " +
            "FROM batch_student JOIN student ON student.id = batch_student.student_id " +
            "JOIN batch_stage stage ON stage.batch_id = batch_student.batch_id " +
            "AND stage.stage_code IN ('ROUND_1','ROUND_2','ROUND_3') " +
            "LEFT JOIN preference_submission submission ON submission.id = COALESCE(batch_student.final_submission_id, batch_student.current_submission_id) " +
            "AND batch_student.preference_status <> 'WITHDRAWN' " +
            "LEFT JOIN preference_item item ON item.submission_id = submission.id " +
            "AND item.preference_order = CASE stage.stage_code WHEN 'ROUND_1' THEN 1 WHEN 'ROUND_2' THEN 2 ELSE 3 END " +
            "LEFT JOIN batch_teacher_quota quota ON quota.id = item.batch_teacher_quota_id " +
            "LEFT JOIN teacher ON teacher.id = quota.teacher_id " +
            "LEFT JOIN round_application application ON application.batch_student_id = batch_student.id " +
            "AND application.stage_id = stage.id AND application.preference_item_id = item.id " +
            "WHERE batch_student.student_id = ? AND student.account_id = ? AND batch_student.batch_id = ? AND batch.college_id = student.college_id " +
            "ORDER BY stage.stage_order";
        boolean terminalBatch = "COMPLETED".equals(batchStatus) || "ARCHIVED".equals(batchStatus) || "CANCELLED".equals(batchStatus);
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            StudentProgressVO.StudentRoundProgressVO round = new StudentProgressVO.StudentRoundProgressVO();
            int roundNo = rs.getInt("round_no");
            round.setRoundNo(Integer.valueOf(roundNo));
            round.setPreferenceOrder(Integer.valueOf(roundNo));
            long rawTeacherId = rs.getLong("teacher_id");
            if (!rs.wasNull()) round.setTeacherId(Long.valueOf(rawTeacherId));
            round.setTeacherName(rs.getString("teacher_name"));
            String applicationStatus = rs.getString("application_status");
            String stageStatus = rs.getString("stage_status");
            boolean published = terminalBatch || "CLOSED".equals(stageStatus) || rs.getTimestamp("actual_closed_at") != null;
            round.setResultPublished(published);
            round.setProcessedAt(rs.getString("processed_at"));
            if (rs.getObject("preference_order") == null) {
                round.setState("NO_PREFERENCE");
            } else if (applicationStatus == null) {
                round.setState(published ? "SKIPPED_BY_SCHEDULE" : "WAITING");
            } else if ("IN_REVIEW".equals(applicationStatus)) {
                round.setState("PENDING");
            } else if ("ADMITTED".equals(applicationStatus)) {
                round.setState(published ? "ADMITTED" : "PROCESSED");
            } else if ("NOT_ADMITTED".equals(applicationStatus) || "REJECTED".equals(applicationStatus)) {
                round.setState(published ? "NOT_ADMITTED" : "PROCESSED");
            } else if ("CANCELLED_BY_BATCH".equals(applicationStatus)) {
                round.setState("CANCELLED_BY_BATCH");
            } else if ("SKIPPED_BY_SCHEDULE".equals(applicationStatus)) {
                round.setState("SKIPPED_BY_SCHEDULE");
            } else if ("SUPERSEDED_BY_REOPEN".equals(applicationStatus)) {
                round.setState("WAITING");
            } else {
                round.setState(published ? "PROCESSED" : "PENDING");
            }
            return round;
        }, studentId, accountId, batchId);
    }
}



