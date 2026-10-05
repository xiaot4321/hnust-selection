package cn.hnust.selection.repository;

import cn.hnust.selection.vo.StudentProfileVO;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.sql.PreparedStatement;
import java.sql.Statement;

/** 学生资料版本的 JDBC 访问层；不承载业务状态或 HTTP 语义。 */
@Repository
public class StudentProfileRepository {
    private final JdbcTemplate jdbcTemplate;

    public StudentProfileRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<StudentProfileVO> profileMapper = new RowMapper<StudentProfileVO>() {
        @Override
        public StudentProfileVO mapRow(ResultSet rs, int rowNum) throws SQLException {
            StudentProfileVO profile = new StudentProfileVO();
            profile.setStudentId(rs.getLong("student_id"));
            profile.setStudentNo(rs.getString("student_no"));
            profile.setFullName(rs.getString("full_name"));
            profile.setCollegeId(rs.getLong("college_id"));
            profile.setCollegeName(rs.getString("college_name"));
            profile.setMajor(new StudentProfileVO.StudentMajorVO(
                rs.getLong("major_id"), rs.getString("major_code"), rs.getString("major_name")));
            profile.setDegreeType(rs.getString("degree_type"));
            profile.setClassificationVersion(rs.getInt("classification_version"));
            profile.setAccountStatus(rs.getString("account_status"));
            profile.setProfileVersion(rs.getInt("profile_version"));
            profile.setBiography(nullToEmpty(rs.getString("biography")));
            profile.setContact(nullToEmpty(rs.getString("contact_text")));

            long rawFileId = rs.getLong("resume_file_id");
            if (!rs.wasNull()) {
                long rawSize = rs.getLong("resume_size_bytes");
                Long size = rs.wasNull() ? null : rawSize;
                profile.setResume(new StudentProfileVO.StudentResumeVO(rawFileId,
                    rs.getString("resume_file_name"), size, rs.getString("resume_uploaded_at"),
                    toScanStatus(rs.getString("resume_status"))));
            }
            return profile;
        }
    };

    public Optional<StudentProfileVO> findCurrentProfile(Long studentId) {
        String sql = "SELECT s.id AS student_id, s.student_no, s.full_name, s.college_id, c.name AS college_name, " +
            "m.id AS major_id, m.major_code, m.name AS major_name, s.degree_type, s.classification_version, " +
            "a.account_status, COALESCE(p.version_no, 0) AS profile_version, p.biography, p.contact_text, " +
            "f.id AS resume_file_id, f.original_filename AS resume_file_name, f.file_size_bytes AS resume_size_bytes, " +
            "DATE_FORMAT(f.uploaded_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS resume_uploaded_at, f.file_status AS resume_status " +
            "FROM student s JOIN account a ON a.id = s.account_id " +
            "JOIN college c ON c.id = s.college_id JOIN major m ON m.id = s.major_id " +
            "LEFT JOIN student_profile_version p ON p.student_id = s.id AND p.version_no = " +
            "(SELECT MAX(p2.version_no) FROM student_profile_version p2 WHERE p2.student_id = s.id) " +
            "LEFT JOIN managed_file f ON f.id = p.resume_file_id AND f.owner_account_id = a.id " +
            "AND f.purpose_code = 'STUDENT_RESUME' WHERE s.id = ?";
        List<StudentProfileVO> rows = jdbcTemplate.query(sql, profileMapper, studentId);
        return rows.isEmpty() ? Optional.<StudentProfileVO>empty() : Optional.of(rows.get(0));
    }

    /** 锁定本人学生行；调用方必须处于事务中。 */
    public boolean lockStudent(Long studentId) {
        try {
            jdbcTemplate.queryForObject("SELECT id FROM student WHERE id = ? FOR UPDATE", Long.class, studentId);
            return true;
        } catch (EmptyResultDataAccessException ex) {
            return false;
        }
    }

    public boolean lockActiveStudent(Long studentId, Long accountId) {
        List<Long> rows = jdbcTemplate.query("SELECT student.id FROM student JOIN account ON account.id = student.account_id " +
            "WHERE student.id = ? AND student.account_id = ? AND account.account_status = 'ACTIVE' FOR UPDATE",
            (rs, n) -> rs.getLong(1), studentId, accountId);
        return !rows.isEmpty();
    }

    public void insertProfileVersion(Long studentId, int versionNo, String biography, String contact,
                                     Long resumeFileId, Long actorAccountId) {
        jdbcTemplate.update("INSERT INTO student_profile_version " +
                "(student_id, version_no, biography, contact_text, resume_file_id, changed_by, changed_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(3))",
            studentId, versionNo, biography, contact, resumeFileId, actorAccountId);
    }

