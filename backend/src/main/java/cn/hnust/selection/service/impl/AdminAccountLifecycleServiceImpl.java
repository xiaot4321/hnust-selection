package cn.hnust.selection.service.impl;

import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.entity.AccountEntity;
import cn.hnust.selection.entity.AdminAccountEntity;
import cn.hnust.selection.entity.AdminAccountOperationEntity;
import cn.hnust.selection.entity.IssuedCredentialEntity;
import cn.hnust.selection.repository.AccountRepository;
import cn.hnust.selection.repository.AdminAccountLifecycleRepository;
import cn.hnust.selection.request.CreateAdminAccountRequest;
import cn.hnust.selection.vo.AdminAccountCredentialVO;
import cn.hnust.selection.vo.AdminAccountDirectoryItemVO;
import cn.hnust.selection.vo.PageVO;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.AccountAuthorizationService;
import cn.hnust.selection.service.AdminAccountLifecycleService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** 管理员账号创建与临时凭证重置的事务实现。 */
@Service
public class AdminAccountLifecycleServiceImpl implements AdminAccountLifecycleService {
    private static final String ADMIN_ACCOUNT_MANAGER = "ADMIN_ACCOUNT_MANAGER";
    private static final String CREATE_ACTION = "ADMIN_ACCOUNT_CREATE";
    private static final String RESET_ACTION = "ADMIN_ACCOUNT_TEMPORARY_CREDENTIAL_RESET";
    private static final String CREDENTIAL_ISSUED_ACTION = "TEMPORARY_CREDENTIAL_ISSUED";
    private static final String CREDENTIAL_DISPLAY_ACTION = "TEMPORARY_CREDENTIAL_DISPLAY_RESERVED";
    private static final int TEMPORARY_CREDENTIAL_BYTES = 32;
    private static final Pattern UUID_V4 = Pattern.compile(
        "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    private final AccountRepository accountRepository;
    private final AdminAccountLifecycleRepository lifecycleRepository;
    private final AccountAuthorizationService accountAuthorizationService;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom;

    @Autowired
    public AdminAccountLifecycleServiceImpl(AccountRepository accountRepository,
                                            AdminAccountLifecycleRepository lifecycleRepository,
                                            AccountAuthorizationService accountAuthorizationService,
                                            PasswordEncoder passwordEncoder, ObjectMapper objectMapper) {
        this(accountRepository, lifecycleRepository, accountAuthorizationService, passwordEncoder,
            objectMapper, new SecureRandom());
    }

    /** 测试可注入随机源；生产构造器固定使用 JVM 安全随机数生成器。 */
    AdminAccountLifecycleServiceImpl(AccountRepository accountRepository,
                                     AdminAccountLifecycleRepository lifecycleRepository,
                                     AccountAuthorizationService accountAuthorizationService,
                                     PasswordEncoder passwordEncoder, ObjectMapper objectMapper,
                                     SecureRandom secureRandom) {
        this.accountRepository = accountRepository;
        this.lifecycleRepository = lifecycleRepository;
        this.accountAuthorizationService = accountAuthorizationService;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
        this.secureRandom = secureRandom;
    }

    /** 总管理员查看当前 ADMIN 账号目录；读路径重新读取操作者状态和授权。 */
    @Override
    @Transactional(readOnly = true)
    public PageVO<AdminAccountDirectoryItemVO> listAccounts(AccountPrincipal actor,
                                                                         int pageNo, int pageSize) {
        if (pageNo < 1) throw invalidArgument("页码必须大于等于 1");
        if (pageSize < 1 || pageSize > 100) throw invalidArgument("每页数量必须为 1 至 100");
        refreshAndRequireManager(actor, false);

        long offset = ((long) pageNo - 1L) * (long) pageSize;
        List<AdminAccountEntity> records = lifecycleRepository.findAdminAccounts(pageSize, offset);
        List<AdminAccountDirectoryItemVO> items = new ArrayList<AdminAccountDirectoryItemVO>();
        for (AdminAccountEntity record : records) {
            items.add(new AdminAccountDirectoryItemVO(record.getAccountId(), record.getLoginIdentifier(),
                record.getAccountStatus(), record.isMustChangePassword(), record.getCreatedAt()));
        }
        return new PageVO<AdminAccountDirectoryItemVO>(
            items, lifecycleRepository.countAdminAccounts(), pageNo, pageSize);
    }

    /** 创建普通管理员；账号、临时凭证、幂等结果和审计一次提交。 */
    @Override
    @Transactional
    public AdminAccountCredentialVO create(AccountPrincipal actor, CreateAdminAccountRequest request,
                                                 String idempotencyKey) {
        if (request == null || request.getLoginIdentifier() == null
            || request.getLoginIdentifier().trim().isEmpty()) {
            throw invalidArgument("登录标识不能为空");
        }
        String loginIdentifier = request.getLoginIdentifier().trim();
        if (loginIdentifier.length() > 128) throw invalidArgument("登录标识长度不能超过 128 个字符");
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        AccountPrincipal currentActor = refreshAndRequireManager(actor, true);
        String requestFingerprint = fingerprint(CREATE_ACTION, loginIdentifier);
        Optional<AdminAccountCredentialVO> replay = replayIfPresent(currentActor.getAccountId(),
            CREATE_ACTION, normalizedKey, requestFingerprint, "CREATED");
        if (replay.isPresent()) return replay.get();

        if (lifecycleRepository.loginIdentifierExists(loginIdentifier)) {
            throw stateConflict("该登录标识已被使用");
        }

        Long operationId = lifecycleRepository.insertOperation(currentActor.getAccountId(), CREATE_ACTION,
            normalizedKey, requestFingerprint);
        try {
            Long accountId = lifecycleRepository.insertAdminAccount(loginIdentifier);
            AdminAccountCredentialVO result = issueCredential(currentActor, operationId, accountId,
                loginIdentifier, "CREATED", null,
                snapshot("roleCode", "ADMIN", "accountStatus", "ACTIVE", "mustChangePassword", true),
                "总管理员创建普通管理员账号");
            lifecycleRepository.completeOperation(operationId);
            return result;
        } catch (DuplicateKeyException exception) {
            // 唯一索引处理两个并发请求同时通过前置查询的竞态；抛出业务冲突让整笔事务回滚。
            throw stateConflict("该登录标识已被使用");
        }
    }

    /** 重置其他普通管理员的临时凭证，并令既有会话因账号版本变化失效。 */
    @Override
    @Transactional
    public AdminAccountCredentialVO resetTemporaryCredential(AccountPrincipal actor, Long targetAccountId,
                                                                   String idempotencyKey) {
        requirePositiveId(targetAccountId);
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        AccountPrincipal currentActor = refreshAndRequireManager(actor, true);
        if (currentActor.getAccountId().equals(targetAccountId)) {
            throw forbidden("总管理员账号的应急恢复须按 TODO-24 流程办理");
        }
        String requestFingerprint = fingerprint(RESET_ACTION, targetAccountId);
        Optional<AdminAccountCredentialVO> replay = replayIfPresent(currentActor.getAccountId(),
            RESET_ACTION, normalizedKey, requestFingerprint, "RESET");
        if (replay.isPresent()) return replay.get();

        AccountEntity target = accountRepository.findByIdForUpdate(targetAccountId)
            .orElseThrow(() -> notFound("未找到普通管理员账号"));
        if (!"ADMIN".equals(target.getRoleCode())) throw notFound("未找到普通管理员账号");
        if (lifecycleRepository.hasReservedAdminManagerCapability(targetAccountId)) {
            throw forbidden("总管理员账号的应急恢复须按 TODO-24 流程办理");
        }

        Long operationId = lifecycleRepository.insertOperation(currentActor.getAccountId(), RESET_ACTION,
            normalizedKey, requestFingerprint);
        Map<String, Object> before = accountSnapshot(target);
        lifecycleRepository.revokeOutstandingTemporaryCredentials(targetAccountId);
        if (lifecycleRepository.requirePasswordChange(targetAccountId) != 1) {
            throw stateConflict("管理员账号状态已变化，请刷新后重试");
        }
        AdminAccountCredentialVO result = issueCredential(currentActor, operationId, targetAccountId,
            target.getLoginIdentifier(), "RESET", serializeSnapshot(before),
            snapshot("roleCode", "ADMIN", "accountStatus", target.getAccountStatus(),
                "mustChangePassword", true, "credentialReset", true),
            "总管理员重置普通管理员临时凭证");
        lifecycleRepository.completeOperation(operationId);
        return result;
    }

    /** 生成并保存凭证哈希；返回的一次性明文只保留在当前请求响应对象中。 */
    private AdminAccountCredentialVO issueCredential(AccountPrincipal actor, Long operationId,
                                                           Long accountId, String loginIdentifier,
                                                           String resultCode, String beforeAccount,
                                                           String afterAccount, String accountReason) {
        String temporaryCredential = generateTemporaryCredential();
        String credentialHash = passwordEncoder.encode(temporaryCredential);
        IssuedCredentialEntity credential = lifecycleRepository.insertShownTemporaryCredential(
            accountId, credentialHash, operationId);

        lifecycleRepository.insertAuditEvent(operationId, actor.getAccountId(), "ACCOUNT", accountId,
            "RESET".equals(resultCode) ? RESET_ACTION : CREATE_ACTION,
            beforeAccount, afterAccount, accountReason);
        lifecycleRepository.insertAuditEvent(operationId, actor.getAccountId(), "TEMPORARY_CREDENTIAL",
            credential.getId(), CREDENTIAL_ISSUED_ACTION, null,
            snapshot("accountId", accountId, "expiresAt", credential.getExpiresAt(),
                "singleUse", true), "随机临时凭证已生成且不设到期时间；仅保存安全哈希");
        lifecycleRepository.insertAuditEvent(operationId, actor.getAccountId(), "TEMPORARY_CREDENTIAL",
            credential.getId(), CREDENTIAL_DISPLAY_ACTION, null,
            snapshot("accountId", accountId, "shownAt", "TRANSACTION_COMMIT", "displayChannel", "ADMIN_WEB"),
            "提交后在本次管理端响应中预留一次性展示；不得通过邮件或短信发送");
        return new AdminAccountCredentialVO(accountId, loginIdentifier, resultCode,
            temporaryCredential, credential.getExpiresAt(), true);
    }

    /** 重读并锁定操作者，避免使用过期 Session 授权或让停用账号继续操作。 */
    private AccountPrincipal refreshAndRequireManager(AccountPrincipal actor, boolean forUpdate) {
        if (actor == null || actor.getAccountId() == null) {
            throw new ApiException("UNAUTHENTICATED", "登录状态已失效", HttpStatus.UNAUTHORIZED);
        }
        Optional<AccountEntity> account = forUpdate
            ? accountRepository.findByIdForUpdate(actor.getAccountId())
            : accountRepository.findById(actor.getAccountId());
        if (!account.isPresent()) throw new ApiException("UNAUTHENTICATED", "登录状态已失效", HttpStatus.UNAUTHORIZED);
        AccountEntity current = account.get();
        if (!"ACTIVE".equalsIgnoreCase(current.getAccountStatus())) {
            throw new ApiException("ACCOUNT_DISABLED", "账号已停用", HttpStatus.FORBIDDEN);
        }
        if (!"ADMIN".equals(current.getRoleCode())) throw forbidden("当前账号不是管理员");
        AccountPrincipal refreshed;
        try {
            refreshed = accountRepository.toPrincipal(current, actor.isTemporaryCredentialLogin());
        } catch (IllegalStateException exception) {
            throw new ApiException("UNAUTHENTICATED", "登录状态已失效", HttpStatus.UNAUTHORIZED);
        }
        accountAuthorizationService.requireCapability(refreshed, ADMIN_ACCOUNT_MANAGER, null, null);
        return refreshed;
    }

    /** 对已有操作只返回非敏感元数据，防止同一幂等键让已展示凭证再次出现。 */
    private Optional<AdminAccountCredentialVO> replayIfPresent(Long actorAccountId, String actionCode,
                                                                      String requestId, String fingerprint,
                                                                      String resultCode) {
        Optional<AdminAccountOperationEntity> found = lifecycleRepository.findOperation(
            actorAccountId, actionCode, requestId);
        if (!found.isPresent()) return Optional.empty();
        AdminAccountOperationEntity existing = found.get();
        if (!fingerprint.equals(existing.getFingerprint())) {
            throw new ApiException("IDEMPOTENCY_KEY_REUSED", "幂等键已用于不同请求", HttpStatus.CONFLICT);
        }
        if (!"OK".equals(existing.getResultCode()) || existing.getAccountId() == null) {
            throw new ApiException("REQUEST_IN_PROGRESS", "相同幂等请求仍在处理", HttpStatus.CONFLICT);
        }
        return Optional.of(new AdminAccountCredentialVO(existing.getAccountId(),
            existing.getLoginIdentifier(), resultCode, null, existing.getExpiresAt(), false));
    }

    private String generateTemporaryCredential() {
        byte[] randomBytes = new byte[TEMPORARY_CREDENTIAL_BYTES];
        secureRandom.nextBytes(randomBytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    private String normalizeIdempotencyKey(String value) {
        if (value == null || !UUID_V4.matcher(value).matches()) {
            throw invalidArgument("Idempotency-Key 必须是 UUID v4");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private String fingerprint(Object... fields) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(fields));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format("%02x", value & 0xff));
            return hex.toString();
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to fingerprint an administrator account command", exception);
        }
    }

    private String snapshot(Object... fields) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        for (int index = 0; index < fields.length; index += 2) {
            values.put(String.valueOf(fields[index]), fields[index + 1]);
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize an administrator account audit snapshot", exception);
        }
    }

