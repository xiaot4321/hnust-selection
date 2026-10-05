package cn.hnust.selection.repository;

import cn.hnust.selection.vo.PreferenceSubmissionVO;
import cn.hnust.selection.vo.StudentPreferenceItemVO;
import cn.hnust.selection.vo.StudentPreferencesVO;
import cn.hnust.selection.vo.SupplementApplicationVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;

@Repository
public class StudentSelectionQueryRepository {
    private final JdbcTemplate jdbcTemplate;

    public StudentSelectionQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Long> findBatchStudentId(Long studentId, Long accountId, Long batchId) {
        List<Long> ids = jdbcTemplate.query(
            "SELECT participant.id FROM batch_student participant " +
                "JOIN student ON student.id = participant.student_id " +
                "JOIN selection_batch batch ON batch.id = participant.batch_id AND batch.college_id = student.college_id " +
                "WHERE participant.student_id = ? AND student.account_id = ? AND participant.batch_id = ?",
            (rs, rowNum) -> rs.getLong(1), studentId, accountId, batchId);
        return ids.isEmpty() ? Optional.<Long>empty() : Optional.of(ids.get(0));
    }
    public StudentPreferencesVO findCurrentPreferences(Long studentId, Long accountId, Long batchId) {
        String sql = "SELECT batch_student.preference_status, submission.id AS submission_id, submission.version_no, " +
            "DATE_FORMAT(submission.submitted_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS submitted_at, " +
            "DATE_FORMAT(submission.locked_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS locked_at " +
            "FROM batch_student JOIN student ON student.id = batch_student.student_id " +
            "JOIN selection_batch batch ON batch.id = batch_student.batch_id AND batch.college_id = student.college_id " +
            "LEFT JOIN preference_submission submission ON submission.id = batch_student.current_submission_id " +
            "WHERE batch_student.student_id = ? AND student.account_id = ? AND batch_student.batch_id = ?";
        List<StudentPreferencesVO> rows = jdbcTemplate.query(sql, (rs, rowNum) -> {
            StudentPreferencesVO result = new StudentPreferencesVO();
            String status = rs.getString("preference_status");
            result.setPreferenceStatus(status == null ? "NOT_SUBMITTED" : status);
            long rawSubmissionId = rs.getLong("submission_id");
            if (!rs.wasNull()) {
                result.setSubmissionId(rawSubmissionId);
                result.setVersionNo(rs.getInt("version_no"));
                result.setSubmittedAt(rs.getString("submitted_at"));
                result.setLockedAt(rs.getString("locked_at"));
                result.setItems(findItems(rawSubmissionId));
            }
            return result;
        }, studentId, accountId, batchId);
        if (!rows.isEmpty()) return rows.get(0);
        StudentPreferencesVO empty = new StudentPreferencesVO();
        empty.setPreferenceStatus("NOT_SUBMITTED");
        return empty;
    }

    public long countSubmissions(Long batchStudentId) {
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM preference_submission WHERE batch_student_id = ?", Long.class, batchStudentId);
        return count == null ? 0L : count.longValue();
    }

