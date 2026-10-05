package cn.hnust.selection.repository;

import cn.hnust.selection.vo.AdminExportJobVO;
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
public class AdminExportRepository {
    private final JdbcTemplate jdbc;
    public AdminExportRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Job> findByRequest(Long actorId, String requestId) {
        try {
            return Optional.of(jdbc.queryForObject("SELECT id, batch_id, requester_account_id, export_type, export_status, " +
                "storage_key, original_filename, row_count, error_code, created_at, completed_at, expires_at, request_fingerprint " +
                "FROM admin_export_job WHERE requester_account_id = ? AND request_id = ?",
                (rs, n) -> new Job(rs.getLong("id"), rs.getLong("batch_id"), rs.getLong("requester_account_id"),
                    rs.getString("export_type"), rs.getString("export_status"), rs.getString("storage_key"),
                    rs.getString("original_filename"), nullableInt(rs, "row_count"), rs.getString("error_code"),
                    rs.getTimestamp("created_at"), rs.getTimestamp("completed_at"), rs.getTimestamp("expires_at"),
                    null, rs.getString("request_fingerprint")), actorId, requestId));
        } catch (EmptyResultDataAccessException ex) { return Optional.empty(); }
    }

    public Long create(Long batchId, Long actorId, String type, String requestId, String fingerprint) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("INSERT INTO admin_export_job(batch_id, requester_account_id, " +
                "export_type, export_status, request_id, request_fingerprint, created_at, expires_at) " +
                "VALUES (?, ?, ?, 'QUEUED', ?, ?, UTC_TIMESTAMP(3), DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 24 HOUR))",
                Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId); ps.setLong(2, actorId); ps.setString(3, type); ps.setString(4, requestId);
            ps.setString(5, fingerprint); return ps;
        }, key);
        Number id = key.getKey();
        if (id == null) throw new IllegalStateException("Export job insert returned no ID");
        return id.longValue();
    }

    public Optional<Job> find(Long id) {
        List<Job> rows = jdbc.query("SELECT job.id, job.batch_id, job.requester_account_id, job.export_type, job.export_status, " +
            "job.storage_key, job.original_filename, job.row_count, job.error_code, job.created_at, job.completed_at, job.expires_at, " +
            "batch.college_id FROM admin_export_job job JOIN selection_batch batch ON batch.id = job.batch_id WHERE job.id = ?",
            (rs, n) -> new Job(rs.getLong("id"), rs.getLong("batch_id"), rs.getLong("requester_account_id"),
                rs.getString("export_type"), rs.getString("export_status"), rs.getString("storage_key"),
                rs.getString("original_filename"), nullableInt(rs, "row_count"), rs.getString("error_code"),
                rs.getTimestamp("created_at"), rs.getTimestamp("completed_at"), rs.getTimestamp("expires_at"),
                Long.valueOf(rs.getLong("college_id")), null), id);
        return rows.isEmpty() ? Optional.<Job>empty() : Optional.of(rows.get(0));
    }

    public boolean markProcessing(Long id) {
        return jdbc.update("UPDATE admin_export_job SET export_status = 'PROCESSING', row_version = row_version + 1 " +
            "WHERE id = ? AND export_status = 'QUEUED' AND expires_at > UTC_TIMESTAMP(3)", id) == 1;
    }

    public void markReady(Long id, String key, String filename, long size, int rows) {
        jdbc.update("UPDATE admin_export_job SET export_status = 'COMPLETED', storage_key = ?, original_filename = ?, " +
            "file_size_bytes = ?, row_count = ?, completed_at = UTC_TIMESTAMP(3), row_version = row_version + 1 " +
            "WHERE id = ? AND export_status = 'PROCESSING'", key, filename, Long.valueOf(size), Integer.valueOf(rows), id);
    }

    public void markFailed(Long id) {
        jdbc.update("UPDATE admin_export_job SET export_status = 'FAILED', error_code = 'EXPORT_GENERATION_FAILED', " +
            "completed_at = UTC_TIMESTAMP(3), row_version = row_version + 1 WHERE id = ? AND export_status IN ('QUEUED','PROCESSING')", id);
    }

    public List<Job> expiredWithFiles() {
        return jdbc.query("SELECT job.id, job.batch_id, job.requester_account_id, job.export_type, job.export_status, " +
            "job.storage_key, job.original_filename, job.row_count, job.error_code, job.created_at, job.completed_at, " +
            "job.expires_at, batch.college_id FROM admin_export_job job JOIN selection_batch batch ON batch.id = job.batch_id " +
            "WHERE job.expires_at <= UTC_TIMESTAMP(3) AND job.export_status <> 'EXPIRED' ORDER BY job.id",
            (rs, n) -> new Job(rs.getLong("id"), rs.getLong("batch_id"), rs.getLong("requester_account_id"),
                rs.getString("export_type"), rs.getString("export_status"), rs.getString("storage_key"),
                rs.getString("original_filename"), nullableInt(rs, "row_count"), rs.getString("error_code"),
                rs.getTimestamp("created_at"), rs.getTimestamp("completed_at"), rs.getTimestamp("expires_at"),
                Long.valueOf(rs.getLong("college_id")), null));
    }

    public boolean isLive(Long id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM admin_export_job WHERE id = ? " +
            "AND expires_at > UTC_TIMESTAMP(3) AND export_status = 'COMPLETED'", Integer.class, id);
        return count != null && count.intValue() == 1;
    }

    public void markExpired(Long id) {
        jdbc.update("UPDATE admin_export_job SET export_status = 'EXPIRED', storage_key = NULL, row_version = row_version + 1 " +
            "WHERE id = ? AND expires_at <= UTC_TIMESTAMP(3)", id);
    }

    public void recordAccess(Long actorId, Job job, String action, String fieldSet, String authorizationBasis) {
        jdbc.update("INSERT INTO data_access_record(account_id, action_code, college_id, batch_id, object_type, object_id, " +
            "field_set, authorization_basis, accessed_at) VALUES (?, ?, ?, ?, 'ADMIN_EXPORT_JOB', ?, ?, ?, UTC_TIMESTAMP(3))",
            actorId, action, job.collegeId, job.batchId, job.id, fieldSet, authorizationBasis);
    }

    public ExportData readData(Long batchId, String exportType) {
        List<String[]> rows = new ArrayList<String[]>();
        if ("MATCHED_RESULTS".equals(exportType)) {
            rows.add(new String[] { "姓名", "学号", "专业", "办理结果" });
            rows.addAll(jdbc.query("SELECT student.full_name, student.student_no, COALESCE(major.name, ''), " +
                "CASE participant.match_status WHEN 'MATCHED' THEN '已匹配' WHEN 'UNMATCHED' THEN '未匹配' " +
                "WHEN 'PENDING_ROUND_1' THEN '等待第一轮' WHEN 'PENDING_ROUND_2' THEN '等待第二轮' " +
                "WHEN 'PENDING_ROUND_3' THEN '等待第三轮' ELSE COALESCE(participant.match_status, '待处理') END " +
                "FROM batch_student participant JOIN student ON student.id = participant.student_id " +
                "LEFT JOIN major ON major.id = student.major_id WHERE participant.batch_id = ? " +
                "ORDER BY student.student_no, participant.id", (rs, n) -> new String[] {
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4) }, batchId));
        } else {
            rows.add(new String[] { "统计项目", "数量" });
            java.util.Map<String, Object> counts = jdbc.queryForMap("SELECT COUNT(*) AS roster, " +
                "COALESCE(SUM(match_status = 'MATCHED'),0) AS matched, COALESCE(SUM(match_status = 'UNMATCHED'),0) AS unmatched, " +
                "COALESCE(SUM(preference_status = 'NOT_SUBMITTED'),0) AS no_preferences, " +
                "COALESCE(SUM(match_reason = 'IDENTITY_CORRECTION'),0) AS identity_correction " +
                "FROM batch_student WHERE batch_id = ?", batchId);
            rows.add(new String[] { "冻结名单人数", String.valueOf(counts.get("roster")) });
            rows.add(new String[] { "已匹配", String.valueOf(counts.get("matched")) });
            rows.add(new String[] { "未匹配", String.valueOf(counts.get("unmatched")) });
            rows.add(new String[] { "未提交志愿", String.valueOf(counts.get("no_preferences")) });
            rows.add(new String[] { "身份纠错后未匹配", String.valueOf(counts.get("identity_correction")) });
        }
        return new ExportData(rows, Math.max(0, rows.size() - 1));
    }

    public AdminExportJobVO toView(Job job) {
        AdminExportJobVO view = new AdminExportJobVO();
        view.setExportId(job.id); view.setBatchId(job.batchId); view.setExportType(job.type); view.setStatus(job.status);
        view.setCreatedAt(text(job.createdAt)); view.setCompletedAt(text(job.completedAt)); view.setExpiresAt(text(job.expiresAt));
        view.setRowCount(job.rowCount); view.setErrorCode(job.errorCode);
        view.setStatus("COMPLETED".equals(job.status) ? "READY" : job.status);
        if ("COMPLETED".equals(job.status)) view.setDownloadPath("/api/admin/exports/" + job.id + "/download");
        return view;
    }

    private static Integer nullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column); return rs.wasNull() ? null : Integer.valueOf(value);
    }
    private static String text(Timestamp value) { return value == null ? null : value.toInstant().toString(); }

    public static final class ExportData {
        public final List<String[]> rows; public final int rowCount;
        ExportData(List<String[]> rows, int rowCount) { this.rows=rows; this.rowCount=rowCount; }
    }
    public static final class Job {
        public final Long id, batchId, actorId, collegeId; public final String type, status, storageKey, filename, errorCode, fingerprint;
        public final Integer rowCount; public final Timestamp createdAt, completedAt, expiresAt;
        Job(Long id, Long batchId, Long actorId, String type, String status, String storageKey, String filename,
            Integer rowCount, String errorCode, Timestamp createdAt, Timestamp completedAt, Timestamp expiresAt, Long collegeId) {
            this(id, batchId, actorId, type, status, storageKey, filename, rowCount, errorCode,
                createdAt, completedAt, expiresAt, collegeId, null);
        }
        Job(Long id, Long batchId, Long actorId, String type, String status, String storageKey, String filename,
            Integer rowCount, String errorCode, Timestamp createdAt, Timestamp completedAt, Timestamp expiresAt,
            Long collegeId, String fingerprint) {
            this.id=id; this.batchId=batchId; this.actorId=actorId; this.type=type; this.status=status; this.storageKey=storageKey;
            this.filename=filename; this.rowCount=rowCount; this.errorCode=errorCode; this.createdAt=createdAt;
            this.completedAt=completedAt; this.expiresAt=expiresAt; this.collegeId=collegeId; this.fingerprint=fingerprint;
        }
    }
}
