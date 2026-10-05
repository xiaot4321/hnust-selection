package cn.hnust.selection.vo;

import cn.hnust.selection.security.AccountAuthorization;
import cn.hnust.selection.security.AccountIdentity;
import cn.hnust.selection.security.AccountPrincipal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 返回给当前用户的账号和角色化身份概要。
 * 该 DTO 由认证主体显式投影而来，避免直接序列化实体或主体；不会包含密码哈希、临时凭证明文、
 * 联系方式、业务资料、授权依据或撤销历史。
 */
public class AuthUserVO {
    private final Long accountId;
    private final String loginIdentifier;
    private final String role;
    private final String accountStatus;
    private final boolean mustChangePassword;
    private final IdentitySummary identity;
    private final List<AuthorizationSummaryVO> authorizations;

    private AuthUserVO(AccountPrincipal principal) {
        this.accountId = principal.getAccountId();
        this.loginIdentifier = principal.getLoginIdentifier();
        this.role = principal.getRole().name();
        this.accountStatus = principal.getAccountStatus();
        this.mustChangePassword = principal.isMustChangePassword();
        // 学生/导师只返回认证所需的本人概要；管理员没有 student/teacher 身份，因此该字段为 null。
        AccountIdentity principalIdentity = principal.getIdentity();
        this.identity = principalIdentity == null ? null : new IdentitySummary(principalIdentity);
        List<AuthorizationSummaryVO> summary = new ArrayList<AuthorizationSummaryVO>();
        // 仅复制主体上当前有效的 capability 与学院/批次范围；不暴露授权依据、操作人和审计历史。
        for (AccountAuthorization authorization : principal.getAuthorizations()) {
            summary.add(new AuthorizationSummaryVO(authorization.getCapabilityCode(),
                authorization.getCollegeId(), authorization.getBatchId()));
        }
        this.authorizations = Collections.unmodifiableList(summary);
    }

    /** 将当前认证主体转换为可安全返回给当前用户的响应 DTO。 */
    public static AuthUserVO from(AccountPrincipal principal) { return new AuthUserVO(principal); }
    public Long getAccountId() {
        return accountId;
    }
    public String getLoginIdentifier() {
        return loginIdentifier;
    }
    public String getRole() {
        return role;
    }
    public String getAccountStatus() {
        return accountStatus;
    }
    public boolean isMustChangePassword() {
        return mustChangePassword;
    }
    public IdentitySummary getIdentity() {
        return identity;
    }
    public List<AuthorizationSummaryVO> getAuthorizations() {
        return authorizations;
    }

    /** 身份概要的 API 子结构；identifier 对学生是学号，对导师是工号。 */
    public static class IdentitySummary {
        private final Long id;
        private final String displayName;
        private final String identifier;
        private final Long collegeId;

        private IdentitySummary(AccountIdentity identity) {
            this.id = identity.getId();
            this.displayName = identity.getDisplayName();
            this.identifier = identity.getIdentifier();
            this.collegeId = identity.getCollegeId();
        }

        public Long getId() {
            return id;
        }
        public String getDisplayName() {
            return displayName;
        }
        public String getIdentifier() {
            return identifier;
        }
        public Long getCollegeId() {
            return collegeId;
        }
    }
}
