package cn.hnust.selection.entity;

/** 导入行的持久化结果，不含首次响应中临时附加的一次性凭证明文。 */
public class PersonnelImportRowEntity {
    private final Integer rowNumber;
    private final String personIdentifier;
    private final String status;
    private final String errorCode;
    private final String errorMessage;
    private final Long personId;
    private final Long eligibilityId;

    public PersonnelImportRowEntity(Integer rowNumber, String personIdentifier, String status,
                                    String errorCode, String errorMessage, Long personId,
                                    Long eligibilityId) {
        this.rowNumber = rowNumber;
        this.personIdentifier = personIdentifier;
        this.status = status;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.personId = personId;
        this.eligibilityId = eligibilityId;
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
}
