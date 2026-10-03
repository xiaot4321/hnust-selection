package cn.hnust.selection.security;

import cn.hnust.selection.enums.AccountRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serializable;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Spring Security 的登录主体，也是服务端 Session 中保存的最小认证上下文。
 *
 * <p>主体仅包含账号主键、登录标识、角色、版本号、首次改密状态、本人身份概要和管理员有效授权；
 * 不保存密码哈希或临时凭证明文。身份概要供 /auth/me 展示，授权集合供服务端做权限判断，
 * 两者都不是长效快照：后续请求会由 AccountRefreshFilter 从数据库重新构造主体。</p>
 */
public class AccountPrincipal implements UserDetails, Serializable {
    private static final long serialVersionUID = 1L;

    private final Long accountId;
    private final String loginIdentifier;
    private final AccountRole role;
    private final String accountStatus;
    private final boolean mustChangePassword;
    private final boolean temporaryCredentialLogin;
    private final Timestamp credentialChangedAt;
    private final long accountVersion;
    private final AccountIdentity identity;
    private final List<AccountAuthorization> authorizations;

    public AccountPrincipal(Long accountId, String loginIdentifier, AccountRole role,
                            String accountStatus, boolean mustChangePassword,
                            boolean temporaryCredentialLogin, Timestamp credentialChangedAt,
                            long accountVersion, AccountIdentity identity,
                            List<AccountAuthorization> authorizations) {
        this.accountId = accountId;
        this.loginIdentifier = loginIdentifier;
        this.role = role;
        this.accountStatus = accountStatus;
        this.mustChangePassword = mustChangePassword;
        this.temporaryCredentialLogin = temporaryCredentialLogin;
        // Timestamp 可变；复制输入值，避免外部持有的对象事后改变主体快照。
        this.credentialChangedAt = credentialChangedAt == null ? null : new Timestamp(credentialChangedAt.getTime());
        this.accountVersion = accountVersion;
        this.identity = identity;
        // 使用防御性副本并暴露只读 List，避免调用方改写当前请求的授权集合。
        this.authorizations = Collections.unmodifiableList(new ArrayList<AccountAuthorization>(authorizations));
    }

    public Long getAccountId() { return accountId; }
    public String getLoginIdentifier() { return loginIdentifier; }
    public AccountRole getRole() { return role; }
    public String getAccountStatus() { return accountStatus; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public boolean isTemporaryCredentialLogin() { return temporaryCredentialLogin; }
    public Timestamp getCredentialChangedAt() {
        return credentialChangedAt == null ? null : new Timestamp(credentialChangedAt.getTime());
    }
    public long getAccountVersion() { return accountVersion; }
    public AccountIdentity getIdentity() { return identity; }
    public List<AccountAuthorization> getAuthorizations() { return authorizations; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        Set<GrantedAuthority> authorities = new LinkedHashSet<GrantedAuthority>();
        // ROLE_STUDENT / ROLE_TEACHER / ROLE_ADMIN 用于 URL 粗粒度门禁。
        // 管理员各项 capability 作为独立 authority 供方法表达式和精细权限检查使用，角色本身不隐含这些能力。
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
        for (AccountAuthorization authorization : authorizations) {
            authorities.add(new SimpleGrantedAuthority(authorization.getCapabilityCode()));
        }
        return Collections.unmodifiableSet(authorities);
    }

    // UserDetails 需要提供 password，但凭证在 Provider 中已经验证完毕；这里不返回数据库哈希，
    // 从而避免可序列化 Session 主体或调试输出意外带出密码哈希。
    @Override public String getPassword() { return ""; }
    @Override public String getUsername() { return loginIdentifier; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return "ACTIVE".equalsIgnoreCase(accountStatus); }
}
