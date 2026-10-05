package cn.hnust.selection.service.impl;

import cn.hnust.selection.bootstrap.InitialAdminBootstrapResult;
import cn.hnust.selection.entity.CollegeEntity;
import cn.hnust.selection.repository.SystemBootstrapRepository;
import cn.hnust.selection.service.SystemBootstrapService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 首次总管理员初始化的事务编排实现。
 *
 * <p>这里不接触浏览器请求，也不保存明文凭证。实现通过数据库保留能力槽位与账号唯一索引阻止
 * 重复初始化，随机临时凭证只经 PasswordEncoder 编码后进入 Repository，并在事务提交后交给
 * 一次性本机命令行入口显示。</p>
 */
@Service
public class SystemBootstrapServiceImpl implements SystemBootstrapService {
    private static final int TEMPORARY_CREDENTIAL_BYTES = 32;
    private static final int TEMPORARY_COLLEGE_CODE_BYTES = 12;
    private static final int RANDOM_CODE_ATTEMPTS = 10;

    private final SystemBootstrapRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom;

    @Autowired
    public SystemBootstrapServiceImpl(SystemBootstrapRepository repository,
                                      PasswordEncoder passwordEncoder,
                                      ObjectMapper objectMapper) {
        this(repository, passwordEncoder, objectMapper, new SecureRandom());
    }

