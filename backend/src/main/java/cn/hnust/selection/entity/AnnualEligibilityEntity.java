package cn.hnust.selection.entity;

/**
 * 年度资格历史的数据库查询投影。
 *
 * <p>该记录将学年、学院、学生或导师身份、资格状态和依据一起映射。每次修改都会产生新的历史版本；
 * {@code validTo} 为空表示当前有效版本。日期由 Repository 按 ISO 格式映射，Service 再组装 API 视图。</p>
 */
public class AnnualEligibilityEntity {
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

    public AnnualEligibilityEntity(Long id, Long academicYearId, String yearCode, Long collegeId,
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
