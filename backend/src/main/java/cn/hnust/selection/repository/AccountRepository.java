package cn.hnust.selection.repository;

import cn.hnust.selection.enums.AccountRole;
import cn.hnust.selection.entity.AccountEntity;
import cn.hnust.selection.entity.TemporaryCredentialEntity;
import cn.hnust.selection.security.AccountAuthorization;
import cn.hnust.selection.security.AccountIdentity;
import cn.hnust.selection.security.AccountPrincipal;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 账号认证模块使用的 JDBC 访问层。
 *
 * <p>本类负责把 SQL 结果映射成认证层的数据对象，并执行凭证、账号版本和授权相关的读写；
 * 登录规则、错误码选择、事务编排和“某项业务是否允许执行”的判断属于 Service 或 Security 层。</p>
 *
 * <p>查询只选择认证主体和本人概要必需的字段，避免把联系方式、业务简介、简历等资料带入 Session。
 * 表结构使用项目已经确认的 account、student、teacher、account_authorization 和
 * temporary_credential 表。</p>
 */
@Repository
public class AccountRepository {
    private final JdbcTemplate jdbcTemplate;

    public AccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // 多个账号查询共用同一映射，避免不同 SQL 对账号状态、版本号等字段产生不一致解释。
    private final RowMapper<AccountEntity> accountRowMapper = new RowMapper<AccountEntity>() {
        @Override
        public AccountEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new AccountEntity(rs.getLong("id"), rs.getString("login_identifier"),
                rs.getString("role_code"), rs.getString("account_status"),
                rs.getString("password_hash"), rs.getBoolean("must_change_password"),
                rs.getTimestamp("credential_changed_at"), rs.getLong("row_version"));
        }
    };

    /** 按唯一登录标识查账号；唯一性由数据库约束保证，未命中时以空 Optional 表示。 */
    public Optional<AccountEntity> findByLoginIdentifier(String loginIdentifier) {
        // 唯一键由 DDL 保证；不存在时返回 Optional.empty，认证层统一转成凭证无效错误。
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                "SELECT id, login_identifier, role_code, account_status, password_hash, " +
                    "must_change_password, credential_changed_at, row_version FROM account WHERE login_identifier = ?",
                accountRowMapper, loginIdentifier));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /** 按内部账号主键读取认证字段；账号不存在时返回空，调用方决定对应业务错误。 */
    public Optional<AccountEntity> findById(Long accountId) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                "SELECT id, login_identifier, role_code, account_status, password_hash, " +
                    "must_change_password, credential_changed_at, row_version FROM account WHERE id = ?",
                accountRowMapper, accountId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /**
     * 读取并锁定账号记录，必须在数据库事务中调用。
     *
     * <p>{@code FOR UPDATE} 会在当前事务结束前锁住命中的 InnoDB 行，用于串行化同一账号的改密流程，
     * 避免并发请求基于旧密码/旧临时凭证状态同时写入。此方法本身不创建事务。</p>
     */
    public Optional<AccountEntity> findByIdForUpdate(Long accountId) {
        try {
            // 仅供事务内改密使用。InnoDB 行锁让同一账号的改密/临时凭证消费串行化。
            return Optional.of(jdbcTemplate.queryForObject(
                "SELECT id, login_identifier, role_code, account_status, password_hash, " +
                    "must_change_password, credential_changed_at, row_version FROM account WHERE id = ? FOR UPDATE",
                accountRowMapper, accountId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /**
     * 将账号记录与其角色对应的身份、管理员有效授权组合成 Spring Security 主体。
     *
     * <p>学生和导师必须有对应人员记录；管理员不从 student/teacher 表读取身份。角色代码无法识别，
     * 或学生/导师账号缺少关联人员行时抛出 IllegalStateException，由认证层转成安全的失败响应。</p>
     *
     * @param account 已读取的账号认证字段
     * @param temporaryCredentialLogin 当前会话是否通过临时凭证建立
     */
    public AccountPrincipal toPrincipal(AccountEntity account, boolean temporaryCredentialLogin) {
        AccountRole role;
        try {
            role = AccountRole.valueOf(account.getRoleCode());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new IllegalStateException("Account has an unsupported role code");
        }
        // 学生/导师必须有对应人员行；管理员不关联这两张人员表。
        AccountIdentity identity = loadIdentity(account.getId(), role);
        if ((role == AccountRole.STUDENT || role == AccountRole.TEACHER) && identity == null) {
            throw new IllegalStateException("Account role is missing its linked identity");
        }
        List<AccountAuthorization> authorizations = role == AccountRole.ADMIN
            ? loadAuthorizations(account.getId()) : Collections.<AccountAuthorization>emptyList();
        return new AccountPrincipal(account.getId(), account.getLoginIdentifier(), role,
            account.getAccountStatus(), account.isMustChangePassword(), temporaryCredentialLogin,
            account.getCredentialChangedAt(), account.getRowVersion(), identity, authorizations);
    }

    private AccountIdentity loadIdentity(Long accountId, AccountRole role) {
        if (role == AccountRole.STUDENT) {
            // 学生身份仅取本人概要字段：student 主键、姓名、学号和学院。
            // 联系方式、简介、简历等业务资料由对应业务接口按需读取，不进入认证主体或 Session。
            List<AccountIdentity> rows = jdbcTemplate.query(
                "SELECT id, full_name, student_no, college_id FROM student WHERE account_id = ?",
                new RowMapper<AccountIdentity>() {
                    @Override public AccountIdentity mapRow(ResultSet rs, int rowNum) throws SQLException {
                        return new AccountIdentity(rs.getLong("id"), rs.getString("full_name"),
                            rs.getString("student_no"), rs.getLong("college_id"));
                    }
                }, accountId);
            return rows.isEmpty() ? null : rows.get(0);
        }
        if (role == AccountRole.TEACHER) {
            // 导师身份与学生使用同一概要结构，但业务标识取 employee_no（工号）。
            List<AccountIdentity> rows = jdbcTemplate.query(
                "SELECT id, full_name, employee_no, college_id FROM teacher WHERE account_id = ?",
                new RowMapper<AccountIdentity>() {
                    @Override public AccountIdentity mapRow(ResultSet rs, int rowNum) throws SQLException {
                        return new AccountIdentity(rs.getLong("id"), rs.getString("full_name"),
                            rs.getString("employee_no"), rs.getLong("college_id"));
                    }
                }, accountId);
            return rows.isEmpty() ? null : rows.get(0);
        }
        return null;
    }

    private List<AccountAuthorization> loadAuthorizations(Long accountId) {
        // 撤销授权记录保留在数据库用于审计；主体只携带 revoked_at 为空的有效授权。
        // 排序使同一账号构造出的 authority 列表稳定，便于诊断、序列化和测试比较。
        return jdbcTemplate.query(
            "SELECT college_id, batch_id, capability_code FROM account_authorization " +
                "WHERE account_id = ? AND revoked_at IS NULL ORDER BY capability_code, college_id, batch_id",
            new RowMapper<AccountAuthorization>() {
                @Override public AccountAuthorization mapRow(ResultSet rs, int rowNum) throws SQLException {
                    long rawBatchId = rs.getLong("batch_id");
                    Long batchId = rs.wasNull() ? null : rawBatchId;
                    return new AccountAuthorization(rs.getLong("college_id"), batchId,
                        rs.getString("capability_code"));
                }
            }, accountId);
    }

    public List<TemporaryCredentialEntity> findTemporaryCredentials(Long accountId, boolean forUpdate) {
        // 改密时需保留 used_at / revoked_at / 到期状态，才能区分错误、已消费和已失效凭证。
        // 只读取最近 20 条签发记录以限制哈希比对成本；forUpdate=true 会锁定这些凭证行，
        // 应与账号行锁在同一事务中使用，不能在事务外借此声称具备并发保护。
        String sql = "SELECT id, credential_hash, used_at, revoked_at, " +
            "(expires_at IS NULL OR expires_at > UTC_TIMESTAMP(3)) AS not_expired FROM temporary_credential " +
            "WHERE account_id = ? ORDER BY issued_at DESC, id DESC LIMIT 20" +
            (forUpdate ? " FOR UPDATE" : "");
        return jdbcTemplate.query(sql, new RowMapper<TemporaryCredentialEntity>() {
            @Override public TemporaryCredentialEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
                return new TemporaryCredentialEntity(rs.getLong("id"), rs.getString("credential_hash"),
                    rs.getTimestamp("used_at") != null, rs.getTimestamp("revoked_at") != null,
                    rs.getBoolean("not_expired"));
            }
        }, accountId);
    }

    public List<TemporaryCredentialEntity> findValidTemporaryCredentials(Long accountId) {
        // 登录只需要验证当前可用的临时凭证，因此由 SQL 排除已用、已撤销或仍保留历史到期时间且已过期的记录，
        // 减少对无效 BCrypt 哈希的昂贵比较。expires_at 为 NULL 表示不设到期时间。
        return jdbcTemplate.query(
            "SELECT id, credential_hash, used_at, revoked_at, TRUE AS not_expired " +
                "FROM temporary_credential WHERE account_id = ? AND used_at IS NULL " +
                "AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > UTC_TIMESTAMP(3)) " +
                "ORDER BY issued_at DESC, id DESC LIMIT 20",
            new RowMapper<TemporaryCredentialEntity>() {
                @Override public TemporaryCredentialEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
                    return new TemporaryCredentialEntity(rs.getLong("id"), rs.getString("credential_hash"),
                        false, false, true);
                }
            }, accountId);
    }

    public boolean consumeTemporaryCredential(Long credentialId) {
        // 用一条条件 UPDATE 原子完成“仍可用 -> 已消费”状态变化，而不是先查再写。
        // 返回 true 代表恰好更新一行；返回 false 表示凭证已被并发请求消费、撤销或仍处于历史到期状态。
        return jdbcTemplate.update(
            "UPDATE temporary_credential SET used_at = UTC_TIMESTAMP(3) " +
                "WHERE id = ? AND used_at IS NULL AND revoked_at IS NULL " +
                "AND (expires_at IS NULL OR expires_at > UTC_TIMESTAMP(3))",
            credentialId) == 1;
    }

    public void updatePassword(Long accountId, String passwordHash) {
        // passwordHash 必须是 PasswordEncoder 生成的哈希，调用方不能传入明文。
        // 每次更新都清除首次改密标记、记录数据库 UTC 时间并递增 row_version；
        // 其他 Session 会在下一请求被 AccountRefreshFilter 发现版本不一致后失效。
        jdbcTemplate.update(
            "UPDATE account SET password_hash = ?, must_change_password = FALSE, " +
                "credential_changed_at = UTC_TIMESTAMP(3), row_version = row_version + 1 WHERE id = ?",
            passwordHash, accountId);
    }

    public void recordSuccessfulLogin(Long accountId) {
        // 登录成功后写数据库 UTC 时间供账号审计/概要展示。
        // 不递增 row_version，因为一次正常登录不应让同账号已有的其他 Session 失效。
        jdbcTemplate.update("UPDATE account SET last_login_at = UTC_TIMESTAMP(3) WHERE id = ?", accountId);
    }

}
