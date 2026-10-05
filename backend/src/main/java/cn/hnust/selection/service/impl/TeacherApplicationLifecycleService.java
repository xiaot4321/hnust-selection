package cn.hnust.selection.service.impl;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.List;

/** 定时推进填报结案、常规轮次和补选窗口；行锁使多实例调度可重复执行。 */
@Service
public class TeacherApplicationLifecycleService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public TeacherApplicationLifecycleService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.transactions = new TransactionTemplate(manager);
    }

    public void processActiveBatches() {
        List<Long> ids = jdbc.query("SELECT id FROM selection_batch WHERE batch_status = 'ACTIVE' ORDER BY id", (rs, n) -> rs.getLong(1));
        for (Long batchId : ids) {
            try { transactions.execute(status -> { processBatch(batchId); return null; }); }
            catch (RuntimeException ex) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Could not advance selection batch {}", batchId, ex);
            }
        }
    }

    private void processBatch(Long batchId) {
        List<String> batchRows = jdbc.query("SELECT batch_status FROM selection_batch WHERE id = ? FOR UPDATE",
            (rs, n) -> rs.getString(1), batchId);
        if (batchRows.isEmpty() || !"ACTIVE".equals(batchRows.get(0))) return;
        Timestamp now = jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)", Timestamp.class);
        Stage filling = stage(batchId, "FILLING");
        if (filling != null && "OPEN".equals(filling.status) && filling.endAt != null && !now.before(filling.endAt)) {
            closeFilling(batchId, filling);
        }
        for (int round = 1; round <= 3; round++) {
            Stage stage = stage(batchId, "ROUND_" + round);
            if (stage == null) return;
            if ("NOT_STARTED".equals(stage.status) && priorRoundOrFillingClosed(batchId, round) && stage.startAt != null && !now.before(stage.startAt)) {
                if (stage.endAt == null || now.before(stage.endAt)) openRound(batchId, stage, round);
                else skipRound(batchId, stage, round);
                stage = stage(batchId, "ROUND_" + round);
            }
            if ("OPEN".equals(stage.status)) {
                if (stage.endAt == null || now.before(stage.endAt)) return;
                closeRound(batchId, stage, round);
            }
            if (!"CLOSED".equals(stage.status)) return;
        }
        advanceSupplementOrComplete(batchId, now);
    }

    private void closeFilling(Long batchId, Stage filling) {
        jdbc.update("UPDATE preference_submission submission JOIN batch_student participant " +
            "ON participant.current_submission_id = submission.id SET submission.locked_at = UTC_TIMESTAMP(3), " +
            "submission.submission_status = 'LOCKED', submission.row_version = submission.row_version + 1 " +
            "WHERE participant.batch_id = ? AND participant.current_submission_id IS NOT NULL AND submission.locked_at IS NULL", batchId);
        jdbc.update("INSERT INTO student_match_event(batch_student_id, old_status, new_status, reason_code, occurred_at) " +
            "SELECT participant.id, participant.match_status, 'UNMATCHED', 'NOT_SUBMITTED', UTC_TIMESTAMP(3) FROM batch_student participant " +
            "WHERE participant.batch_id = ? AND participant.current_submission_id IS NULL " +
            "AND (participant.match_status IS NULL OR participant.match_status <> 'UNMATCHED') " +
            "AND NOT EXISTS (SELECT 1 FROM student_match_event event WHERE event.batch_student_id = participant.id " +
            "AND event.reason_code = 'NOT_SUBMITTED')", batchId);
        jdbc.update("UPDATE batch_student SET final_submission_id = current_submission_id, " +
            "preference_status = IF(current_submission_id IS NULL, 'NOT_SUBMITTED', 'LOCKED'), " +
            "match_status = IF(current_submission_id IS NULL, 'UNMATCHED', match_status), " +
            "match_reason = IF(current_submission_id IS NULL, 'NOT_SUBMITTED', match_reason), " +
            "match_changed_at = IF(current_submission_id IS NULL, UTC_TIMESTAMP(3), match_changed_at), " +
            "row_version = row_version + 1 WHERE batch_id = ?", batchId);
        closeStage(batchId, filling, "FILLING_DEADLINE");
        lifecycleEvent(batchId, filling.id, "FILLING_CLOSED", "填报窗口截止");
    }

    private void openRound(Long batchId, Stage stage, int round) {
        jdbc.update("UPDATE batch_stage SET stage_status = 'OPEN', actual_started_at = COALESCE(actual_started_at, UTC_TIMESTAMP(3)), " +
            "row_version = row_version + 1 WHERE id = ? AND stage_status = 'NOT_STARTED'", stage.id);
        jdbc.update("UPDATE selection_batch SET current_stage_id = ?, row_version = row_version + 1 WHERE id = ?", stage.id, batchId);
        jdbc.update("UPDATE batch_student participant JOIN preference_submission submission ON submission.id = participant.final_submission_id " +
            "SET participant.match_status = ?, participant.match_reason = NULL, participant.match_changed_at = UTC_TIMESTAMP(3), " +
            "participant.row_version = participant.row_version + 1 WHERE participant.batch_id = ? " +
            "AND (participant.match_status IS NULL OR participant.match_status NOT IN ('MATCHED','UNMATCHED')) AND EXISTS (SELECT 1 FROM preference_item item " +
            "WHERE item.submission_id = submission.id AND item.preference_order = ?)", "PENDING_ROUND_" + round, batchId, Integer.valueOf(round));
        jdbc.update("UPDATE round_application application JOIN batch_student participant " +
            "ON participant.id = application.batch_student_id SET application.application_status = 'IN_REVIEW', " +
            "application.close_reason = NULL, " +
            "application.entered_review_at = UTC_TIMESTAMP(3), application.decided_at = NULL, " +
            "application.execution_cycle = ?, application.active_snapshot_id = NULL, " +
            "application.row_version = application.row_version + 1 WHERE application.stage_id = ? " +
            "AND application.application_status = 'SUPERSEDED_BY_REOPEN' " +
            "AND (participant.match_status IS NULL OR participant.match_status NOT IN ('MATCHED','UNMATCHED'))",
            stage.cycle, stage.id);
        jdbc.update("INSERT INTO round_application(batch_student_id, stage_id, preference_item_id, teacher_id, application_status, " +
            "close_reason, entered_review_at, decided_at, execution_cycle, sort_submitted_at, sort_student_no, active_snapshot_id) " +
            "SELECT participant.id, ?, item.id, quota.teacher_id, 'IN_REVIEW', NULL, UTC_TIMESTAMP(3), NULL, ?, " +
            "submission.submitted_at, student.student_no, NULL FROM batch_student participant " +
            "JOIN preference_submission submission ON submission.id = participant.final_submission_id " +
            "JOIN preference_item item ON item.submission_id = submission.id AND item.preference_order = ? " +
            "JOIN batch_teacher_quota quota ON quota.id = item.batch_teacher_quota_id " +
            "JOIN student ON student.id = participant.student_id " +
            "WHERE participant.batch_id = ? AND (participant.match_status IS NULL OR participant.match_status NOT IN ('MATCHED','UNMATCHED')) " +
            "AND NOT EXISTS (SELECT 1 FROM round_application existing WHERE existing.preference_item_id = item.id)",
            stage.id, stage.cycle, Integer.valueOf(round), batchId);
        captureRoundSnapshots(batchId, stage.id);
        jdbc.update("INSERT INTO application_event(object_type, object_id, execution_cycle, action_code, new_status, actor_kind, occurred_at) " +
            "SELECT 'ROUND_APPLICATION', id, execution_cycle, 'ROUND_OPENED', 'IN_REVIEW', 'SYSTEM', UTC_TIMESTAMP(3) " +
            "FROM round_application application WHERE stage_id = ? AND execution_cycle = ? AND NOT EXISTS " +
            "(SELECT 1 FROM application_event event WHERE event.object_type = 'ROUND_APPLICATION' AND event.object_id = application.id " +
            "AND event.execution_cycle = application.execution_cycle AND event.action_code = 'ROUND_OPENED')", stage.id, stage.cycle);
        lifecycleEvent(batchId, stage.id, "ROUND_" + round + "_OPENED", "第 " + round + " 轮开始");
    }

    private void captureRoundSnapshots(Long batchId, Long stageId) {
        jdbc.update("INSERT INTO application_profile_snapshot(round_application_id, supplement_application_id, execution_cycle, " +
            "captured_at, capture_reason, full_name, student_no, major_id, major_name_snapshot, degree_type, biography, resume_file_id) " +
            "SELECT application.id, NULL, application.execution_cycle, UTC_TIMESTAMP(3), 'ROUND_ENTRY', student.full_name, " +
            "student.student_no, student.major_id, major.name, student.degree_type, profile.biography, profile.resume_file_id " +
            "FROM round_application application JOIN batch_student participant ON participant.id = application.batch_student_id " +
            "JOIN student ON student.id = participant.student_id JOIN major ON major.id = student.major_id " +
            "LEFT JOIN student_profile_version profile ON profile.student_id = student.id AND profile.version_no = " +
            "(SELECT MAX(latest.version_no) FROM student_profile_version latest WHERE latest.student_id = student.id) " +
            "WHERE application.stage_id = ? AND application.active_snapshot_id IS NULL", stageId);
        jdbc.update("UPDATE round_application application JOIN application_profile_snapshot snapshot " +
            "ON snapshot.round_application_id = application.id AND snapshot.execution_cycle = application.execution_cycle " +
            "SET application.active_snapshot_id = snapshot.id WHERE application.stage_id = ? AND application.active_snapshot_id IS NULL", stageId);
    }

    private void closeRound(Long batchId, Stage stage, int round) {
        jdbc.update("INSERT INTO application_event(object_type, object_id, execution_cycle, action_code, old_status, new_status, " +
            "close_reason, actor_kind, occurred_at) SELECT 'ROUND_APPLICATION', id, execution_cycle, 'ROUND_DEADLINE_CLOSE', " +
            "'IN_REVIEW', 'NOT_ADMITTED', 'ROUND_DEADLINE', 'SYSTEM', UTC_TIMESTAMP(3) FROM round_application " +
            "WHERE stage_id = ? AND application_status = 'IN_REVIEW'", stage.id);
        jdbc.update("UPDATE round_application SET application_status = 'NOT_ADMITTED', close_reason = 'ROUND_DEADLINE', " +
            "decided_at = UTC_TIMESTAMP(3), row_version = row_version + 1 WHERE stage_id = ? AND application_status = 'IN_REVIEW'", stage.id);
        publishRoundResult(batchId, stage.id, round);
        String exhaustedReason = round == 3 ? "ROUND3_EXHAUSTED" : "PREFERENCE_EXHAUSTED";
        if (round == 3) {
            jdbc.update("INSERT INTO student_match_event(batch_student_id, old_status, new_status, reason_code, actual_round, " +
                "actual_preference_order, source_type, source_id, occurred_at) SELECT participant.id, participant.match_status, " +
                "'UNMATCHED', ?, ?, ?, 'ROUND', ?, UTC_TIMESTAMP(3) FROM batch_student participant WHERE participant.batch_id = ? " +
                "AND participant.match_status = 'PENDING_ROUND_3'", exhaustedReason, Integer.valueOf(round),
                Integer.valueOf(round), stage.id, batchId);
            jdbc.update("UPDATE batch_student SET match_status = 'UNMATCHED', match_reason = ?, match_changed_at = UTC_TIMESTAMP(3), " +
                "row_version = row_version + 1 WHERE batch_id = ? AND match_status = 'PENDING_ROUND_3'", exhaustedReason, batchId);
        } else {
            jdbc.update("INSERT INTO student_match_event(batch_student_id, old_status, new_status, reason_code, actual_round, " +
                "actual_preference_order, source_type, source_id, occurred_at) SELECT participant.id, participant.match_status, " +
                "'UNMATCHED', 'PREFERENCE_EXHAUSTED', ?, ?, 'ROUND', ?, UTC_TIMESTAMP(3) FROM batch_student participant " +
                "JOIN preference_submission submission ON submission.id = participant.final_submission_id " +
                "WHERE participant.batch_id = ? AND participant.match_status = ? AND submission.item_count = ?",
                Integer.valueOf(round), Integer.valueOf(round), stage.id,
                batchId, "PENDING_ROUND_" + round, Integer.valueOf(round));
            jdbc.update("UPDATE batch_student participant JOIN preference_submission submission ON submission.id = participant.final_submission_id " +
                "SET participant.match_status = 'UNMATCHED', participant.match_reason = 'PREFERENCE_EXHAUSTED', " +
                "participant.match_changed_at = UTC_TIMESTAMP(3), participant.row_version = participant.row_version + 1 " +
                "WHERE participant.batch_id = ? AND participant.match_status = ? AND submission.item_count = ?", batchId,
                "PENDING_ROUND_" + round, Integer.valueOf(round));
        }
        closeStage(batchId, stage, "ROUND_" + round + "_DEADLINE");
        lifecycleEvent(batchId, stage.id, "ROUND_" + round + "_CLOSED", "第 " + round + " 轮截止并公布结果");
        if (round == 3) completeIfNoSupplement(batchId);
    }

    private void skipRound(Long batchId, Stage stage, int round) {
        jdbc.update("UPDATE batch_stage SET stage_status = 'CLOSED', actual_closed_at = UTC_TIMESTAMP(3), " +
            "close_reason = 'SCHEDULE_SKIPPED', row_version = row_version + 1 WHERE id = ? AND stage_status = 'NOT_STARTED'", stage.id);
        jdbc.update("INSERT INTO student_match_event(batch_student_id, old_status, new_status, reason_code, actual_round, " +
            "actual_preference_order, source_type, source_id, occurred_at) SELECT participant.id, participant.match_status, " +
            "'UNMATCHED', 'ALL_REMAINING_PREFERENCES_SKIPPED', ?, ?, 'SCHEDULE', ?, UTC_TIMESTAMP(3) " +
            "FROM batch_student participant JOIN preference_submission submission ON submission.id = participant.final_submission_id " +
            "JOIN preference_item item ON item.submission_id = submission.id AND item.preference_order = ? " +
            "WHERE participant.batch_id = ? AND (participant.match_status IS NULL OR participant.match_status NOT IN ('MATCHED','UNMATCHED')) " +
            "AND NOT EXISTS (SELECT 1 FROM preference_item future_item WHERE future_item.submission_id = submission.id " +
            "AND future_item.preference_order > ?) AND NOT EXISTS (SELECT 1 FROM student_match_event event " +
            "WHERE event.batch_student_id = participant.id AND event.reason_code = 'ALL_REMAINING_PREFERENCES_SKIPPED')",
            Integer.valueOf(round), Integer.valueOf(round), stage.id, Integer.valueOf(round), batchId, Integer.valueOf(round));
        jdbc.update("UPDATE batch_student participant JOIN preference_submission submission ON submission.id = participant.final_submission_id " +
            "JOIN preference_item item ON item.submission_id = submission.id AND item.preference_order = ? " +
            "SET participant.match_status = 'UNMATCHED', participant.match_reason = 'ALL_REMAINING_PREFERENCES_SKIPPED', " +
            "participant.match_changed_at = UTC_TIMESTAMP(3), participant.row_version = participant.row_version + 1 " +
            "WHERE participant.batch_id = ? AND (participant.match_status IS NULL OR participant.match_status NOT IN ('MATCHED','UNMATCHED')) " +
            "AND NOT EXISTS " +
            "(SELECT 1 FROM preference_item future_item WHERE future_item.submission_id = submission.id AND future_item.preference_order > ?)",
            Integer.valueOf(round), batchId, Integer.valueOf(round));
        lifecycleEvent(batchId, stage.id, "ROUND_" + round + "_SKIPPED", "第 " + round + " 轮按排期跳过");
    }

    private void advanceSupplementOrComplete(Long batchId, Timestamp now) {
        Stage stage = stage(batchId, "SUPPLEMENT");
        if (stage == null || "NOT_SCHEDULED".equals(stage.status)) { completeIfNoSupplement(batchId); return; }
        List<Window> windows = jdbc.query("SELECT id, window_status, effective_start_at, effective_end_at FROM supplement_window " +
            "WHERE batch_id = ?", (rs, n) -> new Window(rs.getLong("id"), rs.getString("window_status"),
                rs.getTimestamp("effective_start_at"), rs.getTimestamp("effective_end_at")), batchId);
        if (windows.isEmpty()) { completeBatch(batchId); return; }
        Window window = windows.get(0);
        if ("PLANNED".equals(window.status) && window.startAt != null && !now.before(window.startAt)) {
            if (window.endAt != null && !now.before(window.endAt)) {
                jdbc.update("UPDATE supplement_window SET window_status = 'CLOSED', actual_closed_at = UTC_TIMESTAMP(3), " +
                    "close_reason = 'WINDOW_ENDED', row_version = row_version + 1 WHERE id = ? AND window_status = 'PLANNED'", window.id);
                closeStage(batchId, stage, "WINDOW_ENDED");
                lifecycleEvent(batchId, stage.id, "SUPPLEMENT_CLOSED", "补选窗口未开放即到截止时间，批次自动完成");
                completeBatch(batchId); return;
            }
            jdbc.update("UPDATE supplement_window SET window_status = 'OPEN', actual_started_at = COALESCE(actual_started_at, UTC_TIMESTAMP(3)), " +
                "row_version = row_version + 1 WHERE id = ? AND window_status = 'PLANNED'", window.id);
            jdbc.update("UPDATE batch_stage SET stage_status = 'OPEN', actual_started_at = COALESCE(actual_started_at, UTC_TIMESTAMP(3)), " +
                "row_version = row_version + 1 WHERE id = ?", stage.id);
            jdbc.update("UPDATE selection_batch SET current_stage_id = ?, row_version = row_version + 1 WHERE id = ?", stage.id, batchId);
            lifecycleEvent(batchId, stage.id, "SUPPLEMENT_OPENED", "补选窗口开始");
        }
        if ("OPEN".equals(window.status) && window.endAt != null && !now.before(window.endAt)) {
            jdbc.update("INSERT INTO application_event(object_type, object_id, execution_cycle, action_code, old_status, new_status, " +
                "close_reason, actor_kind, occurred_at) SELECT 'SUPPLEMENT_APPLICATION', id, ?, 'SUPPLEMENT_WINDOW_CLOSE', " +
                "'IN_REVIEW', 'REJECTED', 'WINDOW_CLOSED', 'SYSTEM', UTC_TIMESTAMP(3) FROM supplement_application " +
                "WHERE supplement_window_id = ? AND application_status = 'IN_REVIEW'", stage.cycle, window.id);
            jdbc.update("UPDATE supplement_application SET application_status = 'REJECTED', close_reason = 'WINDOW_CLOSED', " +
                "decided_at = UTC_TIMESTAMP(3), row_version = row_version + 1 WHERE supplement_window_id = ? AND application_status = 'IN_REVIEW'", window.id);
            jdbc.update("DELETE pending FROM student_pending_supplement_slot pending JOIN supplement_application application " +
                "ON application.id = pending.supplement_application_id WHERE application.supplement_window_id = ?", window.id);
            jdbc.update("UPDATE supplement_window SET window_status = 'CLOSED', actual_closed_at = UTC_TIMESTAMP(3), " +
                "close_reason = 'WINDOW_ENDED', row_version = row_version + 1 WHERE id = ?", window.id);
            closeStage(batchId, stage, "SUPPLEMENT_CLOSED");
            lifecycleEvent(batchId, stage.id, "SUPPLEMENT_CLOSED", "补选窗口关闭并完成批次");
            completeBatch(batchId);
        }
    }

    private void publishRoundResult(Long batchId, Long stageId, int round) {
        Long noticeId = insertNotice(batchId, "ROUND_RESULT", "第 " + round + " 轮结果已发布", "本轮申请已结案，请在互选进度中查看个人结果。");
        jdbc.update("INSERT IGNORE INTO notice_recipient(notice_id, account_id, recipient_status, delivered_at) " +
            "SELECT ?, student.account_id, 'DELIVERED', UTC_TIMESTAMP(3) FROM round_application application " +
            "JOIN batch_student participant ON participant.id = application.batch_student_id JOIN student ON student.id = participant.student_id " +
            "WHERE application.stage_id = ?", noticeId, stageId);
    }
    private Long insertNotice(Long batchId, String type, String title, String body) {
        org.springframework.jdbc.support.KeyHolder key = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            java.sql.PreparedStatement ps = connection.prepareStatement("INSERT INTO site_notice(sender_account_id, batch_id, notice_type, title, body, created_at, visible_at) " +
                "VALUES (NULL, ?, ?, ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))", java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId); ps.setString(2, type); ps.setString(3, title); ps.setString(4, body); return ps;
        }, key);
        Number id = key.getKey(); return id == null ? null : Long.valueOf(id.longValue());
    }

    private void closeStage(Long batchId, Stage stage, String closeReason) {
        jdbc.update("UPDATE batch_stage SET stage_status = 'CLOSED', actual_closed_at = UTC_TIMESTAMP(3), close_reason = ?, " +
            "row_version = row_version + 1 WHERE id = ?", closeReason, stage.id);
    }
    private void lifecycleEvent(Long batchId, Long stageId, String action, String reason) {
        jdbc.update("INSERT INTO batch_lifecycle_event(batch_id, action_code, old_status, new_status, stage_id, actor_kind, reason, occurred_at) " +
            "VALUES (?, ?, 'ACTIVE', 'ACTIVE', ?, 'SYSTEM', ?, UTC_TIMESTAMP(3))", batchId, action, stageId, reason);
    }
    private boolean priorRoundOrFillingClosed(Long batchId, int round) {
        String code = round == 1 ? "FILLING" : "ROUND_" + (round - 1);
        List<String> rows = jdbc.query("SELECT stage_status FROM batch_stage WHERE batch_id = ? AND stage_code = ?",
            (rs, n) -> rs.getString(1), batchId, code);
        return !rows.isEmpty() && "CLOSED".equals(rows.get(0));
    }
    private Stage stage(Long batchId, String code) {
        List<Stage> rows = jdbc.query("SELECT id, stage_status, effective_start_at, effective_end_at, execution_cycle FROM batch_stage " +
            "WHERE batch_id = ? AND stage_code = ?", (rs, n) -> new Stage(rs.getLong("id"), rs.getString("stage_status"),
            rs.getTimestamp("effective_start_at"), rs.getTimestamp("effective_end_at"), rs.getInt("execution_cycle")), batchId, code);
        return rows.isEmpty() ? null : rows.get(0);
    }
    private void completeIfNoSupplement(Long batchId) {
        Integer planned = jdbc.queryForObject("SELECT supplement_planned FROM selection_batch WHERE id = ?", Integer.class, batchId);
        if (planned == null || planned.intValue() == 0) completeBatch(batchId);
    }
    private void completeBatch(Long batchId) {
        jdbc.update("UPDATE selection_batch SET batch_status = 'COMPLETED', completed_at = UTC_TIMESTAMP(3), " +
            "row_version = row_version + 1 WHERE id = ? AND batch_status = 'ACTIVE'", batchId);
        jdbc.update("DELETE FROM batch_running_slot WHERE batch_id = ?", batchId);
    }

    private static class Stage {
        final Long id; final String status; final Timestamp startAt; final Timestamp endAt; final Integer cycle;
        Stage(Long id, String status, Timestamp startAt, Timestamp endAt, int cycle) {
            this.id=id; this.status=status; this.startAt=startAt; this.endAt=endAt; this.cycle=Integer.valueOf(cycle);
        }
    }
    private static class Window {
        final Long id; final String status; final Timestamp startAt; final Timestamp endAt;
        Window(Long id, String status, Timestamp startAt, Timestamp endAt) { this.id=id; this.status=status; this.startAt=startAt; this.endAt=endAt; }
    }
}
