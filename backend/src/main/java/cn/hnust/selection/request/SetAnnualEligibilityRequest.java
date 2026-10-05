package cn.hnust.selection.request;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;

/** 年度资格状态命令；修改时追加新版本，并关闭旧的当前记录，不覆盖历史行。 */
public class SetAnnualEligibilityRequest {
    @NotBlank @Pattern(regexp = "STUDENT|TEACHER") private String personType;
    @NotNull @Positive private Long personId;
    @NotNull @Positive private Long academicYearId;
    @NotNull @Positive private Long collegeId;
    @NotBlank @Pattern(regexp = "ELIGIBLE|INELIGIBLE") private String eligibilityStatus;
    @NotBlank @Size(max = 32) private String evidenceType;
    @Size(max = 4000) private String evidenceReference;
    @Size(max = 128) private String sourceName;

    public String getPersonType() { return personType; }
    public void setPersonType(String personType) { this.personType = personType; }
    public Long getPersonId() { return personId; }
    public void setPersonId(Long personId) { this.personId = personId; }
    public Long getAcademicYearId() { return academicYearId; }
    public void setAcademicYearId(Long academicYearId) { this.academicYearId = academicYearId; }
    public Long getCollegeId() { return collegeId; }
    public void setCollegeId(Long collegeId) { this.collegeId = collegeId; }
    public String getEligibilityStatus() { return eligibilityStatus; }
    public void setEligibilityStatus(String eligibilityStatus) { this.eligibilityStatus = eligibilityStatus; }
    public String getEvidenceType() { return evidenceType; }
    public void setEvidenceType(String evidenceType) { this.evidenceType = evidenceType; }
    public String getEvidenceReference() { return evidenceReference; }
    public void setEvidenceReference(String evidenceReference) { this.evidenceReference = evidenceReference; }
    public String getSourceName() { return sourceName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }
}
