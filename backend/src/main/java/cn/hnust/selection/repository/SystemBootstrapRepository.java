package cn.hnust.selection.repository;

import cn.hnust.selection.entity.CollegeEntity;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Optional;

/**
 * 首次总管理员初始化使用的数据访问层。
 *
 * <p>该 Repository 只执行 SQL；首次初始化是否允许、密码凭证生成、事务编排与一次性输出均由
 * Service/CLI 负责。所有写入必须位于同一个 InnoDB 事务，任何一项失败时学院、账号、权限、
 * 临时凭证、操作记录和审计记录一并回滚。</p>
 */
@Repository
public class SystemBootstrapRepository {
    private final JdbcTemplate jdbcTemplate;

    public SystemBootstrapRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 判断总管理员唯一槽位是否已经被使用。
     *
     * <p>检查包括历史行：初始化程序不尝试覆盖或清理已有槽位；若遇到既有记录，须走 TODO-24
     * 的线下核验、双人复核与留痕应急流程。</p>
     */
    public boolean hasReservedAdminManagerSlot() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM account_authorization WHERE authority_slot = 'ADMIN_ACCOUNT_MANAGER'",
            Integer.class);
        return count != null && count.intValue() > 0;
    }

    /** 按学院官方代码查找已建学院，初始化时只复用完全匹配且启用的记录。 */
    public Optional<CollegeEntity> findCollegeByCode(String collegeCode) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                "SELECT id, college_code, name, is_active FROM college WHERE college_code = ?",
                (resultSet, rowNum) -> new CollegeEntity(resultSet.getLong("id"),
                    resultSet.getString("college_code"), resultSet.getString("name"),
                    resultSet.getBoolean("is_active")),
                collegeCode));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    /** 避免不同官方代码重复建出同名学院；出现时要求操作者改用现有代码。 */
    public boolean collegeNameExists(String collegeName) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM college WHERE name = ?", Integer.class, collegeName);
        return count != null && count.intValue() > 0;
    }

    /** 检查学院代码是否已存在；临时代码生成时使用，避免随机碰撞覆盖或误用现有学院。 */
    public boolean collegeCodeExists(String collegeCode) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM college WHERE college_code = ?", Integer.class, collegeCode);
        return count != null && count.intValue() > 0;
    }

    /** 在同一事务中创建尚不存在的学院记录，ID 由数据库分配供授权外键使用。 */
    public Long insertCollege(String collegeCode, String collegeName) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO college (college_code, name, is_active) VALUES (?, ?, TRUE)",
                Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, collegeCode);
            statement.setString(2, collegeName);
            return statement;
        }, keyHolder);
        return generatedId(keyHolder, "college");
    }

    /** 检查登录标识是否已被任何角色占用；最终唯一性仍由 account 唯一索引兜底。 */
    public boolean loginIdentifierExists(String loginIdentifier) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM account WHERE login_identifier = ?", Integer.class, loginIdentifier);
        return count != null && count.intValue() > 0;
    }

    /** 创建仍处于首次改密状态的 ADMIN；正式密码字段为空，只有临时凭证哈希可用于首次认证。 */
    public Long insertInitialAdminAccount(String loginIdentifier) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO account (login_identifier, role_code, account_status, password_hash, " +
                    "must_change_password, credential_changed_at) " +
                    "VALUES (?, 'ADMIN', 'ACTIVE', NULL, TRUE, NULL)",
                Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, loginIdentifier);
            return statement;
        }, keyHolder);
        return generatedId(keyHolder, "account");
    }

    /** 为 bootstrap 建立一条无人工账号主体的 SYSTEM 操作，以便所有初始化审计事件可关联。 */
    public Long insertBootstrapOperation(Long collegeId, String requestId, String fingerprint) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO business_operation (actor_account_id, actor_kind, action_code, college_id, " +
                    "batch_id, request_id, request_fingerprint, result_code, started_at, completed_at) " +
                    "VALUES (NULL, 'SYSTEM', 'ADMIN_ACCOUNT_BOOTSTRAP', ?, NULL, ?, ?, 'IN_PROGRESS', " +
                    "UTC_TIMESTAMP(3), NULL)",
                Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, collegeId.longValue());
            statement.setString(2, requestId);
            statement.setString(3, fingerprint);
            return statement;
        }, keyHolder);
        return generatedId(keyHolder, "business_operation");
    }

    /** 创建不设到期时间、已作一次性展示登记的临时凭证；数据库只收到 BCrypt 哈希。 */
    public Long insertShownTemporaryCredential(Long accountId, String credentialHash, Long operationId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO temporary_credential (account_id, credential_hash, issued_at, expires_at, " +
                    "shown_at, used_at, revoked_at, issued_operation_id) " +
                    "VALUES (?, ?, UTC_TIMESTAMP(3), NULL, " +
                    "UTC_TIMESTAMP(3), NULL, NULL, ?)",
                Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, accountId.longValue());
            statement.setString(2, credentialHash);
            statement.setLong(3, operationId.longValue());
            return statement;
        }, keyHolder);
        return generatedId(keyHolder, "temporary_credential");
    }

    /**
     * 为唯一 ADMIN 账号绑定保留能力槽位。
     *
     * <p>schema 将 granted_by 设为非空 account 外键，但首次初始化没有先存的人工作者账号；因此此处
     * 以新建总管理员 ID 填入该必填关联，真实执行主体仍由对应 audit_event 的 SYSTEM 标记说明。</p>
     */
    public Long insertInitialAdminManagerAuthorization(Long accountId, Long collegeId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO account_authorization (account_id, college_id, batch_id, capability_code, " +
                    "authority_slot, basis, granted_by, granted_at, revoked_by, revoked_at) " +
                    "VALUES (?, ?, NULL, 'ADMIN_ACCOUNT_MANAGER', 'ADMIN_ACCOUNT_MANAGER', ?, ?, " +
                    "UTC_TIMESTAMP(3), NULL, NULL)",
                Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, accountId.longValue());
            statement.setLong(2, collegeId.longValue());
            statement.setString(3, "系统首次初始化（TODO-10）；实际操作主体记录为 SYSTEM");
            statement.setLong(4, accountId.longValue());
            return statement;
        }, keyHolder);
        return generatedId(keyHolder, "account_authorization");
    }

    /**
     * 给首次总管理员配置其所属学院的一般业务管理能力。
     *
     * <p>该授权保留总管理员的初始化学院业务记录和操作依据，供历史及审计查询使用。
     * 总管理员的跨学院管理员业务范围由 ADMIN_ACCOUNT_MANAGER 保留能力在服务端隐式授予。</p>
     */
    public Long insertInitialCollegeAdminAuthorization(Long accountId, Long collegeId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO account_authorization (account_id, college_id, batch_id, capability_code, " +
                    "authority_slot, basis, granted_by, granted_at, revoked_by, revoked_at) " +
                    "VALUES (?, ?, NULL, 'COLLEGE_ADMIN', NULL, ?, ?, UTC_TIMESTAMP(3), NULL, NULL)",
                Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, accountId.longValue());
            statement.setLong(2, collegeId.longValue());
            statement.setString(3, "总管理员初始化学院记录；管理员业务能力及数据范围由系统级总管理员权限覆盖");
            statement.setLong(4, accountId.longValue());
            return statement;
        }, keyHolder);
        return generatedId(keyHolder, "account_authorization");
    }

    /** 追加系统初始化的前后状态快照；不把任何密码或临时凭证明文写进审计表。 */
    public void insertAuditEvent(Long operationId, String objectType, Long objectId,
                                 String actionCode, Long collegeId,
                                 String beforeValues, String afterValues, String reason) {
        jdbcTemplate.update(
            "INSERT INTO audit_event (actor_account_id, actor_kind, actor_role, scope_basis, object_type, " +
                "object_id, action_code, before_values_text, after_values_text, reason, approval_comment, " +
                "occurred_at, business_operation_id) " +
                "VALUES (NULL, 'SYSTEM', NULL, ?, ?, ?, ?, ?, ?, ?, NULL, UTC_TIMESTAMP(3), ?)",
            "collegeId=" + collegeId, objectType, objectId, actionCode,
            beforeValues, afterValues, reason, operationId);
    }

    /** 初始化所有账户、授权、凭证和审计成功后再完成操作记录。 */
    public void completeBootstrapOperation(Long operationId) {
        int changed = jdbcTemplate.update(
            "UPDATE business_operation SET result_code = 'OK', completed_at = UTC_TIMESTAMP(3) WHERE id = ?",
            operationId);
        if (changed != 1) throw new IllegalStateException("Bootstrap operation row was not updated");
    }

    private static Long generatedId(KeyHolder keyHolder, String tableName) {
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("Database did not return an ID for " + tableName);
        return Long.valueOf(key.longValue());
    }

}
