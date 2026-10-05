package cn.hnust.selection.vo;

/**
 * 名单导入的逐行处理结果。
 *
 * <p>{@code rowNumber} 按原文件的行号计数，{@code status} 为 CREATED 或 REJECTED；被拒绝行通过
 * {@code errorCode} 和 {@code errorMessage} 说明原因。成功行包含人员 ID、资格 ID 与登录名，
 * 一次性临时凭证只在创建该行的首次 POST 响应中填入，历史查询始终返回 {@code null}。</p>
 */
public class PersonnelImportRowVO {
    private final Integer rowNumber;
    private final String personIdentifier;
    private final String status;
    private final String errorCode;
    private final String errorMessage;
    private final Long personId;
    private final Long eligibilityId;
    private final String loginIdentifier;
    private final String temporaryCredential;

    public PersonnelImportRowVO(Integer rowNumber, String personIdentifier, String status,
                                      String errorCode, String errorMessage, Long personId,
                                      Long eligibilityId, String loginIdentifier, String temporaryCredential) {
        this.rowNumber = rowNumber;
        this.personIdentifier = personIdentifier;
        this.status = status;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.personId = personId;
        this.eligibilityId = eligibilityId;
        this.loginIdentifier = loginIdentifier;
        this.temporaryCredential = temporaryCredential;
    }
    public Integer getRowNumber() {
        return rowNumber;
    }
    public String getPersonIdentifier() {
        return personIdentifier;
    }
    public String getStatus() {
        return status;
    }
    public String getErrorCode() {
        return errorCode;
    }
    public String getErrorMessage() {
        return errorMessage;
    }
    public Long getPersonId() {
        return personId;
    }
    public Long getEligibilityId() {
        return eligibilityId;
    }
    public String getLoginIdentifier() {
        return loginIdentifier;
    }
    public String getTemporaryCredential() {
        return temporaryCredential;
    }
}