    public Optional<ResumeUploadOperation> findResumeUploadOperation(Long accountId, String requestId) {
        List<ResumeUploadOperation> rows = jdbcTemplate.query("SELECT operation.id, operation.request_fingerprint, " +
            "operation.result_code, audit.object_id AS file_id FROM business_operation operation " +
            "LEFT JOIN audit_event audit ON audit.business_operation_id = operation.id " +
            "AND audit.action_code = 'STUDENT_RESUME_UPLOAD' WHERE operation.actor_account_id = ? " +
            "AND operation.action_code = 'STUDENT_RESUME_UPLOAD' AND operation.request_id = ? ORDER BY audit.id LIMIT 1",
            (rs, n) -> {
                long fileId = rs.getLong("file_id");
                boolean fileIdMissing = rs.wasNull();
                return new ResumeUploadOperation(rs.getLong("id"), rs.getString("request_fingerprint"),
                    rs.getString("result_code"), fileIdMissing ? null : Long.valueOf(fileId));
            }, accountId, requestId);
        return rows.isEmpty() ? Optional.<ResumeUploadOperation>empty() : Optional.of(rows.get(0));
    }

    public Long insertResumeUploadOperation(Long accountId, String requestId, String fingerprint) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("INSERT INTO business_operation " +
                "(actor_account_id, actor_kind, action_code, batch_id, request_id, request_fingerprint, result_code, started_at) " +
                "VALUES (?, 'STUDENT', 'STUDENT_RESUME_UPLOAD', NULL, ?, ?, 'IN_PROGRESS', UTC_TIMESTAMP(3))", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, accountId); ps.setString(2, requestId); ps.setString(3, fingerprint); return ps;
        }, key);
        Number id = key.getKey();
        if (id == null) throw new IllegalStateException("No resume upload operation id returned");
        return Long.valueOf(id.longValue());
    }

    public Long insertStudentResumeFile(Long accountId, String filename, String mediaType, long size,
                                        String digest, String storageKey) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("INSERT INTO managed_file " +
                "(owner_account_id, purpose_code, original_filename, media_type, file_size_bytes, content_digest, " +
                "storage_key, uploaded_at, retention_basis, file_status) " +
                "VALUES (?, 'STUDENT_RESUME', ?, ?, ?, ?, ?, UTC_TIMESTAMP(3), 'STUDENT_PROFILE', 'PENDING')",
                Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, accountId); ps.setString(2, filename); ps.setString(3, mediaType);
            ps.setLong(4, size); ps.setString(5, digest); ps.setString(6, storageKey); return ps;
        }, key);
        Number id = key.getKey();
        if (id == null) throw new IllegalStateException("No student resume file id returned");
        return Long.valueOf(id.longValue());
    }

    public void insertResumeUploadAudit(Long accountId, Long studentId, Long fileId, Long operationId) {
        jdbcTemplate.update("INSERT INTO audit_event(actor_account_id, actor_kind, actor_role, scope_basis, object_type, " +
            "object_id, action_code, after_values_text, occurred_at, business_operation_id) " +
            "VALUES (?, 'STUDENT', 'STUDENT', ?, 'MANAGED_FILE', ?, 'STUDENT_RESUME_UPLOAD', ?, UTC_TIMESTAMP(3), ?)",
            accountId, "studentId=" + studentId, fileId, "{\"fileStatus\":\"PENDING\"}", operationId);
    }

    public void completeResumeUploadOperation(Long operationId) {
        int changed = jdbcTemplate.update("UPDATE business_operation SET result_code = 'OK', completed_at = UTC_TIMESTAMP(3), " +
            "row_version = row_version + 1 WHERE id = ? AND result_code = 'IN_PROGRESS'", operationId);
        if (changed != 1) throw new IllegalStateException("Resume upload operation was not completed");
    }

    public Optional<StudentProfileVO.StudentResumeVO> findStudentResume(Long fileId, Long accountId) {
        List<StudentProfileVO.StudentResumeVO> rows = jdbcTemplate.query("SELECT id, original_filename, file_size_bytes, " +
            "DATE_FORMAT(uploaded_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS uploaded_at, file_status FROM managed_file " +
            "WHERE id = ? AND owner_account_id = ? AND purpose_code = 'STUDENT_RESUME'", (rs, n) ->
            new StudentProfileVO.StudentResumeVO(rs.getLong("id"), rs.getString("original_filename"),
                Long.valueOf(rs.getLong("file_size_bytes")), rs.getString("uploaded_at"), toScanStatus(rs.getString("file_status"))),
            fileId, accountId);
        return rows.isEmpty() ? Optional.<StudentProfileVO.StudentResumeVO>empty() : Optional.of(rows.get(0));
    }

    public static class ResumeUploadOperation {
        public final Long id;
        public final String fingerprint;
        public final String resultCode;
        public final Long fileId;
        ResumeUploadOperation(Long id, String fingerprint, String resultCode, Long fileId) {
            this.id = id; this.fingerprint = fingerprint; this.resultCode = resultCode; this.fileId = fileId;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String toScanStatus(String storedStatus) {
        if ("PENDING".equals(storedStatus) || "AVAILABLE".equals(storedStatus) || "REJECTED".equals(storedStatus)) {
            return storedStatus;
        }
        // Не отображать неизвестный внутренний статус как可下载文件；返回 REJECTED 使前端 fail-closed。
        return "REJECTED";
    }
}
