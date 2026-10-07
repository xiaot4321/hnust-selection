package cn.hnust.selection.service;

import cn.hnust.selection.entity.AccountEntity;
import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.repository.AccountRepository;
import cn.hnust.selection.repository.EmailRecoveryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class EmailRecoveryService {
    private final AccountRepository accounts;
    private final EmailRecoveryRepository repository;
    private final PasswordEncoder encoder;
    private final EmailDelivery delivery;
    private final SecureRandom random = new SecureRandom();

    public EmailRecoveryService(AccountRepository accounts, EmailRecoveryRepository repository,
                                PasswordEncoder encoder, EmailDelivery delivery) {
        this.accounts = accounts; this.repository = repository; this.encoder = encoder; this.delivery = delivery;
    }

    public Map<String,Object> status(Long accountId) {
        eligible(accounts.findById(accountId).orElseThrow(this::invalid));
        Map<String,Object> result = new HashMap<>();
        result.put("email", repository.email(accountId));
        result.put("configured", delivery.isConfigured());
        return result;
    }

    @Transactional
    public Map<String,Object> bindingCode(Long accountId, String email, String password) {
        requireMail();
        AccountEntity account = accounts.findByIdForUpdate(accountId).orElseThrow(this::invalid);
        eligible(account);
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > 72
            || !encoder.matches(password, account.getPasswordHash())) {
            throw new ApiException("INVALID_CREDENTIALS", "当前密码不正确。", HttpStatus.BAD_REQUEST);
        }
        email = normalize(email);
        if (!repository.available(accountId, email)) throw new ApiException("EMAIL_IN_USE", "该邮箱无法绑定，请换一个邮箱。", HttpStatus.CONFLICT);
        if (!repository.canSend(accountId)) throw new ApiException("RATE_LIMITED", "请等待 60 秒后重试，每小时最多发送 5 次。", HttpStatus.TOO_MANY_REQUESTS);
        return issue(account, "BIND", email);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void confirmBinding(Long accountId, String challengeId, String code) {
        AccountEntity account = accounts.findByIdForUpdate(accountId).orElseThrow(this::invalid);
        eligible(account);
        Map<String,Object> challenge = validate(account, challengeId, code, "BIND");
        // A unique-email collision must roll the whole transaction back; do not use an upsert
        // that could accidentally update another account's row.
        repository.bind(accountId, (String) challenge.get("email"));
        repository.invalidate(accountId);
        repository.audit(accountId, "EMAIL_BOUND");
    }

    @Transactional
    public Map<String,Object> recoveryCode(String loginIdentifier, String email) {
        requireMail();
        email = normalize(email);
        Optional<AccountEntity> found = accounts.findByLoginIdentifier(loginIdentifier.trim());
        if (found.isPresent()) {
            AccountEntity account = accounts.findByIdForUpdate(found.get().getId()).orElseThrow(this::invalid);
            if (isEligible(account) && email.equals(repository.email(account.getId())) && repository.canSend(account.getId())) {
                return issue(account, "RESET", email);
            }
        }
        // Unknown account, incorrect address, inactive account and cooldown all have the
        // same successful response shape. No account/address existence is disclosed.
        return receipt(randomId());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void reset(String loginIdentifier, String email, String challengeId, String code, String password) {
        if (password == null || password.trim().isEmpty() || password.length() > 128
            || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException("INVALID_ARGUMENT", "新密码不能为空，且最多 72 个 UTF-8 字节。", HttpStatus.BAD_REQUEST);
        }
        AccountEntity found = accounts.findByLoginIdentifier(loginIdentifier.trim()).orElseThrow(this::invalid);
        AccountEntity account = accounts.findByIdForUpdate(found.getId()).orElseThrow(this::invalid);
        if (!isEligible(account)) throw invalid();
        Map<String,Object> challenge = validate(account, challengeId, code, "RESET");
        String verifiedEmail = repository.email(account.getId());
        if (!normalize(email).equals(verifiedEmail) || !challenge.get("email").equals(verifiedEmail)) throw invalid();
        accounts.updatePassword(account.getId(), encoder.encode(password));
        repository.revokeTemporary(account.getId());
        repository.invalidate(account.getId());
        repository.audit(account.getId(), "EMAIL_PASSWORD_RESET");
    }

    private Map<String,Object> validate(AccountEntity account, String id, String code, String purpose) {
        Map<String,Object> row = repository.challenge(id);
        if (row == null || ((Number) row.get("account_id")).longValue() != account.getId()
            || ((Number) row.get("account_version")).longValue() != account.getRowVersion()
            || !purpose.equals(row.get("purpose")) || row.get("used_at") != null
            || !truth(row.get("live")) || ((Number) row.get("attempts")).intValue() >= 5) throw invalid();
        if (!encoder.matches(code, (String) row.get("code_hash"))) {
            repository.failedAttempt(id);
            throw invalid(); // noRollbackFor preserves the attempt counter.
        }
        return row;
    }
    private boolean truth(Object value) { return Boolean.TRUE.equals(value) || value instanceof Number && ((Number)value).intValue() != 0; }
    private Map<String,Object> issue(AccountEntity account, String purpose, String email) {
        String id = randomId();
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1000000));
        delivery.sendCode(email, code, "BIND".equals(purpose));
        repository.issue(id, account.getId(), account.getRowVersion(), purpose, email, encoder.encode(code));
        return receipt(id);
    }
    private Map<String,Object> receipt(String id) {
        Map<String,Object> result = new HashMap<>();
        result.put("challengeId", id); result.put("expiresInSeconds", 300); result.put("resendAfterSeconds", 60);
        return result;
    }
    private String randomId() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(String.format(Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
    private String normalize(String value) {
        String email = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (email.length() > 254 || !email.matches("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,}")) {
            throw new ApiException("INVALID_ARGUMENT", "请输入有效的邮箱地址。", HttpStatus.BAD_REQUEST);
        }
        return email;
    }
    private boolean isEligible(AccountEntity account) {
        return "ACTIVE".equals(account.getAccountStatus()) && !account.isMustChangePassword()
            && account.getPasswordHash() != null
            && ("STUDENT".equals(account.getRoleCode()) || "TEACHER".equals(account.getRoleCode()));
    }
    private void eligible(AccountEntity account) {
        if (!isEligible(account)) throw new ApiException("FORBIDDEN", "仅完成首次改密的学生和导师可以绑定邮箱。", HttpStatus.FORBIDDEN);
    }
    private void requireMail() {
        if (!delivery.isConfigured()) throw new ApiException("EMAIL_UNAVAILABLE", "邮件服务尚未配置，请联系管理员。", HttpStatus.SERVICE_UNAVAILABLE);
    }
    private ApiException invalid() { return new ApiException("INVALID_VERIFICATION", "验证码无效、已过期或已使用，请重新获取。", HttpStatus.BAD_REQUEST); }
}