    private String serializeSnapshot(Map<String, Object> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize an administrator account audit snapshot", exception);
        }
    }

    private Map<String, Object> accountSnapshot(AccountEntity account) {
        Map<String, Object> before = new LinkedHashMap<String, Object>();
        before.put("loginIdentifier", account.getLoginIdentifier());
        before.put("roleCode", account.getRoleCode());
        before.put("accountStatus", account.getAccountStatus());
        before.put("mustChangePassword", account.isMustChangePassword());
        before.put("rowVersion", account.getRowVersion());
        return before;
    }

    private void requirePositiveId(Long id) {
        if (id == null || id.longValue() <= 0) throw invalidArgument("目标管理员账号 ID 必须为正整数");
    }

    private ApiException invalidArgument(String message) {
        return new ApiException("INVALID_ARGUMENT", message, HttpStatus.BAD_REQUEST);
    }

    private ApiException forbidden(String message) {
        return new ApiException("FORBIDDEN", message, HttpStatus.FORBIDDEN);
    }

    private ApiException notFound(String message) {
        return new ApiException("NOT_FOUND", message, HttpStatus.NOT_FOUND);
    }

    private ApiException stateConflict(String message) {
        return new ApiException("STATE_CONFLICT", message, HttpStatus.CONFLICT);
    }
}
