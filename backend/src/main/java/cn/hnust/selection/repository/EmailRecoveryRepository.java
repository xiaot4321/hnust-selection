package cn.hnust.selection.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Map;

@Repository
public class EmailRecoveryRepository {
    private final JdbcTemplate jdbc;
    public EmailRecoveryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public String email(Long accountId) {
        List<String> rows = jdbc.query("SELECT email FROM account_email WHERE account_id=?", (rs, n) -> rs.getString(1), accountId);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public boolean available(Long accountId, String email) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM account_email WHERE email=? AND account_id<>?", Integer.class, email, accountId) == 0;
    }
    public boolean canSend(Long accountId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM email_verification WHERE account_id=? AND created_at>UTC_TIMESTAMP(3)-INTERVAL 60 SECOND", Integer.class, accountId) == 0
            && jdbc.queryForObject("SELECT COUNT(*) FROM email_verification WHERE account_id=? AND created_at>UTC_TIMESTAMP(3)-INTERVAL 1 HOUR", Integer.class, accountId) < 5;
    }
    public void issue(String id, Long accountId, long version, String purpose, String email, String hash) {
        jdbc.update("UPDATE email_verification SET used_at=UTC_TIMESTAMP(3) WHERE account_id=? AND purpose=? AND used_at IS NULL", accountId, purpose);
        jdbc.update("INSERT INTO email_verification(id,account_id,account_version,purpose,email,code_hash,created_at,expires_at) VALUES (?,?,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)+INTERVAL 5 MINUTE)", id, accountId, version, purpose, email, hash);
    }
    public Map<String,Object> challenge(String id) {
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT *, expires_at>UTC_TIMESTAMP(3) AS live FROM email_verification WHERE id=? FOR UPDATE", id);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public void failedAttempt(String id) {
        jdbc.update("UPDATE email_verification SET attempts=attempts+1 WHERE id=?", id);
    }
    public void bind(Long accountId, String email) {
        if (email(accountId) == null) {
            jdbc.update("INSERT INTO account_email(account_id,email,verified_at) VALUES (?,?,UTC_TIMESTAMP(3))", accountId, email);
        } else {
            jdbc.update("UPDATE account_email SET email=?,verified_at=UTC_TIMESTAMP(3) WHERE account_id=?", email, accountId);
        }
    }
    public void invalidate(Long accountId) {
        jdbc.update("UPDATE email_verification SET used_at=UTC_TIMESTAMP(3) WHERE account_id=? AND used_at IS NULL", accountId);
    }
    public void revokeTemporary(Long accountId) {
        jdbc.update("UPDATE temporary_credential SET revoked_at=UTC_TIMESTAMP(3),row_version=row_version+1 WHERE account_id=? AND used_at IS NULL AND revoked_at IS NULL", accountId);
    }
    public void audit(Long accountId, String action) {
        jdbc.update("INSERT INTO email_security_event(account_id,action_code,created_at) VALUES (?,?,UTC_TIMESTAMP(3))", accountId, action);
    }
}
