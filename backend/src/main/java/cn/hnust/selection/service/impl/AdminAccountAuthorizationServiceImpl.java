package cn.hnust.selection.service.impl;

import cn.hnust.selection.entity.AccountEntity;
import cn.hnust.selection.entity.AdminAuthorizationEntity;
import cn.hnust.selection.entity.AdminAuthorizationOperationEntity;
import cn.hnust.selection.enums.AccountRole;
import cn.hnust.selection.enums.AdminAuthorizationStatusFilter;
import cn.hnust.selection.enums.AdminCapabilityCode;
import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.repository.AccountRepository;
import cn.hnust.selection.repository.AdminAccountAuthorizationRepository;
import cn.hnust.selection.request.GrantAdminAuthorizationRequest;
import cn.hnust.selection.request.RevokeAdminAuthorizationRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.AccountAuthorizationService;
import cn.hnust.selection.service.AdminAccountAuthorizationService;
import cn.hnust.selection.vo.AdminAccountAuthorizationVO;
import cn.hnust.selection.vo.AdminAuthorizationCommandVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 管理员账号业务能力授权用例的事务实现。
 *
 * <p>本类刻意与 Controller 分开：它从数据库刷新操作者权限、验证总管理员能力和目标学院，
 * 锁定操作者和目标账号，校验能力目录与批次归属，并在同一 InnoDB 事务里保存授权、
 * 幂等操作和审计事件。失败时整个事务回滚，避免出现“授权写入但无审计”或反向的不一致。</p>
 *
 * <p>项目能力目录由 {@link AdminCapabilityCode} 明确登记。保留枚举白名单意味着请求不能随意把
 * 一个拼写出来的字符串变成新的权限。管理员账号管理能力 {@code ADMIN_ACCOUNT_MANAGER}
 * 不允许由这组常规命令变更。</p>
 */
