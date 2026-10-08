package cn.hnust.selection.vo;

import java.util.List;

public class TeacherDirectoryItemVO {
    private Long teacherId;
    private String employeeNo;
    private String displayName;
    private List<String> researchDirections;
    private String profileSummary;
    private List<String> allowedDegreeTypes;
    private List<AllowedMajorVO> allowedMajors;
    private boolean canApply;
    private TeacherOfficialProfileVO officialProfile;

    public Long getTeacherId() { return teacherId; }
    public void setTeacherId(Long teacherId) { this.teacherId = teacherId; }
    public String getEmployeeNo() { return employeeNo; }
    public void setEmployeeNo(String employeeNo) { this.employeeNo = employeeNo; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public List<String> getResearchDirections() { return researchDirections; }
    public void setResearchDirections(List<String> researchDirections) { this.researchDirections = researchDirections; }
    public String getProfileSummary() { return profileSummary; }
    public void setProfileSummary(String profileSummary) { this.profileSummary = profileSummary; }
    public List<String> getAllowedDegreeTypes() { return allowedDegreeTypes; }
    public void setAllowedDegreeTypes(List<String> allowedDegreeTypes) { this.allowedDegreeTypes = allowedDegreeTypes; }
    public List<AllowedMajorVO> getAllowedMajors() { return allowedMajors; }
    public void setAllowedMajors(List<AllowedMajorVO> allowedMajors) { this.allowedMajors = allowedMajors; }
    public boolean isCanApply() { return canApply; }
    public void setCanApply(boolean canApply) { this.canApply = canApply; }
    public TeacherOfficialProfileVO getOfficialProfile() { return officialProfile; }
    public void setOfficialProfile(TeacherOfficialProfileVO officialProfile) { this.officialProfile = officialProfile; }
}
