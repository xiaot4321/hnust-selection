package cn.hnust.selection.service;

import cn.hnust.selection.request.CreateStudentRequest;
import cn.hnust.selection.request.CreateTeacherRequest;
import cn.hnust.selection.security.AccountPrincipal;

/**
 * 人员账号与初始档案注册的应用服务契约。
 *
 * <p>具体事务实现在 {@code service.impl} 中。名单导入逐行使用独立事务，因此该行的账号、人员档案、
 * 初始分类、年度资格、临时凭证及审计要么同时提交，要么同时回滚。</p>
 */
public interface PersonnelRegistrationService {
    RegisteredPerson createStudent(AccountPrincipal actor, Long operationId,
                                   CreateStudentRequest request, EligibilityInput eligibility);

    RegisteredPerson createTeacher(AccountPrincipal actor, Long operationId,
                                   CreateTeacherRequest request, EligibilityInput eligibility);

    /** 名单行可选的年度资格输入；学年由整个导入任务选定。 */
    final class EligibilityInput {
        public final Long academicYearId;
        public final String status;
        public final String evidenceType;
        public final String evidenceReference;
        public final String sourceName;

        public EligibilityInput(Long academicYearId, String status, String evidenceType,
                                String evidenceReference, String sourceName) {
            this.academicYearId = academicYearId;
            this.status = status;
            this.evidenceType = evidenceType;
            this.evidenceReference = evidenceReference;
            this.sourceName = sourceName;
        }
    }

    /** 凭证明文只在当前请求中短暂传递；expiresAt 兼容保留但当前为 null；不提供 toString 避免日志输出秘密。 */
    final class CredentialResult {
        private final String plaintext;
        private final String expiresAt;

        public CredentialResult(String plaintext, String expiresAt) {
            this.plaintext = plaintext;
            this.expiresAt = expiresAt;
        }

        public String getPlaintext() { return plaintext; }
        public String getExpiresAt() { return expiresAt; }
    }

    /** 创建完成的最小内部结果，只供应用层拼装首次响应。 */
    final class RegisteredPerson {
        private final String type;
        private final Long personId;
        private final Long accountId;
        private final String login;
        private final CredentialResult credential;
        private final Long eligibilityId;

        public RegisteredPerson(String type, Long personId, Long accountId, String login,
                                CredentialResult credential, Long eligibilityId) {
            this.type = type;
            this.personId = personId;
            this.accountId = accountId;
            this.login = login;
            this.credential = credential;
            this.eligibilityId = eligibilityId;
        }

        public String getType() { return type; }
        public Long getPersonId() { return personId; }
        public Long getAccountId() { return accountId; }
        public String getLogin() { return login; }
        public CredentialResult getCredential() { return credential; }
        public Long getEligibilityId() { return eligibilityId; }
    }
}