    /** 包级测试构造器允许注入随机源；生产 Bean 始终使用 JVM 安全随机数生成器。 */
    SystemBootstrapServiceImpl(SystemBootstrapRepository repository,
                               PasswordEncoder passwordEncoder,
                               ObjectMapper objectMapper,
                               SecureRandom secureRandom) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
        this.secureRandom = secureRandom;
    }

    /**
     * 在单个事务中写入学院（若缺失）、管理员、授权、临时凭证、操作记录与审计事件。
     *
     * <p>最先检查保留授权槽位和登录标识，避免重复运行时留下孤立学院记录。最终仍由数据库唯一
     * 约束处理两个并发初始化进程同时通过预检查的竞态；任一写入冲突都会回滚本事务全部数据。</p>
     */
    @Transactional
    @Override
    public InitialAdminBootstrapResult initializeInitialAdmin(
        String loginIdentifier, String collegeCode, String collegeName) {
        String normalizedLogin = requireTrimmed(loginIdentifier, "登录标识", 128);
        String normalizedCollegeName = requireTrimmed(collegeName, "学院名称", 128);

        if (repository.hasReservedAdminManagerSlot()) {
            throw new IllegalStateException(
                "总管理员能力槽位已被使用；请按 TODO-24 的线下核验、双人复核和审计流程处理。");
        }
        if (repository.loginIdentifierExists(normalizedLogin)) {
            throw new IllegalStateException("登录标识已存在，初始化已停止且没有写入新账号。");
        }

        // 用户可提供正式代码；若按授权暂用随机代码，则生成带 TMP 标记的唯一值并在结果中返回。
        // 该临时代码不会冒充学校正式代码，后续应按学院正式编码维护流程更正。
        boolean generatedTemporaryCode = collegeCode == null || collegeCode.trim().isEmpty();
        String normalizedCollegeCode = generatedTemporaryCode
            ? generateUnusedTemporaryCollegeCode()
            : requireTrimmed(collegeCode, "学院代码", 32);

        Long collegeId = resolveCollege(normalizedCollegeCode, normalizedCollegeName);
        String temporaryCredential = generateTemporaryCredential();
        String credentialHash = passwordEncoder.encode(temporaryCredential);
        String requestId = UUID.randomUUID().toString();
        String requestFingerprint = fingerprint(normalizedLogin, normalizedCollegeCode, normalizedCollegeName);

        Long operationId = repository.insertBootstrapOperation(collegeId, requestId, requestFingerprint);
        if (generatedTemporaryCode) {
            repository.insertAuditEvent(operationId, "COLLEGE", collegeId,
                "COLLEGE_CREATED_FOR_BOOTSTRAP", collegeId, null,
                snapshot("collegeCode", normalizedCollegeCode, "name", normalizedCollegeName,
                    "codeKind", "TEMPORARY_RANDOM"),
                "按业务方指示临时随机生成学院代码；后续应按正式代码维护流程更正");
        }

        Long accountId = repository.insertInitialAdminAccount(normalizedLogin);
        // schema 的 granted_by 是非空 account 外键，而首次初始化时还不存在人工授权人。
        // 数据行用刚创建的账号满足 FK；紧邻的审计事件明确保留真实执行主体为 SYSTEM。
        Long authorizationId = repository.insertInitialAdminManagerAuthorization(accountId, collegeId);
        Long collegeAdminAuthorizationId = repository.insertInitialCollegeAdminAuthorization(accountId, collegeId);
        Long temporaryCredentialId = repository.insertShownTemporaryCredential(
            accountId, credentialHash, operationId);

        // 审计快照只包含账号标识、角色、状态及凭证元数据，绝不包含密码明文或哈希。
        repository.insertAuditEvent(operationId, "ACCOUNT", accountId,
            "ADMIN_ACCOUNT_BOOTSTRAPPED", collegeId, null,
            snapshot("loginIdentifier", normalizedLogin, "roleCode", "ADMIN",
                "accountStatus", "ACTIVE", "mustChangePassword", true),
            "系统首次初始化总管理员账号");
        repository.insertAuditEvent(operationId, "ACCOUNT_AUTHORIZATION", authorizationId,
            "ADMIN_MANAGER_CAPABILITY_INITIALIZED", collegeId, null,
            snapshot("accountId", accountId, "capabilityCode", "ADMIN_ACCOUNT_MANAGER",
                "authoritySlot", "ADMIN_ACCOUNT_MANAGER", "collegeId", collegeId),
            "系统初始化设置唯一总管理员能力；实际操作者为 SYSTEM");
        repository.insertAuditEvent(operationId, "ACCOUNT_AUTHORIZATION", collegeAdminAuthorizationId,
            "COLLEGE_ADMIN_CAPABILITY_INITIALIZED", collegeId, null,
            snapshot("accountId", accountId, "capabilityCode", "COLLEGE_ADMIN", "collegeId", collegeId),
            "系统初始化总管理员所属学院的一般业务能力；实际操作者为 SYSTEM");
        repository.insertAuditEvent(operationId, "TEMPORARY_CREDENTIAL", temporaryCredentialId,
            "TEMPORARY_CREDENTIAL_ISSUED_AND_DISPLAY_RESERVED", collegeId, null,
            snapshot("accountId", accountId, "expiresAt", null,
                "singleUse", true, "displayChannel", "LOCAL_CONSOLE"),
            "凭证不设到期时间，在事务中登记为单次展示；提交后由本机控制台输出，明文不入库、不入审计");

        repository.completeBootstrapOperation(operationId);
        return new InitialAdminBootstrapResult(accountId, normalizedLogin, normalizedCollegeCode,
            normalizedCollegeName, temporaryCredential);
    }

    /** 复用完全匹配且启用的学院；任何代码或名称歧义都会中止，而不擅自更改组织主数据。 */
    private Long resolveCollege(String collegeCode, String collegeName) {
        Optional<CollegeEntity> existingCollege = repository.findCollegeByCode(collegeCode);
        if (existingCollege.isPresent()) {
            CollegeEntity record = existingCollege.get();
            if (!record.isActive() || !collegeName.equals(record.getName())) {
                throw new IllegalStateException("学院代码已存在，但名称不匹配或学院未启用；未更改学院数据。");
            }
            return record.getId();
        }
        if (repository.collegeNameExists(collegeName)) {
            throw new IllegalStateException("同名学院已使用其他代码；请提供现有正式代码，未创建重复学院。");
        }
        try {
            return repository.insertCollege(collegeCode, collegeName);
        } catch (DuplicateKeyException exception) {
            // 并发插入同学院代码或名称时不尝试自动合并，避免误关联到未经核实的组织记录。
            throw new IllegalStateException("学院记录在初始化期间发生冲突；事务已回滚，请核对学院信息后重试。",
                exception);
        }
    }

    /** 用操作系统安全随机源产生高熵凭证，URL-safe Base64 便于线下口述和复制。 */
    private String generateTemporaryCredential() {
        byte[] randomBytes = new byte[TEMPORARY_CREDENTIAL_BYTES];
        secureRandom.nextBytes(randomBytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    /** 返回形如 TMP-<24 位随机十六进制字符> 的代码，并先对数据库做碰撞检查。 */
    private String generateUnusedTemporaryCollegeCode() {
        for (int attempt = 0; attempt < RANDOM_CODE_ATTEMPTS; attempt++) {
            byte[] randomBytes = new byte[TEMPORARY_COLLEGE_CODE_BYTES];
            secureRandom.nextBytes(randomBytes);
            String candidate = "TMP-" + toHex(randomBytes);
            if (!repository.collegeCodeExists(candidate)) return candidate;
        }
        throw new IllegalStateException("无法生成未占用的临时学院代码；没有写入任何数据。");
    }

    /** SHA-256 十六进制格式只用于非秘密请求参数的审计指纹，不参与认证或授权判断。 */
    private static String fingerprint(String login, String collegeCode, String collegeName) {
        String canonical = "ADMIN_BOOTSTRAP\u0000" + login + "\u0000" + collegeCode + "\u0000" + collegeName;
        try {
            return toHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM does not provide SHA-256", exception);
        }
    }

    /** 审计字段使用 ObjectMapper 正确转义，确保结构化快照不会因学院名中的引号而损坏。 */
    private String snapshot(Object... keyValues) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        for (int index = 0; index < keyValues.length; index += 2) {
            values.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成初始化审计快照", exception);
        }
    }

    private static String requireTrimmed(String value, String label, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + "不能为空。");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(label + "长度不能超过 " + maxLength + " 个字符。");
        }
        return trimmed;
    }

    private static String toHex(byte[] bytes) {
        char[] digits = "0123456789ABCDEF".toCharArray();
        char[] output = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xFF;
            output[index * 2] = digits[value >>> 4];
            output[index * 2 + 1] = digits[value & 0x0F];
        }
        return new String(output);
    }
}
