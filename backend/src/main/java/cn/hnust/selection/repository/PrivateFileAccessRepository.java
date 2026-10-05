package cn.hnust.selection.repository;

import cn.hnust.selection.enums.AccountRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 文件访问授权与审计查询；返回内容前必须同时命中附件状态和当前业务对象授权。 */
@Repository
public class PrivateFileAccessRepository {
    private final JdbcTemplate jdbc;
    public PrivateFileAccessRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public FileMetadata findAuthorized(Long fileId, Long accountId, AccountRole role, Long teacherId) {
        String authorization;
        if (role == AccountRole.STUDENT) {
            authorization = "file.owner_account_id = ?";
        } else if (role == AccountRole.TEACHER && teacherId != null) {
            authorization = "EXISTS (SELECT 1 FROM application_profile_snapshot snapshot " +
                "JOIN round_application application ON application.id = snapshot.round_application_id " +
                "WHERE snapshot.resume_file_id = file.id AND application.teacher_id = ?) OR " +
                "EXISTS (SELECT 1 FROM application_profile_snapshot snapshot " +
                "JOIN supplement_application application ON application.id = snapshot.supplement_application_id " +
                "WHERE snapshot.resume_file_id = file.id AND application.teacher_id = ?)";
        } else {
            return null;
        }
        String sql = "SELECT file.id, file.owner_account_id, file.purpose_code, file.original_filename, file.media_type, " +
            "file.storage_key, file.file_status, file.file_size_bytes FROM managed_file file WHERE file.id = ? " +
            "AND file.purpose_code = 'STUDENT_RESUME' AND file.file_status = 'AVAILABLE' AND (" + authorization + ")";
        List<FileMetadata> rows;
        if (role == AccountRole.STUDENT) rows = jdbc.query(sql, (rs, n) -> new FileMetadata(rs.getLong("id"),
            rs.getLong("owner_account_id"), rs.getString("purpose_code"), rs.getString("original_filename"),
            rs.getString("media_type"), rs.getString("storage_key"), rs.getString("file_status"), rs.getLong("file_size_bytes")),
            fileId, accountId);
        else rows = jdbc.query(sql, (rs, n) -> new FileMetadata(rs.getLong("id"), rs.getLong("owner_account_id"),
            rs.getString("purpose_code"), rs.getString("original_filename"), rs.getString("media_type"),
            rs.getString("storage_key"), rs.getString("file_status"), rs.getLong("file_size_bytes")), fileId, teacherId, teacherId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insertAccessRecord(Long accountId, Long batchId, Long fileId, String authorizationBasis) {
        jdbc.update("INSERT INTO data_access_record(account_id, action_code, batch_id, object_type, object_id, field_set, " +
            "authorization_basis, accessed_at, accessed_file_id) VALUES (?, 'RESUME_VIEW', ?, 'MANAGED_FILE', ?, 'STUDENT_RESUME', ?, " +
            "UTC_TIMESTAMP(3), ?)", accountId, batchId, fileId, authorizationBasis, fileId);
    }

    public static class FileMetadata {
        public final Long id, ownerAccountId, sizeBytes; public final String purpose, filename, mediaType, storageKey, status;
        public FileMetadata(Long id, Long ownerAccountId, String purpose, String filename, String mediaType,
                            String storageKey, String status, Long sizeBytes) {
            this.id=id; this.ownerAccountId=ownerAccountId; this.purpose=purpose; this.filename=filename;
            this.mediaType=mediaType; this.storageKey=storageKey; this.status=status; this.sizeBytes=sizeBytes;
        }
    }
}
