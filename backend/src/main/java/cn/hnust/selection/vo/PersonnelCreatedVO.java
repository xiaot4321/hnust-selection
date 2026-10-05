package cn.hnust.selection.vo;

/** 人员创建回执；一次性临时凭证明文只存在于首次创建响应中。 */
public class PersonnelCreatedVO {
    private final String personType;
    private final Long personId;
    private final Long accountId;
    private final String loginIdentifier;
    private final AdminAccountCredentialVO credential;
    public PersonnelCreatedVO(String personType, Long personId, Long accountId,
                                    String loginIdentifier, AdminAccountCredentialVO credential) {
        this.personType = personType;
        this.personId = personId;
        this.accountId = accountId;
        this.loginIdentifier = loginIdentifier;
        this.credential = credential;
    }
    public String getPersonType() {
        return personType;
    }
    public Long getPersonId() {
        return personId;
    }
    public Long getAccountId() {
        return accountId;
    }
    public String getLoginIdentifier() {
        return loginIdentifier;
    }
    public AdminAccountCredentialVO getCredential() {
        return credential;
    }
}
