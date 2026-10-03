package cn.hnust.selection.service.impl;

import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.repository.AccountRepository;
import cn.hnust.selection.repository.AccountRepository.AccountRecord;
import cn.hnust.selection.repository.AccountRepository.TemporaryCredential;
import cn.hnust.selection.service.AccountAuthService;
import cn.hnust.selection.security.AccountPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * 本地账号认证和密码凭证生命周期的具体实现。
 *
 * <p>认证入口只用登录标识定位账号。角色、人员身份、管理员能力和数据范围都由数据库记录构造，
 * 不采用请求体传入的 accountId、role 或授权信息。Session 后续每个请求也会重新读取主体，
 * 让账号停用、身份变更和授权撤销尽快生效。</p>
 *
 * <p>本类负责组织凭证验证与改密流程；具体 SQL 由 {@link AccountRepository} 执行，
 * 改密时使用事务保证临时凭证消费与密码更新一起成功或一起回滚。</p>
 */
@Service
public class AccountAuthServiceImpl implements AccountAuthService {
    // 对不存在账号或没有正式密码的失败尝试也执行 BCrypt，尽量让失败请求耗时接近真实密码比对。
    // 这样可减少攻击者通过响应时间猜测登录标识是否存在的机会；该值不是任何账号的真实凭证。
    private static final String DUMMY_PASSWORD_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;

