package cn.hnust.selection.repository;

import cn.hnust.selection.entity.AdminAccountEntity;
import cn.hnust.selection.entity.AdminAccountOperationEntity;
import cn.hnust.selection.entity.IssuedCredentialEntity;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * 管理员账号生命周期 SQL。
 * 账号状态、幂等语义、凭证生成和审计编排由 Service 负责；所有写方法都要求调用方处于事务中。
 */
@Repository
public class AdminAccountLifecycleRepository {
    private final JdbcTemplate jdbcTemplate;

    public AdminAccountLifecycleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 按账号唯一登录标识检查冲突；唯一索引仍负责并发创建时的最终保护。 */
    public boolean loginIdentifierExists(String loginIdentifier) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM account WHERE login_identifier = ?", Integer.class, loginIdentifier);
        return count != null && count.intValue() > 0;
    }

    /** 按创建时间倒序分页列出管理员；只选择账号目录所需的非凭证字段。 */
    public List<AdminAccountEntity> findAdminAccounts(int pageSize, long offset) {
        return jdbcTemplate.query(
            "SELECT id, login_identifier, account_status, must_change_password, " +
                "DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS created_at " +
                "FROM account WHERE role_code = 'ADMIN' " +
                "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
            (resultSet, rowNumber) -> new AdminAccountEntity(
                resultSet.getLong("id"),
                resultSet.getString("login_identifier"),
                resultSet.getString("account_status"),
                resultSet.getBoolean("must_change_password"),
                resultSet.getString("created_at")),
            pageSize, offset);
    }

    /** 统计当前 ADMIN 账号总数，供列表分页元数据使用。 */
    public long countAdminAccounts() {
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM account WHERE role_code = 'ADMIN'", Long.class);
        return count == null ? 0L : count.longValue();
    }

    /** 创建活动状态、尚未设置正式密码且必须首次改密的 ADMIN。 */
    public Long insertAdminAccount(String loginIdentifier) {
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

    /** 确认目标账号从未占用总管理员保留槽位；应急恢复账号不得通过普通凭证重置入口操作。 */
    public boolean hasReservedAdminManagerCapability(Long accountId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM account_authorization WHERE account_id = ? " +
                "AND (authority_slot = 'ADMIN_ACCOUNT_MANAGER' OR capability_code = 'ADMIN_ACCOUNT_MANAGER')",
            Integer.class, accountId);
        return count != null && count.intValue() > 0;
    }

    /** 在新凭证签发前撤销目标账号所有未消费的旧临时凭证，包括已过期但未标记撤销的记录。 */
    public int revokeOutstandingTemporaryCredentials(Long accountId) {
        return jdbcTemplate.update(
            "UPDATE temporary_credential SET revoked_at = UTC_TIMESTAMP(3), row_version = row_version + 1 " +
                "WHERE account_id = ? AND used_at IS NULL AND revoked_at IS NULL", accountId);
    }

    /** 让密码重置即刻使既有 Session 失效，并要求新临时凭证首次改密。 */
    public int requirePasswordChange(Long accountId) {
        return jdbcTemplate.update(
            "UPDATE account SET password_hash = NULL, must_change_password = TRUE, " +
                "credential_changed_at = NULL, row_version = row_version + 1 WHERE id = ? AND role_code = 'ADMIN'",
            accountId);
    }

    /** 创建不设到期时间、已登记为一次性展示的凭证；持久化内容只有 BCrypt 哈希。 */
    public IssuedCredentialEntity insertShownTemporaryCredential(Long accountId, String credentialHash,
                                                                 Long operationId) {
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
        Long credentialId = generatedId(keyHolder, "temporary_credential");
        String expiresAt = jdbcTemplate.queryForObject(
            "SELECT DATE_FORMAT(expires_at, '%Y-%m-%dT%H:%i:%s.%fZ') " +
                "FROM temporary_credential WHERE id = ?", String.class, credentialId);
        return new IssuedCredentialEntity(credentialId, expiresAt);
    }

    /** 读取此前的幂等操作与其账号/凭证元数据；不会查询或返回凭证明文。 */
    public Optional<AdminAccountOperationEntity> findOperation(Long actorAccountId, String actionCode,
                                                               String requestId) {
        String sql = "SELECT operation.id AS operation_id, operation.request_fingerprint, operation.result_code, " +
            "audit.object_id AS account_id, account.login_identifier, " +
            "DATE_FORMAT(credential.expires_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS expires_at " +
            "FROM business_operation operation " +
            "LEFT JOIN audit_event audit ON audit.business_operation_id = operation.id " +
                "AND audit.object_type = 'ACCOUNT' AND audit.action_code = operation.action_code " +
            "LEFT JOIN account ON account.id = audit.object_id " +
            "LEFT JOIN temporary_credential credential ON credential.issued_operation_id = operation.id " +
            "WHERE operation.actor_account_id = ? AND operation.action_code = ? AND operation.request_id = ? " +
            "ORDER BY audit.id DESC, credential.id DESC LIMIT 1";
        List<AdminAccountOperationEntity> rows = jdbcTemplate.query(sql, (resultSet, rowNum) -> {
            long rawAccountId = resultSet.getLong("account_id");
            Long accountId = resultSet.wasNull() ? null : Long.valueOf(rawAccountId);
            return new AdminAccountOperationEntity(resultSet.getLong("operation_id"),
                resultSet.getString("request_fingerprint"), resultSet.getString("result_code"),
                accountId, resultSet.getString("login_identifier"), resultSet.getString("expires_at"));
        }, actorAccountId, actionCode, requestId);
        return rows.isEmpty() ? Optional.<AdminAccountOperationEntity>empty() : Optional.of(rows.get(0));
    }

    /** 建立幂等操作记录，与账号、临时凭证及审计在同一事务提交。 */
    public Long insertOperation(Long actorAccountId, String actionCode, String requestId, String fingerprint) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO business_operation (actor_account_id, actor_kind, action_code, college_id, batch_id, " +
                    "request_id, request_fingerprint, result_code, started_at, completed_at) " +
                    "VALUES (?, 'ADMIN', ?, NULL, NULL, ?, ?, 'IN_PROGRESS', UTC_TIMESTAMP(3), NULL)",
                Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, actorAccountId.longValue());
            statement.setString(2, actionCode);
            statement.setString(3, requestId);
            statement.setString(4, fingerprint);
            return statement;
        }, keyHolder);
        return generatedId(keyHolder, "business_operation");
    }

    /** 追加不可覆盖的审计事件；调用方传入的快照不得包含密码、哈希或临时凭证明文。 */
    public void insertAuditEvent(Long operationId, Long actorAccountId, String objectType, Long objectId,
                                 String actionCode, String beforeValues, String afterValues, String reason) {
        jdbcTemplate.update(
            "INSERT INTO audit_event (actor_account_id, actor_kind, actor_role, scope_basis, object_type, object_id, " +
                "action_code, before_values_text, after_values_text, reason, approval_comment, occurred_at, " +
                "business_operation_id) VALUES (?, 'ADMIN', 'ADMIN', 'ADMIN_ACCOUNT_MANAGER', ?, ?, ?, ?, ?, ?, " +
                "NULL, UTC_TIMESTAMP(3), ?)",
            actorAccountId, objectType, objectId, actionCode, beforeValues, afterValues, reason, operationId);
    }

    /** 仅当该事务内所有业务写入与审计成功后完成操作。 */
    public void completeOperation(Long operationId) {
        int changed = jdbcTemplate.update(
            "UPDATE business_operation SET result_code = 'OK', completed_at = UTC_TIMESTAMP(3) WHERE id = ?",
            operationId);
        if (changed != 1) throw new IllegalStateException("Business operation row was not updated");
    }

    private static Long generatedId(KeyHolder keyHolder, String tableName) {
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("Database did not return an ID for " + tableName);
        return Long.valueOf(key.longValue());
    }

}
