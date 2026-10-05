package cn.hnust.selection.repository;

import cn.hnust.selection.entity.AcademicYearEntity;
import cn.hnust.selection.entity.AnnualEligibilityEntity;
import cn.hnust.selection.entity.CollegeEntity;
import cn.hnust.selection.entity.MajorEntity;
import cn.hnust.selection.entity.PersonnelEntity;
import cn.hnust.selection.entity.PersonnelImportEntity;
import cn.hnust.selection.entity.PersonnelImportRowEntity;
import cn.hnust.selection.entity.PersonnelOperationEntity;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 人员管理模块的数据访问层。
 *
 * <p>该类只执行参数化 SQL 和映射结果，不决定谁能访问学院、资格如何变更或一行名单是否有效；
 * 这些规则全部由 service.impl 中的应用服务先行检查。</p>
 */
@Repository
public class PersonnelManagementRepository {
    private final JdbcTemplate jdbc;

    public PersonnelManagementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CollegeEntity> findCollege(Long id) {
        try {
            return Optional.of(jdbc.queryForObject(
                "SELECT id, college_code, name, is_active FROM college WHERE id = ? AND is_active = TRUE",
                (rs, row) -> new CollegeEntity(rs.getLong("id"), rs.getString("college_code"),
                    rs.getString("name"), rs.getBoolean("is_active")), id));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    /** 返回所有启用学院的最小展示字段，供系统级总管理员选择目标业务范围。 */
    public List<CollegeEntity> listActiveColleges() {
        return jdbc.query("SELECT id, college_code, name, is_active FROM college WHERE is_active = TRUE " +
                "ORDER BY college_code, id",
            (rs, row) -> new CollegeEntity(rs.getLong("id"), rs.getString("college_code"),
                rs.getString("name"), rs.getBoolean("is_active")));
    }

    public List<AcademicYearEntity> listAcademicYears() {
        return jdbc.query("SELECT id, year_code, display_name FROM academic_year " +
                "ORDER BY year_code DESC, id DESC",
            (rs, row) -> new AcademicYearEntity(rs.getLong("id"), rs.getString("year_code"),
                rs.getString("display_name")));
    }

    public boolean academicYearExists(Long id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM academic_year WHERE id = ?", Integer.class, id);
        return count != null && count.intValue() > 0;
    }

    public List<MajorEntity> listMajors(Long collegeId, Boolean activeOnly) {
        String sql = "SELECT id, college_id, major_code, name, is_active, valid_from, valid_to, row_version " +
            "FROM major WHERE college_id = ?" + (Boolean.TRUE.equals(activeOnly) ? " AND is_active = TRUE" : "") +
            " ORDER BY is_active DESC, major_code, id";
        return jdbc.query(sql, (rs, row) -> major(rs), collegeId);
    }

    /** 所有已登录用户可读取的启用目录，不包含依据、维护者或停用专业。 */
    public List<MajorEntity> listPublicMajors(Long collegeId) {
        String sql = "SELECT id, college_id, major_code, name, is_active, valid_from, valid_to, row_version " +
            "FROM major WHERE is_active = TRUE" + (collegeId == null ? "" : " AND college_id = ?") +
            " ORDER BY college_id, major_code, id";
        return collegeId == null ? jdbc.query(sql, (rs, row) -> major(rs)) :
            jdbc.query(sql, (rs, row) -> major(rs), collegeId);
    }

    public Optional<MajorEntity> findMajor(Long majorId) {
        try {
            return Optional.of(jdbc.queryForObject(
                "SELECT id, college_id, major_code, name, is_active, valid_from, valid_to, row_version " +
                    "FROM major WHERE id = ?", (rs, row) -> major(rs), majorId));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public Optional<MajorEntity> findMajorByCode(Long collegeId, String majorCode) {
        try {
            return Optional.of(jdbc.queryForObject(
                "SELECT id, college_id, major_code, name, is_active, valid_from, valid_to, row_version " +
                    "FROM major WHERE college_id = ? AND major_code = ?",
                (rs, row) -> major(rs), collegeId, majorCode));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public Long insertMajor(Long collegeId, String code, String name, java.sql.Date validFrom,
                            java.sql.Date validTo, String basis) {
        return insert("INSERT INTO major (college_id, major_code, name, is_active, valid_from, valid_to, change_basis) " +
            "VALUES (?, ?, ?, TRUE, ?, ?, ?)", "major", collegeId, code, name, validFrom, validTo, basis);
    }

    public void updateMajor(Long majorId, String name, boolean active, java.sql.Date validFrom,
                            java.sql.Date validTo, String basis) {
        jdbc.update("UPDATE major SET name = ?, is_active = ?, valid_from = ?, valid_to = ?, change_basis = ?, " +
                "row_version = row_version + 1 WHERE id = ?",
            name, active, validFrom, validTo, basis, majorId);
    }

    public List<PersonnelEntity> listStudents(Long collegeId, String identifier, int limit, long offset) {
        String sql = "SELECT s.id, s.account_id, a.login_identifier, s.student_no AS identifier, s.full_name, " +
                "s.college_id, c.name AS college_name, m.major_code, m.name AS major_name, s.degree_type, " +
                "s.enrollment_year_code, s.classification_version, CAST(NULL AS CHAR) AS review_status " +
                "FROM student s JOIN account a ON a.id = s.account_id JOIN college c ON c.id = s.college_id " +
                "JOIN major m ON m.id = s.major_id WHERE s.college_id = ?" +
                (identifier == null || identifier.trim().isEmpty() ? "" : " AND s.student_no = ?") +
                " ORDER BY s.student_no, s.id LIMIT ? OFFSET ?";
        return identifier == null || identifier.trim().isEmpty()
            ? jdbc.query(sql, (rs, row) -> person(rs), collegeId, limit, offset)
            : jdbc.query(sql, (rs, row) -> person(rs), collegeId, identifier.trim(), limit, offset);
    }

    public long countStudents(Long collegeId, String identifier) {
        Long count = identifier == null || identifier.trim().isEmpty()
            ? jdbc.queryForObject("SELECT COUNT(*) FROM student WHERE college_id = ?", Long.class, collegeId)
            : jdbc.queryForObject("SELECT COUNT(*) FROM student WHERE college_id = ? AND student_no = ?",
                Long.class, collegeId, identifier.trim());
        return count == null ? 0 : count.longValue();
    }

    public List<PersonnelEntity> listTeachers(Long collegeId, String identifier, int limit, long offset) {
        String sql = "SELECT t.id, t.account_id, a.login_identifier, t.employee_no AS identifier, t.full_name, " +
                "t.college_id, c.name AS college_name, CAST(NULL AS CHAR) AS major_code, " +
                "CAST(NULL AS CHAR) AS major_name, CAST(NULL AS CHAR) AS degree_type, " +
                "CAST(NULL AS CHAR) AS enrollment_year_code, CAST(NULL AS SIGNED) AS classification_version, " +
                "p.review_status FROM teacher t JOIN account a ON a.id = t.account_id " +
                "JOIN college c ON c.id = t.college_id " +
                "LEFT JOIN teacher_public_profile_version p ON p.id = t.current_public_profile_version_id " +
                "WHERE t.college_id = ?" + (identifier == null || identifier.trim().isEmpty() ? "" : " AND t.employee_no = ?") +
                " ORDER BY t.employee_no, t.id LIMIT ? OFFSET ?";
        return identifier == null || identifier.trim().isEmpty()
            ? jdbc.query(sql, (rs, row) -> person(rs), collegeId, limit, offset)
            : jdbc.query(sql, (rs, row) -> person(rs), collegeId, identifier.trim(), limit, offset);
    }

    public long countTeachers(Long collegeId, String identifier) {
        Long count = identifier == null || identifier.trim().isEmpty()
            ? jdbc.queryForObject("SELECT COUNT(*) FROM teacher WHERE college_id = ?", Long.class, collegeId)
            : jdbc.queryForObject("SELECT COUNT(*) FROM teacher WHERE college_id = ? AND employee_no = ?",
                Long.class, collegeId, identifier.trim());
        return count == null ? 0 : count.longValue();
    }

    public boolean loginIdentifierExists(String login) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE login_identifier = ?", Integer.class, login);
        return count != null && count.intValue() > 0;
    }

    public boolean personIdentifierExists(String type, String identifier) {
        String table = "STUDENT".equals(type) ? "student" : "teacher";
        String column = "STUDENT".equals(type) ? "student_no" : "employee_no";
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
            Integer.class, identifier);
        return count != null && count.intValue() > 0;
    }

    public Long insertAccount(String loginIdentifier, String role) {
        return insert("INSERT INTO account (login_identifier, role_code, account_status, password_hash, " +
            "must_change_password, credential_changed_at) VALUES (?, ?, 'ACTIVE', NULL, TRUE, NULL)",
            "account", loginIdentifier, role);
    }

    public Long insertStudent(Long accountId, String studentNo, String name, Long collegeId, Long majorId,
                              String degreeType, String enrollmentYearCode) {
        return insert("INSERT INTO student (account_id, student_no, full_name, college_id, major_id, degree_type, " +
                "classification_version, enrollment_year_code) VALUES (?, ?, ?, ?, ?, ?, 1, ?)", "student",
            accountId, studentNo, name, collegeId, majorId, degreeType, enrollmentYearCode);
    }

    public void insertInitialStudentProfile(Long studentId, Long actorAccountId) {
        jdbc.update("INSERT INTO student_profile_version (student_id, version_no, biography, contact_text, " +
            "resume_file_id, changed_by, changed_at) VALUES (?, 1, NULL, NULL, NULL, ?, UTC_TIMESTAMP(3))",
            studentId, actorAccountId);
    }

    public void insertInitialClassificationRevision(Long studentId, Long majorId, String degreeType,
                                                    String basis, String reason, Long actorAccountId) {
        jdbc.update("INSERT INTO student_classification_revision (student_id, version_no, from_major_id, to_major_id, " +
                "from_degree_type, to_degree_type, basis, reason, changed_by, changed_at) " +
                "VALUES (?, 1, NULL, ?, NULL, ?, ?, ?, ?, UTC_TIMESTAMP(3))",
            studentId, majorId, degreeType, basis, reason, actorAccountId);
    }

    public Long insertTeacher(Long accountId, String employeeNo, String name, Long collegeId) {
        return insert("INSERT INTO teacher (account_id, employee_no, full_name, college_id) VALUES (?, ?, ?, ?)",
            "teacher", accountId, employeeNo, name, collegeId);
    }

    public Long insertInitialTeacherProfile(Long teacherId) {
        Long profileId = insert("INSERT INTO teacher_public_profile_version (teacher_id, version_no, " +
                "research_directions, biography, review_status, submitted_at) " +
                "VALUES (?, 1, NULL, NULL, 'DRAFT', UTC_TIMESTAMP(3))", "teacher_public_profile_version", teacherId);
        jdbc.update("UPDATE teacher SET current_public_profile_version_id = ?, row_version = row_version + 1 WHERE id = ?",
            profileId, teacherId);
        return profileId;
    }

    public void lockPerson(String type, Long personId, Long expectedCollegeId) {
        String table = "STUDENT".equals(type) ? "student" : "teacher";
        try {
            jdbc.queryForObject("SELECT id FROM " + table + " WHERE id = ? AND college_id = ? FOR UPDATE",
                Long.class, personId, expectedCollegeId);
        } catch (EmptyResultDataAccessException exception) {
            throw new IllegalArgumentException("人员记录不存在或不属于所选学院");
        }
    }

    public Long insertEligibility(Long yearId, Long collegeId, String type, Long personId, String status,
                                 String evidenceType, String evidenceReference, String sourceName, Long actorId) {
        Long studentId = "STUDENT".equals(type) ? personId : null;
        Long teacherId = "TEACHER".equals(type) ? personId : null;
        return insert("INSERT INTO annual_eligibility (academic_year_id, college_id, student_id, teacher_id, " +
                "eligibility_status, evidence_type, evidence_reference, source_name, valid_from, valid_to, changed_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(3), NULL, ?)", "annual_eligibility",
            yearId, collegeId, studentId, teacherId, status, evidenceType, evidenceReference, sourceName, actorId);
    }

    public void claimEligibilitySlot(Long yearId, Long collegeId, String type, Long personId, Long eligibilityId) {
        Long studentId = "STUDENT".equals(type) ? personId : null;
        Long teacherId = "TEACHER".equals(type) ? personId : null;
        jdbc.update("INSERT INTO annual_eligibility_slot (academic_year_id, college_id, student_id, teacher_id, " +
                "eligibility_id, claimed_at) VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(3)) " +
                "ON DUPLICATE KEY UPDATE college_id = VALUES(college_id), eligibility_id = VALUES(eligibility_id), " +
                "claimed_at = VALUES(claimed_at)", yearId, collegeId, studentId, teacherId, eligibilityId);
    }

    public Optional<Long> currentEligibilityId(Long yearId, String type, Long personId) {
        String column = "STUDENT".equals(type) ? "student_id" : "teacher_id";
        try {
            return Optional.of(jdbc.queryForObject("SELECT eligibility_id FROM annual_eligibility_slot " +
                "WHERE academic_year_id = ? AND " + column + " = ? FOR UPDATE", Long.class, yearId, personId));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public void closeEligibility(Long eligibilityId) {
        jdbc.update("UPDATE annual_eligibility SET valid_to = UTC_TIMESTAMP(3), row_version = row_version + 1 " +
            "WHERE id = ? AND valid_to IS NULL", eligibilityId);
    }

    public List<AnnualEligibilityEntity> listEligibility(Long collegeId, Long yearId,
                                                         String personType, String identifier,
                                                         boolean history) {
        StringBuilder sql = new StringBuilder("SELECT e.id, e.academic_year_id, y.year_code, e.college_id, " +
            "CASE WHEN e.student_id IS NULL THEN 'TEACHER' ELSE 'STUDENT' END AS person_type, " +
            "COALESCE(s.id, t.id) AS person_id, COALESCE(s.student_no, t.employee_no) AS person_identifier, " +
            "COALESCE(s.full_name, t.full_name) AS person_name, e.eligibility_status, e.evidence_type, " +
            "e.evidence_reference, e.source_name, DATE_FORMAT(e.valid_from, '%Y-%m-%dT%H:%i:%s.%fZ') AS valid_from, " +
            "DATE_FORMAT(e.valid_to, '%Y-%m-%dT%H:%i:%s.%fZ') AS valid_to, e.changed_by " +
            "FROM annual_eligibility e JOIN academic_year y ON y.id = e.academic_year_id " +
            "LEFT JOIN student s ON s.id = e.student_id LEFT JOIN teacher t ON t.id = e.teacher_id " +
            "WHERE e.college_id = ?");
        List<Object> args = new ArrayList<Object>();
        args.add(collegeId);
        if (yearId != null) {
            sql.append(" AND e.academic_year_id = ?");
            args.add(yearId);
        }
        if (personType != null) {
            sql.append(" AND CASE WHEN e.student_id IS NULL THEN 'TEACHER' ELSE 'STUDENT' END = ?");
            args.add(personType);
        }
        if (identifier != null && !identifier.trim().isEmpty()) {
            sql.append(" AND COALESCE(s.student_no, t.employee_no) = ?");
            args.add(identifier.trim());
        }
        if (!history) {
            sql.append(" AND e.valid_to IS NULL");
        }
        sql.append(" ORDER BY y.year_code DESC, person_identifier, e.id DESC");
        return jdbc.query(sql.toString(), (rs, row) -> new AnnualEligibilityEntity(
            rs.getLong("id"), rs.getLong("academic_year_id"), rs.getString("year_code"),
            rs.getLong("college_id"), rs.getString("person_type"), rs.getLong("person_id"),
            rs.getString("person_identifier"), rs.getString("person_name"), rs.getString("eligibility_status"),
            rs.getString("evidence_type"), rs.getString("evidence_reference"), rs.getString("source_name"),
            rs.getString("valid_from"), rs.getString("valid_to"), rs.getLong("changed_by")), args.toArray());
    }

    public Long insertOperation(Long actorId, String action, Long collegeId, String requestId, String fingerprint) {
        return insert("INSERT INTO business_operation (actor_account_id, actor_kind, action_code, college_id, " +
                "batch_id, request_id, request_fingerprint, result_code, started_at) " +
                "VALUES (?, 'ACCOUNT', ?, ?, NULL, ?, ?, 'IN_PROGRESS', UTC_TIMESTAMP(3))",
            "business_operation", actorId, action, collegeId, requestId, fingerprint);
    }

    public Optional<PersonnelOperationEntity> findOperation(Long actorId, String action, String requestId) {
        try {
            return Optional.of(jdbc.queryForObject("SELECT id, request_fingerprint, result_code " +
                    "FROM business_operation WHERE actor_account_id = ? AND action_code = ? AND request_id = ?",
                (rs, row) -> new PersonnelOperationEntity(rs.getLong("id"), rs.getString("request_fingerprint"),
                    rs.getString("result_code")), actorId, action, requestId));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public void completeOperation(Long operationId) {
        jdbc.update("UPDATE business_operation SET result_code = 'OK', completed_at = UTC_TIMESTAMP(3), " +
            "row_version = row_version + 1 WHERE id = ?", operationId);
    }

    public void failOperation(Long operationId) {
        jdbc.update("UPDATE business_operation SET result_code = 'FAILED', completed_at = UTC_TIMESTAMP(3), " +
            "row_version = row_version + 1 WHERE id = ?", operationId);
    }

    public void insertAudit(Long operationId, Long actorId, Long collegeId, String objectType,
                            Long objectId, String action, String afterJson, String reason) {
        jdbc.update("INSERT INTO audit_event (actor_account_id, actor_kind, actor_role, scope_basis, object_type, " +
                "object_id, action_code, before_values_text, after_values_text, reason, approval_comment, " +
                "occurred_at, business_operation_id) VALUES (?, 'ACCOUNT', 'ADMIN', ?, ?, ?, ?, NULL, ?, ?, NULL, " +
                "UTC_TIMESTAMP(3), ?)", actorId, "COLLEGE_ADMIN collegeId=" + collegeId, objectType,
            objectId, action, afterJson, reason, operationId);
    }

    public Long insertTemporaryCredential(Long accountId, String hash, Long operationId) {
        return insert("INSERT INTO temporary_credential (account_id, credential_hash, issued_at, expires_at, " +
                "shown_at, used_at, revoked_at, issued_operation_id) VALUES (?, ?, UTC_TIMESTAMP(3), NULL, " +
                "UTC_TIMESTAMP(3), NULL, NULL, ?)",
            "temporary_credential", accountId, hash, operationId);
    }

    public String credentialExpiry(Long credentialId) {
        return jdbc.queryForObject("SELECT DATE_FORMAT(expires_at, '%Y-%m-%dT%H:%i:%s.000Z') " +
            "FROM temporary_credential WHERE id = ?", String.class, credentialId);
    }

    public void revokeUnusedCredentials(Long accountId) {
        jdbc.update("UPDATE temporary_credential SET revoked_at = UTC_TIMESTAMP(3), row_version = row_version + 1 " +
            "WHERE account_id = ? AND used_at IS NULL AND revoked_at IS NULL", accountId);
    }

    public void setManagedFile(Long owner, String filename, String mediaType, long length,
                               String digest, String storageKey) {
        jdbc.update("INSERT INTO managed_file (owner_account_id, purpose_code, original_filename, media_type, " +
                "file_size_bytes, content_digest, storage_key, uploaded_at, retention_basis, file_status) " +
                "VALUES (?, 'PERSONNEL_IMPORT_SOURCE', ?, ?, ?, ?, ?, UTC_TIMESTAMP(3), 'PERSONNEL_IMPORT', 'ACTIVE')",
            owner, filename, mediaType, length, digest, storageKey);
    }

    public Long managedFileId(String storageKey) {
        return jdbc.queryForObject("SELECT id FROM managed_file WHERE storage_key = ?", Long.class, storageKey);
    }

    public Long insertImport(Long actorId, Long collegeId, Long yearId, String personType,
                             Long fileId, Long operationId) {
        return insert("INSERT INTO personnel_import (actor_account_id, college_id, academic_year_id, person_type, " +
                "template_version, source_file_id, business_operation_id, import_status, submitted_at, " +
                "accepted_count, rejected_count) VALUES (?, ?, ?, ?, '1.0', ?, ?, 'PROCESSING', " +
                "UTC_TIMESTAMP(3), 0, 0)", "personnel_import", actorId, collegeId, yearId,
            personType, fileId, operationId);
    }

    public void insertImportRow(Long importId, int rowNumber, String identifier, String status,
                                String errorCode, String errorMessage, Long personId, Long eligibilityId,
                                boolean teacher) {
        jdbc.update("INSERT INTO personnel_import_row (import_id, row_number, person_identifier, row_status, " +
                "error_code, error_message, person_id, teacher_id, eligibility_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            importId, rowNumber, identifier == null || identifier.isEmpty() ? "(空)" : identifier, status,
            errorCode, errorMessage, teacher ? null : personId, teacher ? personId : null, eligibilityId);
    }

    public void finishImport(Long importId, int accepted, int rejected, String status) {
        jdbc.update("UPDATE personnel_import SET accepted_count = ?, rejected_count = ?, import_status = ?, " +
            "completed_at = UTC_TIMESTAMP(3), row_version = row_version + 1 WHERE id = ?",
            accepted, rejected, status, importId);
    }

    public Optional<PersonnelImportEntity> findImport(Long importId) {
        try {
            return Optional.of(jdbc.queryForObject("SELECT id, college_id, person_type, import_status, " +
                    "accepted_count, rejected_count FROM personnel_import WHERE id = ?",
                (rs, row) -> new PersonnelImportEntity(rs.getLong("id"), rs.getLong("college_id"),
                    rs.getString("person_type"), rs.getString("import_status"),
                    rs.getInt("accepted_count"), rs.getInt("rejected_count")), importId));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public Optional<Long> importIdForOperation(Long operationId) {
        try {
            return Optional.of(jdbc.queryForObject(
                "SELECT id FROM personnel_import WHERE business_operation_id = ?", Long.class, operationId));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public List<PersonnelImportRowEntity> listImportRows(Long importId) {
        return jdbc.query("SELECT row_number, person_identifier, row_status, error_code, error_message, " +
                "COALESCE(person_id, teacher_id) AS person_id, eligibility_id " +
                "FROM personnel_import_row WHERE import_id = ? ORDER BY row_number",
            (rs, row) -> new PersonnelImportRowEntity(rs.getInt("row_number"), rs.getString("person_identifier"),
                rs.getString("row_status"), rs.getString("error_code"), rs.getString("error_message"),
                asLong(rs.getObject("person_id")), asLong(rs.getObject("eligibility_id"))), importId);
    }

    public Optional<PersonnelEntity> findPersonByLogin(String type, String login) {
        String table = "STUDENT".equals(type) ? "student" : "teacher";
        String identifier = "STUDENT".equals(type) ? "student_no" : "employee_no";
        String studentFields = "s.id, s.account_id, a.login_identifier, s.student_no AS identifier, s.full_name, " +
            "s.college_id, c.name AS college_name, m.major_code, m.name AS major_name, s.degree_type, " +
            "s.enrollment_year_code, s.classification_version, CAST(NULL AS CHAR) AS review_status";
        String teacherFields = "t.id, t.account_id, a.login_identifier, t.employee_no AS identifier, t.full_name, " +
            "t.college_id, c.name AS college_name, CAST(NULL AS CHAR) AS major_code, CAST(NULL AS CHAR) AS major_name, " +
            "CAST(NULL AS CHAR) AS degree_type, CAST(NULL AS CHAR) AS enrollment_year_code, " +
            "CAST(NULL AS SIGNED) AS classification_version, p.review_status";
        String sql = "STUDENT".equals(type)
            ? "SELECT " + studentFields + " FROM student s JOIN account a ON a.id=s.account_id " +
                "JOIN college c ON c.id=s.college_id JOIN major m ON m.id=s.major_id WHERE a.login_identifier=?"
            : "SELECT " + teacherFields + " FROM teacher t JOIN account a ON a.id=t.account_id " +
                "JOIN college c ON c.id=t.college_id LEFT JOIN teacher_public_profile_version p " +
                "ON p.id=t.current_public_profile_version_id WHERE a.login_identifier=?";
        try {
            return Optional.of(jdbc.queryForObject(sql, (rs, row) -> person(rs), login));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public Optional<PersonnelEntity> findPersonByIdentifier(String type, String identifier) {
        String table = "STUDENT".equals(type) ? "student" : "teacher";
        String column = "STUDENT".equals(type) ? "student_no" : "employee_no";
        String query = "SELECT a.login_identifier FROM " + table +
            " p JOIN account a ON a.id=p.account_id WHERE p." + column + "=?";
        try {
            String login = jdbc.queryForObject(query, String.class, identifier);
            return findPersonByLogin(type, login);
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    private Long insert(String sql, String table, Object... parameters) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("数据库未返回 " + table + " 主键");
        return key.longValue();
    }

    private static MajorEntity major(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.Date from = rs.getDate("valid_from");
        java.sql.Date to = rs.getDate("valid_to");
        return new MajorEntity(rs.getLong("id"), rs.getLong("college_id"), rs.getString("major_code"),
            rs.getString("name"), rs.getBoolean("is_active"), from, to, rs.getLong("row_version"));
    }

    private static PersonnelEntity person(java.sql.ResultSet rs) throws java.sql.SQLException {
        Object majorCode = rs.getObject("major_code");
        Object majorName = rs.getObject("major_name");
        Object degree = rs.getObject("degree_type");
        Object enrollment = rs.getObject("enrollment_year_code");
        Object version = rs.getObject("classification_version");
        return new PersonnelEntity(rs.getLong("id"), rs.getLong("account_id"),
            rs.getString("login_identifier"), rs.getString("identifier"), rs.getString("full_name"),
            rs.getLong("college_id"), rs.getString("college_name"), (String) majorCode, (String) majorName,
            (String) degree, (String) enrollment, version == null ? null : ((Number) version).intValue(),
            rs.getString("review_status"));
    }

    private static Long asLong(Object value) {
        return value == null ? null : Long.valueOf(((Number) value).longValue());
    }
}
