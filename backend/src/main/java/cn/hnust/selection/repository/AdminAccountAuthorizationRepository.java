package cn.hnust.selection.repository;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;
import java.util.Optional;

/**
 * 管理员账号授权管理的数据访问层。
 *
 * <p>本类只执行 SQL、行锁和结果映射；总管理员资格、能力目录、授权范围、幂等摘要和
 * 审计语义由 {@code AdminAccountAuthorizationServiceImpl} 负责。所有带 {@code FOR UPDATE}
 * 的方法都要求调用方已经开启事务，不能单独用作并发保护。</p>
 *
 * <p>授权历史保存在既有 {@code account_authorization} 表，幂等请求及操作结果保存在
 * {@code business_operation}，不可覆盖的变更证据写入 {@code audit_event}。本模块不创建
 * 新表，也不删除已撤销的授权记录。</p>
 */
@Repository
public class AdminAccountAuthorizationRepository {
    /** 所有列表/明细查询共用的授权列投影；撤销理由来自对应的不可覆盖审计事件。 */
    private static final String AUTHORIZATION_SELECT =
        "SELECT authorization.id, authorization.account_id, authorization.college_id, authorization.batch_id, " +
            "authorization.capability_code, authorization.authority_slot, authorization.basis, " +
            "authorization.granted_by, authorization.granted_at, authorization.revoked_by, authorization.revoked_at, " +
            "(SELECT audit_record.reason FROM audit_event audit_record WHERE audit_record.object_type = 'ACCOUNT_AUTHORIZATION' " +
            "AND audit_record.object_id = authorization.id AND audit_record.action_code = 'ADMIN_AUTHORIZATION_REVOKE' " +
            "ORDER BY audit_record.id DESC LIMIT 1) AS revocation_reason " +
            "FROM account_authorization authorization ";
    private final JdbcTemplate jdbcTemplate;

    public AdminAccountAuthorizationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 查询指定学院中的授权记录，并按状态筛选；ALL 同时返回有效记录和撤销历史。 */
    public List<AuthorizationRecord> findAuthorizations(Long accountId, Long collegeId, String status) {
        StringBuilder sql = new StringBuilder(AUTHORIZATION_SELECT)
            .append("WHERE authorization.account_id = ? AND authorization.college_id = ? ");
        if ("ACTIVE".equals(status)) {
            sql.append("AND authorization.revoked_at IS NULL ");
        } else if ("REVOKED".equals(status)) {
            sql.append("AND authorization.revoked_at IS NOT NULL ");
        }
        sql.append("ORDER BY authorization.capability_code, authorization.batch_id, " +
            "authorization.granted_at, authorization.id");
        return jdbcTemplate.query(sql.toString(), authorizationRowMapper, accountId, collegeId);
    }