@Service
public class AdminAccountAuthorizationServiceImpl implements AdminAccountAuthorizationService {
    private static final String ADMIN_ACCOUNT_MANAGER = "ADMIN_ACCOUNT_MANAGER";
    private static final String GRANT_ACTION = "ADMIN_AUTHORIZATION_GRANT";
    private static final String REVOKE_ACTION = "ADMIN_AUTHORIZATION_REVOKE";
    private static final Pattern UUID_V4 = Pattern.compile(
        "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    private final AccountRepository accountRepository;
    private final AdminAccountAuthorizationRepository authorizationRepository;
    private final AccountAuthorizationService accountAuthorizationService;
    private final ObjectMapper objectMapper;

    public AdminAccountAuthorizationServiceImpl(AccountRepository accountRepository,
                                                AdminAccountAuthorizationRepository authorizationRepository,
                                                AccountAuthorizationService accountAuthorizationService,
                                                ObjectMapper objectMapper) {
        this.accountRepository = accountRepository;
        this.authorizationRepository = authorizationRepository;
        this.accountAuthorizationService = accountAuthorizationService;
        this.objectMapper = objectMapper;
    }

    /**
     * 读取目标管理员指定学院下的授权记录。
     *
     * <p>先校验调用者对该学院拥有总管理员管理能力，再判断目标账号是否为 ADMIN，
     * 这样无权查询学院的调用者不会借错误消息枚举目标账号是否存在。</p>
     */
    @Override
    @Transactional(readOnly = true)
    public List<AdminAccountAuthorizationVO> listAuthorizations(
        AccountPrincipal actor, Long targetAccountId, Long collegeId, AdminAuthorizationStatusFilter status) {
        requirePositiveId(targetAccountId, "目标账号 ID");
        requirePositiveId(collegeId, "学院 ID");
        if (status == null) status = AdminAuthorizationStatusFilter.ACTIVE;

        AccountPrincipal currentActor = refreshAndValidateActor(actor, false);
        requireAccountManagerScope(currentActor, collegeId);
        requireCollegeExists(collegeId);
        requireAdminTarget(targetAccountId, false, currentActor.getAccountId());

        List<AdminAuthorizationEntity> records = authorizationRepository.findAuthorizations(
            targetAccountId, collegeId, status.name());
        List<AdminAccountAuthorizationVO> responses = new ArrayList<AdminAccountAuthorizationVO>();
        for (AdminAuthorizationEntity record : records) {
            responses.add(toResponse(record));
        }
        return responses;
    }

    /**
     * 原子授予一项普通业务能力，并将请求结果、操作记录和审计一并提交。
     *
     * <p>同一操作者的授权命令先锁操作者账号行，串行化相同幂等键检查；之后锁定目标账号行，
     * 串行化有效授权去重检查。重复的同键同请求返回原授权 ID，不会创建第二条授权记录。</p>
     */
    @Override
    @Transactional
    public AdminAuthorizationCommandVO grant(AccountPrincipal actor, Long targetAccountId,
                                                   GrantAdminAuthorizationRequest request,
                                                   String idempotencyKey) {
        if (request == null) throw invalidArgument("缺少授权请求内容");
        requirePositiveId(targetAccountId, "目标账号 ID");
        requirePositiveId(request.getCollegeId(), "学院 ID");
        if (request.getBatchId() != null) requirePositiveId(request.getBatchId(), "批次 ID");
        if (!StringUtils.hasText(request.getBasis())) throw invalidArgument("授权依据不能为空");
        String basis = request.getBasis().trim();
        idempotencyKey = normalizeUuidV4(idempotencyKey);

        String capabilityCode = validateGrantableCapability(request.getCapabilityCode());
        if ("COLLEGE_ADMIN".equals(capabilityCode) && request.getBatchId() != null) {
            // 学院人员与资格管理是学院级范围；批次授权无法安全覆盖人员档案与年度资格数据。
            throw invalidArgument("COLLEGE_ADMIN 必须授予整个学院范围，不能限定到单个批次");
        }
        AccountPrincipal currentActor = refreshAndValidateActor(actor, true);
        if (targetAccountId.equals(currentActor.getAccountId())) {
            throw forbidden("常规授权接口只能管理其他管理员账号");
        }
        requireAccountManagerScope(currentActor, request.getCollegeId());
        requireCollegeExists(request.getCollegeId());
        validateBatchCollege(request.getBatchId(), request.getCollegeId());

        String fingerprint = fingerprint(GRANT_ACTION, targetAccountId, capabilityCode,
            request.getCollegeId(), request.getBatchId(), basis);
        Optional<AdminAuthorizationCommandVO> replay = replayIfPresent(
            currentActor.getAccountId(), GRANT_ACTION, idempotencyKey, fingerprint, "GRANTED");
        if (replay.isPresent()) return replay.get();

        // 目标账号行锁让并发的能力授予与撤销对同一账号串行执行，下面的重复授权检查不在事务外。
        requireAdminTarget(targetAccountId, true, currentActor.getAccountId());
        if (authorizationRepository.hasActiveAuthorization(targetAccountId, capabilityCode,
            request.getCollegeId(), request.getBatchId())) {
            throw stateConflict("该管理员已拥有相同能力和范围的有效授权");
        }

        Long operationId = authorizationRepository.insertOperation(currentActor.getAccountId(), GRANT_ACTION,
            request.getCollegeId(), request.getBatchId(), idempotencyKey, fingerprint);
        Long authorizationId = authorizationRepository.insertAuthorization(targetAccountId,
            request.getCollegeId(), request.getBatchId(), capabilityCode, basis,
            currentActor.getAccountId());
        AdminAuthorizationEntity granted = authorizationRepository.findAuthorizationById(authorizationId)
            .orElseThrow(() -> new IllegalStateException("New authorization row was not found"));

        authorizationRepository.insertAuditEvent(operationId, currentActor.getAccountId(), GRANT_ACTION,
            authorizationId, scopeBasis(granted.getCollegeId(), granted.getBatchId()), null,
            auditSnapshot(granted), null);
        authorizationRepository.completeOperation(operationId);
        return new AdminAuthorizationCommandVO(authorizationId, "GRANTED");
    }

    /**
     * 原子撤销目标管理员的一项普通业务能力，保留原授权行并追加撤销字段。
     *
     * <p>服务端根据授权 ID 读取并锁定记录，核对路径账号、记录状态和总管理员作用范围；
     * 请求无法指定操作者、学院、能力代码或撤销时间。</p>
     */
    @Override
    @Transactional
    public AdminAuthorizationCommandVO revoke(AccountPrincipal actor, Long targetAccountId,
                                                    Long authorizationId,
                                                    RevokeAdminAuthorizationRequest request,
                                                    String idempotencyKey) {
        if (request == null || !StringUtils.hasText(request.getReason())) {
            throw invalidArgument("撤销原因不能为空");
        }
        String reason = request.getReason().trim();
        requirePositiveId(targetAccountId, "目标账号 ID");
        requirePositiveId(authorizationId, "授权记录 ID");
        idempotencyKey = normalizeUuidV4(idempotencyKey);

        AccountPrincipal currentActor = refreshAndValidateActor(actor, true);
        if (targetAccountId.equals(currentActor.getAccountId())) {
            throw forbidden("常规授权接口只能管理其他管理员账号");
        }
        String fingerprint = fingerprint(REVOKE_ACTION, targetAccountId, authorizationId, reason);
        Optional<AdminAuthorizationCommandVO> replay = replayIfPresent(
            currentActor.getAccountId(), REVOKE_ACTION, idempotencyKey, fingerprint, "REVOKED");
        if (replay.isPresent()) return replay.get();

        // 先锁目标账号，再锁它所属的授权行；撤销授权不会删除记录，因此之后仍可审计。
        requireAdminTarget(targetAccountId, true, currentActor.getAccountId());
        AdminAuthorizationEntity before = authorizationRepository.findAuthorizationForUpdate(
            targetAccountId, authorizationId)
            .orElseThrow(() -> notFound("未找到该管理员的授权记录"));
        requireAccountManagerScope(currentActor, before.getCollegeId());
        if (ADMIN_ACCOUNT_MANAGER.equals(before.getCapabilityCode())) {
            throw forbidden("管理员账户管理能力只能通过初始化或应急恢复流程调整");
        }
        if (before.isRevoked()) throw stateConflict("该授权已经撤销");

        Long operationId = authorizationRepository.insertOperation(currentActor.getAccountId(), REVOKE_ACTION,
            before.getCollegeId(), before.getBatchId(), idempotencyKey, fingerprint);
        if (!authorizationRepository.revokeAuthorization(authorizationId, currentActor.getAccountId())) {
            // 条件 UPDATE 是行状态的最后保护；影响行数为 0 时回滚操作记录和任何后续审计写入。
            throw stateConflict("该授权状态已变化，请重新读取后再操作");
        }
        AdminAuthorizationEntity after = authorizationRepository.findAuthorizationById(authorizationId)
            .orElseThrow(() -> new IllegalStateException("Revoked authorization row was not found"));
        authorizationRepository.insertAuditEvent(operationId, currentActor.getAccountId(), REVOKE_ACTION,
            authorizationId, scopeBasis(before.getCollegeId(), before.getBatchId()), auditSnapshot(before),
            auditSnapshot(after), reason);
        authorizationRepository.completeOperation(operationId);
        return new AdminAuthorizationCommandVO(authorizationId, "REVOKED");
    }

    /**
     * 重新从数据库读取操作者并检查角色/状态，写操作还锁定操作者行。
     *
     * <p>请求过滤器也会刷新 Session 主体；这里再次读取是为了保证本事务中的授权变更依据
     * 数据库当前账号状态，而不是仅依赖请求刚进入时的内存快照。</p>
     */
    private AccountPrincipal refreshAndValidateActor(AccountPrincipal actor, boolean forUpdate) {
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
        if (!AccountRole.ADMIN.name().equals(current.getRoleCode())) {
            throw forbidden("当前账号不是管理员");
        }
        try {
            return accountRepository.toPrincipal(current, actor.isTemporaryCredentialLogin());
        } catch (IllegalStateException exception) {
            throw new ApiException("UNAUTHENTICATED", "登录状态已失效", HttpStatus.UNAUTHORIZED);
        }
    }

    /** 总管理员管理能力由保留授权标记识别；目标学院仍需存在且有效，ADMIN 角色本身不代表总管理员。 */
    private void requireAccountManagerScope(AccountPrincipal actor, Long collegeId) {
        accountAuthorizationService.requireCapability(actor, ADMIN_ACCOUNT_MANAGER, collegeId, null);
    }

    /** 查询并校验账号角色；写路径使用行锁，读路径只查询。目标不存在时不泄露其他账号类型。 */
    private void requireAdminTarget(Long targetAccountId, boolean forUpdate, Long actorAccountId) {
        if (forUpdate && actorAccountId != null && actorAccountId.equals(targetAccountId)) {
            throw forbidden("常规授权接口只能管理其他管理员账号");
        }
        Optional<AccountEntity> target = forUpdate
            ? accountRepository.findByIdForUpdate(targetAccountId)
            : accountRepository.findById(targetAccountId);
        if (!target.isPresent() || !AccountRole.ADMIN.name().equals(target.get().getRoleCode())) {
            throw notFound("未找到目标管理员账号");
        }
    }

    /** 只有服务端能力枚举中登记的普通业务能力可以授予。 */
    private String validateGrantableCapability(String capabilityCode) {
        if (ADMIN_ACCOUNT_MANAGER.equals(capabilityCode)) {
            throw forbidden("管理员账户管理能力只能通过初始化或应急恢复流程调整");
        }
        if (!StringUtils.hasText(capabilityCode)) throw invalidArgument("能力代码不能为空");
        try {
            return AdminCapabilityCode.valueOf(capabilityCode).name();
        } catch (IllegalArgumentException exception) {
            throw invalidArgument("能力代码未登记或当前不支持");
        }
    }

    /** 确认批次真实属于授权所声明的学院；空批次表示学院级授权，不查批次表。 */
    private void validateBatchCollege(Long batchId, Long collegeId) {
        if (batchId == null) return;
        Optional<Long> actualCollegeId = authorizationRepository.findBatchCollegeId(batchId);
        if (!actualCollegeId.isPresent() || !collegeId.equals(actualCollegeId.get())) {
            throw notFound("未找到该学院范围内的批次");
        }
    }

    /** 检查学院存在。调用前必须先验证操作者范围，防止无权用户枚举学院主键。 */
    private void requireCollegeExists(Long collegeId) {
        if (!authorizationRepository.collegeExists(collegeId)) throw notFound("未找到学院");
    }

    /** 校验幂等头必须为规范 UUID v4，避免不同格式被当成不同请求标识。 */
    private String normalizeUuidV4(String idempotencyKey) {
        if (idempotencyKey == null || !UUID_V4.matcher(idempotencyKey).matches()) {
            throw invalidArgument("Idempotency-Key 必须是 UUID v4");
        }
        // UUID 十六进制字符大小写不影响其含义；统一大小写，避免同一个 UUID 被存成两个幂等键。
        return idempotencyKey.toLowerCase(Locale.ROOT);
    }

    /**
     * 检查已完成的幂等请求。
     *
     * <p>相同操作者/动作/键且请求摘要相同，直接返回原授权 ID；相同键但请求内容不同则拒绝，
     * 不会误把新请求合并到之前的授权操作。</p>
     */
    private Optional<AdminAuthorizationCommandVO> replayIfPresent(Long actorAccountId,
                                                                         String actionCode,
                                                                         String idempotencyKey,
                                                                         String fingerprint,
                                                                         String successResult) {
        Optional<AdminAuthorizationOperationEntity> existing = authorizationRepository.findOperation(
            actorAccountId, actionCode, idempotencyKey);
        if (!existing.isPresent()) return Optional.empty();
        AdminAuthorizationOperationEntity operation = existing.get();
        if (!fingerprint.equals(operation.getFingerprint())) {
            throw new ApiException("IDEMPOTENCY_KEY_REUSED", "幂等键已用于不同请求", HttpStatus.CONFLICT);
        }
        if (!"OK".equals(operation.getResultCode()) || operation.getAuthorizationId() == null) {
            throw new ApiException("REQUEST_IN_PROGRESS", "相同幂等请求仍在处理", HttpStatus.CONFLICT);
        }
        return Optional.of(new AdminAuthorizationCommandVO(operation.getAuthorizationId(), successResult));
    }

    /** 使用稳定字段顺序构造请求摘要，再通过 SHA-256 生成适合 business_operation 保存的指纹。 */
    private String fingerprint(Object... fields) {
        try {
            byte[] normalizedRequest = objectMapper.writeValueAsBytes(fields);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalizedRequest);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format("%02x", value & 0xff));
            return hex.toString();
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to fingerprint an authorization request", exception);
        }
    }

    /** 以固定字段顺序生成不含密码等敏感数据的审计快照 JSON。 */
    private String auditSnapshot(AdminAuthorizationEntity record) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("authorizationId", record.getId());
        values.put("accountId", record.getAccountId());
        values.put("capabilityCode", record.getCapabilityCode());
        values.put("collegeId", record.getCollegeId());
        values.put("batchId", record.getBatchId());
        values.put("basis", record.getBasis());
        values.put("grantedBy", record.getGrantedBy());
        values.put("grantedAt", isoTime(record.getGrantedAt()));
        values.put("status", record.isRevoked() ? "REVOKED" : "ACTIVE");
        values.put("revokedBy", record.getRevokedBy());
        values.put("revokedAt", isoTime(record.getRevokedAt()));
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize authorization audit snapshot", exception);
        }
    }

    /** 把数据库 UTC 时间转换为 ISO-8601 文本，避免返回受服务器本地时区影响的日期。 */
    private String isoTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    /** 将数据库授权记录投影到只包含 API 允许字段的响应对象。 */
    private AdminAccountAuthorizationVO toResponse(AdminAuthorizationEntity record) {
        return new AdminAccountAuthorizationVO(record.getId(), record.getCapabilityCode(),
            record.getCollegeId(), record.getBatchId(), record.getBasis(), record.getGrantedBy(),
            isoTime(record.getGrantedAt()), record.isRevoked() ? "REVOKED" : "ACTIVE",
            record.getRevokedBy(), isoTime(record.getRevokedAt()), record.getRevocationReason());
    }

    /** 生成仅含范围标识的审计摘要；授权依据单独保留在授权表及业务字段中。 */
    private String scopeBasis(Long collegeId, Long batchId) {
        return "collegeId=" + collegeId + ";batchId=" + (batchId == null ? "*" : batchId);
    }

    private void requirePositiveId(Long value, String fieldName) {
        if (value == null || value.longValue() <= 0L) throw invalidArgument(fieldName + "必须为正整数");
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
