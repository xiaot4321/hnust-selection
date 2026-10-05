package cn.hnust.selection.vo;

/**
 * 人员某一学年的当前资格或历史版本视图。
 *
 * <p>{@code personType} 与 {@code personId} 定位学生或导师，{@code status} 表示 ELIGIBLE 或 INELIGIBLE；
 * {@code evidenceType}、{@code evidenceReference} 和 {@code sourceName} 记录资格依据。
 * 每次调整保留为新历史版本，{@code validTo} 为空表示该人员该学年的当前版本。</p>
 */
public class AnnualEligibilityVO {
    private final Long id;
    private final Long academicYearId;
    private final String yearCode;
    private final Long collegeId;
    private final String personType;
    private final Long personId;
    private final String personIdentifier;
    private final String personName;
    private final String status;
    private final String evidenceType;
    private final String evidenceReference;
    private final String sourceName;
    private final String validFrom;
    private final String validTo;
    private final Long changedBy;

    public AnnualEligibilityVO(Long id, Long academicYearId, String yearCode, Long collegeId,
                                     String personType, Long personId, String personIdentifier,
                                     String personName, String status, String evidenceType,
                                     String evidenceReference, String sourceName, String validFrom,
                                     String validTo, Long changedBy) {
        this.id = id;
        this.academicYearId = academicYearId;
        this.yearCode = yearCode;
        this.collegeId = collegeId;
        this.personType = personType;
        this.personId = personId;
        this.personIdentifier = personIdentifier;
        this.personName = personName;
        this.status = status;
        this.evidenceType = evidenceType;
        this.evidenceReference = evidenceReference;
        this.sourceName = sourceName;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.changedBy = changedBy;
    }
    public Long getId() {
        return id;
    }
    public Long getAcademicYearId() {
        return academicYearId;
    }
    public String getYearCode() {
        return yearCode;
    }
    public Long getCollegeId() {
        return collegeId;
    }
    public String getPersonType() {
        return personType;
    }
    public Long getPersonId() {
        return personId;
    }
    public String getPersonIdentifier() {
        return personIdentifier;
    }
    public String getPersonName() {
        return personName;
    }
    public String getStatus() {
        return status;
    }
    public String getEvidenceType() {
        return evidenceType;
    }
    public String getEvidenceReference() {
        return evidenceReference;
    }
    public String getSourceName() {
        return sourceName;
    }
    public String getValidFrom() {
        return validFrom;
    }
    public String getValidTo() {
        return validTo;
    }
    public Long getChangedBy() {
        return changedBy;
    }
}