    /**
     * 在当前事务中锁定授权记录，并同时限定目标账号主键。
     *
     * <p>把目标账号 ID 放进 WHERE 子句，可避免调用者把另一个管理员的授权 ID 拼到路径中，
     * 从而误撤销不属于该账号的记录。</p>
     */
    public Optional<AuthorizationRecord> findAuthorizationForUpdate(Long accountId, Long authorizationId) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                AUTHORIZATION_SELECT + "WHERE authorization.account_id = ? AND authorization.id = ? FOR UPDATE",
                authorizationRowMapper, accountId, authorizationId));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    /** 检查学院主键是否存在；调用方在检查前先验证自己的该学院授权，避免越权枚举。 */
    public boolean collegeExists(Long collegeId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM college WHERE id = ?", Integer.class, collegeId);
        return count != null && count.intValue() > 0;
    }

    /**
     * 返回批次所属学院；批次不存在时为空。
     * Service 会要求它与请求授权的 collegeId 完全一致，不能靠客户端拼接跨学院范围。
     */
    public Optional<Long> findBatchCollegeId(Long batchId) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                "SELECT college_id FROM selection_batch WHERE id = ?", Long.class, batchId));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    /**
     * 在已锁定目标管理员账号行后检查是否已有相同的有效能力/范围组合。
     *
     * <p>{@code <=>} 是 MySQL 的 NULL 安全相等运算符，用于正确比较两个学院级授权的 NULL
     * batch_id。账号行锁让并发管理命令按同一目标账号串行检查，避免事务外先查再写。</p>
     */
    public boolean hasActiveAuthorization(Long accountId, String capabilityCode,
                                          Long collegeId, Long batchId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM account_authorization " +
                "WHERE account_id = ? AND capability_code = ? AND college_id = ? " +
                "AND batch_id <=> ? AND revoked_at IS NULL",
            Integer.class, accountId, capabilityCode, collegeId, batchId);
        return count != null && count.intValue() > 0;
    }

    /**
     * 新增一条授权历史并返回数据库生成的授权 ID。
     *
     * <p>{@code authority_slot} 仅用于总管理员能力的唯一槽位；普通业务授权必须写 NULL，
     * 因此不会占用或覆盖总管理员的保留槽位。</p>
     */
    public Long insertAuthorization(Long accountId, Long collegeId, Long batchId,
                                    String capabilityCode, String basis, Long grantedBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO account_authorization " +
                    "(account_id, college_id, batch_id, capability_code, authority_slot, basis, " +
                    "granted_by, granted_at, revoked_by, revoked_at) " +
                    "VALUES (?, ?, ?, ?, NULL, ?, ?, UTC_TIMESTAMP(3), NULL, NULL)",
                Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, accountId);
            statement.setLong(2, collegeId);
            setNullableLong(statement, 3, batchId);
            statement.setString(4, capabilityCode);
            statement.setString(5, basis);
            statement.setLong(6, grantedBy);
            return statement;
        }, keyHolder);
        return generatedId(keyHolder, "account_authorization");
    }

    /** 查询指定幂等命令；审计对象 ID 用来重放稳定的命令回执。 */
    public Optional<ExistingOperation> findOperation(Long actorAccountId, String actionCode,
                                                     String requestId) {
        String sql = "SELECT operation.request_fingerprint, operation.result_code, audit.object_id " +
            "FROM business_operation operation LEFT JOIN audit_event audit " +
            "ON audit.business_operation_id = operation.id AND audit.action_code = operation.action_code " +
            "WHERE operation.actor_account_id = ? AND operation.action_code = ? " +
            "AND operation.request_id = ? ORDER BY audit.id LIMIT 1";
        List<ExistingOperation> operations = jdbcTemplate.query(sql, new RowMapper<ExistingOperation>() {
            @Override
            public ExistingOperation mapRow(ResultSet resultSet, int rowNum) throws SQLException {
                long rawObjectId = resultSet.getLong("object_id");
                Long authorizationId = resultSet.wasNull() ? null : rawObjectId;
                return new ExistingOperation(resultSet.getString("request_fingerprint"),
                    resultSet.getString("result_code"), authorizationId);
            }
        }, actorAccountId, actionCode, requestId);
        return operations.isEmpty() ? Optional.<ExistingOperation>empty()
            : Optional.of(operations.get(0));
    }

    /** 在业务修改事务内创建幂等操作记录；授权写入或审计失败时该行也会一起回滚。 */
    public Long insertOperation(Long actorAccountId, String actionCode, Long collegeId,
                                Long batchId, String requestId, String fingerprint) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO business_operation " +
                    "(actor_account_id, actor_kind, action_code, college_id, batch_id, request_id, " +
                    "request_fingerprint, result_code, started_at, completed_at) " +
                    "VALUES (?, 'ADMIN', ?, ?, ?, ?, ?, 'IN_PROGRESS', UTC_TIMESTAMP(3), NULL)",
                Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, actorAccountId);
            statement.setString(2, actionCode);
            setNullableLong(statement, 3, collegeId);
            setNullableLong(statement, 4, batchId);
            statement.setString(5, requestId);
            statement.setString(6, fingerprint);
            return statement;
        }, keyHolder);
        return generatedId(keyHolder, "business_operation");
    }

    /** 把原子授权命令标记为完成；与授权行和审计事件处在同一个事务。 */
    public void completeOperation(Long operationId) {
        int changed = jdbcTemplate.update(
            "UPDATE business_operation SET result_code = 'OK', completed_at = UTC_TIMESTAMP(3) WHERE id = ?",
            operationId);
        if (changed != 1) throw new IllegalStateException("Business operation row was not updated");
    }

    /**
     * 追加一条不可覆盖的授权审计事件。
     *
     * @param operationId 关联的业务操作 ID
     * @param actorAccountId 当前操作者
     * @param actionCode 授予或撤销动作编码
     * @param authorizationId 被操作授权记录 ID
     * @param scopeBasis 可读的学院/批次范围摘要
     * @param beforeValues 撤销前快照；授予时为空
     * @param afterValues 授予后或撤销后快照
     * @param reason 撤销原因；授予操作使用授权记录中的 basis
     */
    public void insertAuditEvent(Long operationId, Long actorAccountId, String actionCode,
                                 Long authorizationId, String scopeBasis, String beforeValues,
                                 String afterValues, String reason) {
        jdbcTemplate.update(
            "INSERT INTO audit_event " +
                "(actor_account_id, actor_kind, actor_role, scope_basis, object_type, object_id, " +
                "action_code, before_values_text, after_values_text, reason, approval_comment, " +
                "occurred_at, business_operation_id) " +
                "VALUES (?, 'ADMIN', 'ADMIN', ?, 'ACCOUNT_AUTHORIZATION', ?, ?, ?, ?, ?, NULL, " +
                "UTC_TIMESTAMP(3), ?)",
            actorAccountId, scopeBasis, authorizationId, actionCode,
            beforeValues, afterValues, reason, operationId);
    }

    /** 只在已锁定授权行后执行条件撤销，避免历史记录被重复或物理删除。 */
    public boolean revokeAuthorization(Long authorizationId, Long revokedBy) {
        return jdbcTemplate.update(
            "UPDATE account_authorization SET revoked_by = ?, revoked_at = UTC_TIMESTAMP(3), " +
                "row_version = row_version + 1 WHERE id = ? AND revoked_at IS NULL",
            revokedBy, authorizationId) == 1;
    }

    /** 授予成功后读取本事务刚创建的完整记录，作为 API 响应及审计快照来源。 */
    public Optional<AuthorizationRecord> findAuthorizationById(Long authorizationId) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                AUTHORIZATION_SELECT + "WHERE authorization.id = ?",
                authorizationRowMapper, authorizationId));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    private final RowMapper<AuthorizationRecord> authorizationRowMapper = new RowMapper<AuthorizationRecord>() {
        @Override
        public AuthorizationRecord mapRow(ResultSet resultSet, int rowNum) throws SQLException {
            long rawBatchId = resultSet.getLong("batch_id");
            Long batchId = resultSet.wasNull() ? null : rawBatchId;
            long rawRevokedBy = resultSet.getLong("revoked_by");
            Long revokedBy = resultSet.wasNull() ? null : rawRevokedBy;
            return new AuthorizationRecord(resultSet.getLong("id"), resultSet.getLong("account_id"),
                resultSet.getLong("college_id"), batchId, resultSet.getString("capability_code"),
                resultSet.getString("authority_slot"), resultSet.getString("basis"),
                resultSet.getLong("granted_by"), resultSet.getTimestamp("granted_at"),
                revokedBy, resultSet.getTimestamp("revoked_at"), resultSet.getString("revocation_reason"));
        }
    };

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) statement.setNull(index, Types.BIGINT);
        else statement.setLong(index, value.longValue());
    }

    private static Long generatedId(KeyHolder keyHolder, String tableName) {
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("Database did not return an ID for " + tableName);
        return Long.valueOf(key.longValue());
    }

    /** 只读映射出的授权历史数据；status 通过撤销时间推导，不重复存成另一份状态。 */
    public static class AuthorizationRecord {
        private final Long id;
        private final Long accountId;
        private final Long collegeId;
        private final Long batchId;
        private final String capabilityCode;
        private final String authoritySlot;
        private final String basis;
        private final Long grantedBy;
        private final Timestamp grantedAt;
        private final Long revokedBy;
        private final Timestamp revokedAt;
        private final String revocationReason;

        public AuthorizationRecord(Long id, Long accountId, Long collegeId, Long batchId,
                                   String capabilityCode, String authoritySlot, String basis,
                                   Long grantedBy, Timestamp grantedAt, Long revokedBy, Timestamp revokedAt,
                                   String revocationReason) {
            this.id = id;
            this.accountId = accountId;
            this.collegeId = collegeId;
            this.batchId = batchId;
            this.capabilityCode = capabilityCode;
            this.authoritySlot = authoritySlot;
            this.basis = basis;
            this.grantedBy = grantedBy;
            this.grantedAt = grantedAt;
            this.revokedBy = revokedBy;
            this.revokedAt = revokedAt;
            this.revocationReason = revocationReason;
        }

        public Long getId() { return id; }
        public Long getAccountId() { return accountId; }
        public Long getCollegeId() { return collegeId; }
        public Long getBatchId() { return batchId; }
        public String getCapabilityCode() { return capabilityCode; }
        public String getAuthoritySlot() { return authoritySlot; }
        public String getBasis() { return basis; }
        public Long getGrantedBy() { return grantedBy; }
        public Timestamp getGrantedAt() { return grantedAt == null ? null : new Timestamp(grantedAt.getTime()); }
        public Long getRevokedBy() { return revokedBy; }
        public Timestamp getRevokedAt() { return revokedAt == null ? null : new Timestamp(revokedAt.getTime()); }
        public String getRevocationReason() { return revocationReason; }
        public boolean isRevoked() { return revokedAt != null; }
    }

    /** 已提交的幂等操作摘要及其对应授权 ID。 */
    public static class ExistingOperation {
        private final String fingerprint;
        private final String resultCode;
        private final Long authorizationId;

        public ExistingOperation(String fingerprint, String resultCode, Long authorizationId) {
            this.fingerprint = fingerprint;
            this.resultCode = resultCode;
            this.authorizationId = authorizationId;
        }

        public String getFingerprint() { return fingerprint; }
        public String getResultCode() { return resultCode; }
        public Long getAuthorizationId() { return authorizationId; }
    }
}