    public List<PreferenceSubmissionVO> listSubmissions(Long batchStudentId, int pageNo, int pageSize) {
        long offset = ((long) pageNo - 1L) * pageSize;
        String sql = "SELECT submission.id AS submission_id, submission.version_no, submission.submission_status, " +
            "DATE_FORMAT(submission.submitted_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS submitted_at, " +
            "DATE_FORMAT(submission.locked_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS locked_at, " +
            "item.preference_order, quota.teacher_id, teacher.full_name AS teacher_name, teacher.employee_no, " +
            "profile.research_directions " +
            "FROM preference_submission submission " +
            "LEFT JOIN preference_item item ON item.submission_id = submission.id " +
            "LEFT JOIN batch_teacher_quota quota ON quota.id = item.batch_teacher_quota_id " +
            "LEFT JOIN teacher ON teacher.id = quota.teacher_id " +
            "LEFT JOIN teacher_public_profile_version profile ON profile.id = teacher.current_public_profile_version_id AND profile.published_at IS NOT NULL " +
            "WHERE submission.batch_student_id = ? " +
            "ORDER BY submission.version_no DESC, item.preference_order ASC LIMIT ? OFFSET ?";
        Map<Long, PreferenceSubmissionVO> grouped = new LinkedHashMap<Long, PreferenceSubmissionVO>();
        jdbcTemplate.query(sql, rs -> {
            long submissionId = rs.getLong("submission_id");
            PreferenceSubmissionVO item = grouped.get(submissionId);
            if (item == null) {
                item = new PreferenceSubmissionVO();
                item.setSubmissionId(submissionId);
                item.setVersionNo(rs.getInt("version_no"));
                item.setStatus(rs.getString("submission_status"));
                item.setSubmittedAt(rs.getString("submitted_at"));
                item.setLockedAt(rs.getString("locked_at"));
                grouped.put(submissionId, item);
            }
            long rawTeacherId = rs.getLong("teacher_id");
            if (!rs.wasNull()) {
                StudentPreferenceItemVO preference = new StudentPreferenceItemVO();
                preference.setTeacherId(rawTeacherId);
                preference.setPreferenceOrder(rs.getInt("preference_order"));
                preference.setTeacherName(rs.getString("teacher_name"));
                preference.setEmployeeNo(rs.getString("employee_no"));
                preference.setResearchDirections(splitDirections(rs.getString("research_directions")));
                item.getItems().add(preference);
            }
        }, batchStudentId, pageSize, offset);
        return new ArrayList<PreferenceSubmissionVO>(grouped.values());
    }

    private List<StudentPreferenceItemVO> findItems(Long submissionId) {
        String sql = "SELECT quota.teacher_id, item.preference_order, teacher.full_name AS teacher_name, " +
            "teacher.employee_no, profile.research_directions " +
            "FROM preference_item item JOIN batch_teacher_quota quota ON quota.id = item.batch_teacher_quota_id " +
            "JOIN teacher ON teacher.id = quota.teacher_id " +
            "LEFT JOIN teacher_public_profile_version profile ON profile.id = teacher.current_public_profile_version_id AND profile.published_at IS NOT NULL " +
            "WHERE item.submission_id = ? ORDER BY item.preference_order";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            StudentPreferenceItemVO item = new StudentPreferenceItemVO();
            item.setTeacherId(rs.getLong("teacher_id"));
            item.setPreferenceOrder(rs.getInt("preference_order"));
            item.setTeacherName(rs.getString("teacher_name"));
            item.setEmployeeNo(rs.getString("employee_no"));
            item.setResearchDirections(splitDirections(rs.getString("research_directions")));
            return item;
        }, submissionId);
    }

    public long countSupplementApplications(Long batchStudentId) {
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM supplement_application WHERE batch_student_id = ?", Long.class, batchStudentId);
        return count == null ? 0L : count.longValue();
    }

    public List<SupplementApplicationVO> listSupplementApplications(Long batchStudentId, int pageNo, int pageSize) {
        long offset = ((long) pageNo - 1L) * pageSize;
        String sql = "SELECT application.id, application.teacher_id, teacher.full_name AS teacher_name, " +
            "DATE_FORMAT(application.submitted_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS submitted_at, " +
            "application.application_status, " +
            "DATE_FORMAT(application.decided_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS processed_at " +
            "FROM supplement_application application JOIN teacher ON teacher.id = application.teacher_id " +
            "WHERE application.batch_student_id = ? ORDER BY application.submitted_at DESC, application.id DESC LIMIT ? OFFSET ?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            SupplementApplicationVO item = new SupplementApplicationVO();
            item.setApplicationId(rs.getLong("id"));
            item.setTeacherId(rs.getLong("teacher_id"));
            item.setTeacherName(rs.getString("teacher_name"));
            item.setSubmittedAt(rs.getString("submitted_at"));
            String status = rs.getString("application_status");
            item.setStatus("NOT_ADMITTED".equals(status) ? "REJECTED" : status);
            item.setProcessedAt(rs.getString("processed_at"));
            return item;
        }, batchStudentId, pageSize, offset);
    }

    private static List<String> splitDirections(String raw) {
        List<String> result = new ArrayList<String>();
        if (raw == null || raw.trim().isEmpty()) return result;
        for (String line : raw.split("\\r?\\n")) {
            String direction = line.trim();
            if (!direction.isEmpty()) result.add(direction);
        }
        return result;
    }
}