    public AccountAuthServiceImpl(AccountRepository accountRepository, PasswordEncoder passwordEncoder) {
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 验证正式密码或首次登录使用的一次性临时凭证，并生成不含密码的 Security 主体。
     *
     * <p>正式密码只在账号不处于“必须改密”状态时有效；临时凭证只在未使用、未撤销、未过期，
     * 且账号要求改密时有效。账号不存在和密码错误统一返回凭证错误，避免泄露账号是否存在。</p>
     *
     * @param loginIdentifier 学号、工号或管理员登录标识
     * @param credential 用户提交的正式密码或临时凭证明文；该值不会放进返回主体
     * @return 由数据库账号及关联身份/授权构造的认证主体
     * @throws BadCredentialsException 登录标识或凭证无效，或账号关联数据不完整
     * @throws DisabledException 账号存在但状态不是 ACTIVE
     */
    @Override
    public AccountPrincipal authenticate(String loginIdentifier, String credential) {
        // BCrypt 最多使用 72 个 UTF-8 字节。先拒绝更长输入，避免超长密码被哈希算法截断后发生凭证混淆。
        // 对该失败路径仍执行一次虚拟哈希比较，避免超长输入和不存在账号的失败耗时差异过于明显。
        if (credential == null || credential.getBytes(StandardCharsets.UTF_8).length > 72) {
            passwordEncoder.matches("invalid", DUMMY_PASSWORD_HASH);
            throw new BadCredentialsException("Invalid credentials");
        }
        Optional<AccountRecord> found = accountRepository.findByLoginIdentifier(loginIdentifier);
        if (!found.isPresent()) {
            // 使用虚拟哈希完成与普通失败请求相似的密码计算；对外仍返回同一种凭证错误。
            passwordEncoder.matches(credential, DUMMY_PASSWORD_HASH);
            throw new BadCredentialsException("Invalid credentials");
        }

        AccountRecord account = found.get();
        // 停用账号按文档返回 ACCOUNT_DISABLED，不继续验证密码或建立会话。
        if (!"ACTIVE".equalsIgnoreCase(account.getAccountStatus())) {
            throw new DisabledException("Account disabled");
        }

        boolean temporaryCredential = false;
        // 处于首次改密状态时正式密码不参与校验，必须走管理员发放的临时凭证流程。
        // 没有可用正式密码时执行虚拟比较，也维持密码计算量，之后再尝试临时凭证。
        boolean hasUsablePassword = !account.isMustChangePassword() && account.getPasswordHash() != null;
        boolean passwordMatches = hasUsablePassword
            ? passwordEncoder.matches(credential, account.getPasswordHash()) : false;
        if (!hasUsablePassword) passwordEncoder.matches(credential, DUMMY_PASSWORD_HASH);

        if (!passwordMatches) {
            // SQL 已过滤掉过期、已撤销和已消费记录；Java 层再次检查状态，避免数据映射变化时误放行。
            // 每个账号只读取最近一小段有效凭证历史，避免无界遍历大量 BCrypt 哈希。
            for (TemporaryCredential temporary : accountRepository.findValidTemporaryCredentials(account.getId())) {
                if (!temporary.isUsed() && !temporary.isRevoked() && temporary.isNotExpired()
                    && passwordEncoder.matches(credential, temporary.getCredentialHash())) {
                    temporaryCredential = true;
                    break;
                }
            }
        }
        if (!passwordMatches && !temporaryCredential) {
            throw new BadCredentialsException("Invalid credentials");
        }
        if (temporaryCredential && !account.isMustChangePassword()) {
            // 临时凭证只用于强制改密，不作为普通账号的长期登录替代品。
            throw new BadCredentialsException("Invalid credentials");
        }

        AccountPrincipal principal;
        try {
            // 主体构造时还会核对角色代码与人员表关联是否完整，并只加载管理员的有效能力授权。
            principal = accountRepository.toPrincipal(account, temporaryCredential);
        } catch (IllegalStateException ex) {
            // 不向客户端暴露内部关联表不一致的细节，登录端点继续表现为凭证无效。
            throw new BadCredentialsException("Invalid credentials");
        }
        // 仅在凭证及主体数据全部校验成功后更新时间；该审计字段不递增 row_version。
        accountRepository.recordSuccessfulLogin(account.getId());
        return principal;
    }

    /**
     * 依据 Session 中的账号主键重建最新认证主体，不沿用 Session 内可能过期的角色和授权快照。
     *
     * @param accountId Session 中保存的账号主键
     * @param temporaryCredentialLogin 保留本次会话由临时凭证建立的标记，供首次改密门禁使用
     * @return 当前账号主体；账号已删除时返回 {@code null}
     */
    @Override
    public AccountPrincipal refreshPrincipal(Long accountId, boolean temporaryCredentialLogin) {
        // 每个受保护请求都通过此处重新加载账号状态、角色、人员关联、版本号及管理员授权。
        // Session 只作为账号主键和认证流程标记的载体，不能作为当前授权是否仍有效的唯一依据。
        Optional<AccountRecord> found = accountRepository.findById(accountId);
        if (!found.isPresent()) return null;
        return accountRepository.toPrincipal(found.get(), temporaryCredentialLogin);
    }

    /**
     * 校验当前凭证并设置新密码。整个流程在一个事务内完成，避免并发请求重复消费临时凭证。
     *
     * <p>普通改密要求当前正式密码；首次改密或临时凭证登录要求对应临时凭证。成功后递增账号
     * row_version，使其他旧 Session 在下次请求刷新主体时失效，而当前请求会使用新主体继续会话。</p>
     *
     * @param principal 当前已认证主体，只使用其账号主键和改密流程标记
     * @param currentCredential 当前正式密码或临时凭证明文
     * @param newPassword 用户希望设置的新正式密码明文；只在编码后写入数据库
     * @return 以更新后数据库状态重建的主体
     */
    @Transactional
    @Override
    public AccountPrincipal changePassword(AccountPrincipal principal, String currentCredential, String newPassword) {
        validateNewPassword(newPassword);
        // 先锁账号行再读取/消费临时凭证。并发改密请求在同一账号行上串行，第二个请求会看到首个事务结果。
        // 账号不存在时无法继续确认当前身份，返回未认证而不是创建新记录。
        AccountRecord account = accountRepository.findByIdForUpdate(principal.getAccountId())
            .orElseThrow(() -> new ApiException("UNAUTHENTICATED", "登录状态已失效", HttpStatus.UNAUTHORIZED));
        if (!"ACTIVE".equalsIgnoreCase(account.getAccountStatus())) {
            throw new ApiException("ACCOUNT_DISABLED", "账号已停用", HttpStatus.FORBIDDEN);
        }

        // 从请求时的主体判断凭证类型，同时在行锁内读取最新账号状态，防止客户端自行切换改密模式。
        boolean consumeTemporaryCredential = principal.isMustChangePassword()
            || principal.isTemporaryCredentialLogin();
        if (consumeTemporaryCredential) {
            TemporaryCredential matched = null;
            // 这里要包含已消费记录：若凭证明文能匹配到一条已使用记录，返回更准确的冲突错误；
            // 查询结果按最近签发优先，并在改密事务中加锁，避免并发消费时各自读到可用旧状态。
            for (TemporaryCredential temporary : accountRepository.findTemporaryCredentials(account.getId(), true)) {
                if (passwordEncoder.matches(currentCredential, temporary.getCredentialHash())) {
                    matched = temporary;
                    break;
                }
            }
            if (matched == null) {
                throw new ApiException("TEMP_CREDENTIAL_EXPIRED", "临时凭证已过期或无效", HttpStatus.UNAUTHORIZED);
            }
            if (matched.isUsed()) {
                throw new ApiException("TEMP_CREDENTIAL_ALREADY_USED", "临时凭证已使用", HttpStatus.CONFLICT);
            }
            if (matched.isRevoked() || !matched.isNotExpired()) {
                throw new ApiException("TEMP_CREDENTIAL_EXPIRED", "临时凭证已过期或无效", HttpStatus.UNAUTHORIZED);
            }
            if (!accountRepository.consumeTemporaryCredential(matched.getId())) {
                // 即使前面已加锁，SQL 仍通过未使用/未撤销/未过期条件做原子保护。
                // 更新行数为 0 表示凭证状态已变化；抛异常会回滚本次事务，不写入新密码。
                throw new ApiException("TEMP_CREDENTIAL_ALREADY_USED", "临时凭证已使用", HttpStatus.CONFLICT);
            }
        } else if (account.getPasswordHash() == null
            || !passwordEncoder.matches(currentCredential, account.getPasswordHash())) {
            throw new ApiException("INVALID_CREDENTIALS", "当前凭证不正确", HttpStatus.UNAUTHORIZED);
        }

        // BCrypt 只编码新密码，数据库永远不保存新密码明文。临时凭证消费和密码更新处于同一事务，
        // 更新或后续读取失败都会回滚凭证消费，避免用户凭证被消耗但密码没有成功设置。
        accountRepository.updatePassword(account.getId(), passwordEncoder.encode(newPassword));
        AccountRecord updated = accountRepository.findById(account.getId())
            .orElseThrow(() -> new ApiException("UNAUTHENTICATED", "登录状态已失效", HttpStatus.UNAUTHORIZED));
        return accountRepository.toPrincipal(updated, false);
    }

    private void validateNewPassword(String password) {
        // 业务文档没有确认必须包含大小写、数字或符号等复杂度规则，服务端不能自行加规则。
        // 本方法只拒绝空白密码，以及 Java 字符数或 BCrypt UTF-8 字节数超出当前存储/算法边界的输入。
        if (password == null || password.trim().isEmpty()) {
            throw new ApiException("INVALID_ARGUMENT", "新密码不能为空", HttpStatus.BAD_REQUEST);
        }
        if (password.length() > 128 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException("INVALID_ARGUMENT", "新密码长度超出安全哈希支持范围", HttpStatus.BAD_REQUEST);
        }
    }
}
