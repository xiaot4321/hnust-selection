package cn.hnust.selection.repository;

import cn.hnust.selection.vo.AdminTeacherProfileVersionVO;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
public class AdminTeacherProfileReviewRepository {
    private final JdbcTemplate jdbc;
    public AdminTeacherProfileReviewRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long countPending(Long collegeId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM teacher_public_profile_version profile " +
            "JOIN teacher teacher ON teacher.id = profile.teacher_id WHERE teacher.college_id = ? " +
            "AND profile.review_status = 'PENDING_REVIEW'", Long.class, collegeId);
        return count == null ? 0L : count.longValue();
    }

    public List<AdminTeacherProfileVersionVO> listPending(Long collegeId, int pageNo, int pageSize) {
        long offset = ((long) pageNo - 1L) * pageSize;
        return jdbc.query(viewSql() + " WHERE teacher.college_id = ? AND profile.review_status = 'PENDING_REVIEW' " +
            "ORDER BY profile.submitted_at ASC, profile.id ASC LIMIT ? OFFSET ?",
            this::mapView, collegeId, pageSize, offset);
    }

    public Optional<AdminTeacherProfileVersionVO> findView(Long versionId) {
        List<AdminTeacherProfileVersionVO> rows = jdbc.query(viewSql() + " WHERE profile.id = ?", this::mapView, versionId);
        return rows.isEmpty() ? Optional.<AdminTeacherProfileVersionVO>empty() : Optional.of(rows.get(0));
    }

    public Optional<ReviewTarget> lockTarget(Long versionId) {
        String sql = "SELECT profile.id, profile.teacher_id, teacher.college_id, profile.version_no, profile.review_status, " +
            "profile.row_version, teacher.current_public_profile_version_id " +
            "FROM teacher_public_profile_version profile JOIN teacher teacher ON teacher.id = profile.teacher_id " +
            "WHERE profile.id = ? FOR UPDATE";
        List<ReviewTarget> rows = jdbc.query(sql, (rs, n) -> {
            long published = rs.getLong("current_public_profile_version_id");
            boolean publishedMissing = rs.wasNull();
            return new ReviewTarget(rs.getLong("id"), rs.getLong("teacher_id"), rs.getLong("college_id"),
                rs.getInt("version_no"), rs.getString("review_status"), rs.getLong("row_version"),
                publishedMissing ? null : Long.valueOf(published));
        }, versionId);
        return rows.isEmpty() ? Optional.<ReviewTarget>empty() : Optional.of(rows.get(0));
    }

    public boolean updateReview(ReviewTarget target, String decision, Long actorId, String comment) {
        String status = "APPROVE".equals(decision) ? "APPROVED" : "REJECTED";
        int changed = jdbc.update("UPDATE teacher_public_profile_version SET review_status = ?, reviewed_by = ?, " +
            "reviewed_at = UTC_TIMESTAMP(3), review_comment = ?, published_at = IF(? = 'APPROVE', UTC_TIMESTAMP(3), published_at), " +
            "row_version = row_version + 1 WHERE id = ? AND review_status = 'PENDING_REVIEW' AND row_version = ?",
            status, actorId, comment, decision, target.versionId, target.rowVersion);
        if (changed != 1) return false;
        if ("APPROVE".equals(decision)) {
            jdbc.update("UPDATE teacher SET current_public_profile_version_id = ?, row_version = row_version + 1 " +
                "WHERE id = ?", target.versionId, target.teacherId);
        }
        return true;
    }

    public Optional<Operation> findOperation(Long actorId, String key) {
        List<Operation> rows = jdbc.query("SELECT operation.request_fingerprint, operation.result_code, audit.object_id " +
            "FROM business_operation operation LEFT JOIN audit_event audit ON audit.business_operation_id = operation.id " +
            "AND audit.action_code = operation.action_code WHERE operation.actor_account_id = ? " +
            "AND operation.action_code = 'TEACHER_PROFILE_REVIEW' AND operation.request_id = ? ORDER BY audit.id LIMIT 1",
            (rs, n) -> {
                long id = rs.getLong("object_id");
                return new Operation(rs.getString("request_fingerprint"), rs.getString("result_code"),
                    rs.wasNull() ? null : Long.valueOf(id));
            }, actorId, key);
        return rows.isEmpty() ? Optional.<Operation>empty() : Optional.of(rows.get(0));
    }

    public Long insertOperation(Long actorId, Long collegeId, String key, String fingerprint) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("INSERT INTO business_operation(actor_account_id, actor_kind, " +
                "action_code, college_id, request_id, request_fingerprint, result_code, started_at) " +
                "VALUES (?, 'ADMIN', 'TEACHER_PROFILE_REVIEW', ?, ?, ?, 'IN_PROGRESS', UTC_TIMESTAMP(3))", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, actorId); ps.setLong(2, collegeId); ps.setString(3, key); ps.setString(4, fingerprint); return ps;
        }, holder);
        Number id = holder.getKey();
        if (id == null) throw new IllegalStateException("Database did not return an ID for business_operation");
        return Long.valueOf(id.longValue());
    }

    public void insertAudit(Long operationId, Long actorId, Long collegeId, Long versionId, String before, String after,
        String comment) {
        jdbc.update("INSERT INTO audit_event(actor_account_id, actor_kind, actor_role, scope_basis, object_type, object_id, " +
            "action_code, before_values_text, after_values_text, reason, approval_comment, occurred_at, business_operation_id) " +
            "VALUES (?, 'ADMIN', 'ADMIN', ?, 'TEACHER_PUBLIC_PROFILE_VERSION', ?, 'TEACHER_PROFILE_REVIEW', ?, ?, ?, ?, " +
            "UTC_TIMESTAMP(3), ?)", actorId, "collegeId=" + collegeId, versionId, before, after, comment, comment, operationId);
    }

    public void completeOperation(Long operationId) {
        jdbc.update("UPDATE business_operation SET result_code = 'OK', completed_at = UTC_TIMESTAMP(3), row_version = row_version + 1 " +
            "WHERE id = ? AND result_code = 'IN_PROGRESS'", operationId);
    }

    private String viewSql() {
        return "SELECT profile.id AS version_id, profile.teacher_id, teacher.employee_no, teacher.full_name, teacher.college_id, " +
            "college.name AS college_name, profile.version_no, profile.review_status, profile.research_directions, profile.biography, " +
            "DATE_FORMAT(profile.submitted_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS submitted_at, published.version_no AS published_version_no, " +
            "published.research_directions AS published_directions, published.biography AS published_biography, profile.row_version " +
            "FROM teacher_public_profile_version profile JOIN teacher teacher ON teacher.id = profile.teacher_id " +
            "JOIN college college ON college.id = teacher.college_id LEFT JOIN teacher_public_profile_version published " +
            "ON published.id = teacher.current_public_profile_version_id AND published.published_at IS NOT NULL";
    }

    private AdminTeacherProfileVersionVO mapView(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        AdminTeacherProfileVersionVO value = new AdminTeacherProfileVersionVO();
        value.setVersionId(rs.getLong("version_id")); value.setTeacherId(rs.getLong("teacher_id"));
        value.setEmployeeNo(rs.getString("employee_no")); value.setFullName(rs.getString("full_name"));
        value.setCollegeId(rs.getLong("college_id")); value.setCollegeName(rs.getString("college_name"));
        value.setVersionNo(rs.getInt("version_no")); value.setReviewStatus(rs.getString("review_status"));
        value.setResearchDirections(rs.getString("research_directions")); value.setBiography(rs.getString("biography"));
        value.setSubmittedAt(rs.getString("submitted_at"));
        int publishedVersion = rs.getInt("published_version_no");
        value.setCurrentPublishedVersionNo(rs.wasNull() ? null : Integer.valueOf(publishedVersion));
        value.setCurrentPublishedResearchDirections(rs.getString("published_directions"));
        value.setCurrentPublishedBiography(rs.getString("published_biography"));
        long rowVersion = rs.getLong("row_version"); value.setRowVersion(Long.valueOf(rowVersion));
        value.setEtag(etag(value.getVersionId(), rowVersion));
        return value;
    }

    public static String etag(Long versionId, long rowVersion) { return "teacher-profile-" + versionId + "-" + rowVersion; }

    public static class ReviewTarget {
        public final Long versionId, teacherId, collegeId, rowVersion, currentPublishedVersionId;
        public final Integer versionNo; public final String status;
        ReviewTarget(Long versionId, Long teacherId, Long collegeId, Integer versionNo, String status,
            Long rowVersion, Long currentPublishedVersionId) {
            this.versionId=versionId; this.teacherId=teacherId; this.collegeId=collegeId; this.versionNo=versionNo;
            this.status=status; this.rowVersion=rowVersion; this.currentPublishedVersionId=currentPublishedVersionId;
        }
    }
    public static class Operation {
        public final String fingerprint, resultCode; public final Long objectId;
        Operation(String fingerprint, String resultCode, Long objectId) {
            this.fingerprint=fingerprint; this.resultCode=resultCode; this.objectId=objectId;
        }
    }
}
