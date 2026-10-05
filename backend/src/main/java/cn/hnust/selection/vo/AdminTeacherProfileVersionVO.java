package cn.hnust.selection.vo;

/** 学院管理员审核导师公开资料版本的最小视图。 */
public class AdminTeacherProfileVersionVO {
    private Long versionId;
    private Long teacherId;
    private String employeeNo;
    private String fullName;
    private Long collegeId;
    private String collegeName;
    private Integer versionNo;
    private String reviewStatus;
    private String researchDirections;
    private String biography;
    private String submittedAt;
    private Integer currentPublishedVersionNo;
    private String currentPublishedResearchDirections;
    private String currentPublishedBiography;
    private Long rowVersion;
    private String etag;

    public Long getVersionId() { return versionId; }
    public void setVersionId(Long value) { versionId = value; }
    public Long getTeacherId() { return teacherId; }
    public void setTeacherId(Long value) { teacherId = value; }
    public String getEmployeeNo() { return employeeNo; }
    public void setEmployeeNo(String value) { employeeNo = value; }
    public String getFullName() { return fullName; }
    public void setFullName(String value) { fullName = value; }
    public Long getCollegeId() { return collegeId; }
    public void setCollegeId(Long value) { collegeId = value; }
    public String getCollegeName() { return collegeName; }
    public void setCollegeName(String value) { collegeName = value; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer value) { versionNo = value; }
    public String getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(String value) { reviewStatus = value; }
    public String getResearchDirections() { return researchDirections; }
    public void setResearchDirections(String value) { researchDirections = value; }
    public String getBiography() { return biography; }
    public void setBiography(String value) { biography = value; }
    public String getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(String value) { submittedAt = value; }
    public Integer getCurrentPublishedVersionNo() { return currentPublishedVersionNo; }
    public void setCurrentPublishedVersionNo(Integer value) { currentPublishedVersionNo = value; }
    public String getCurrentPublishedResearchDirections() { return currentPublishedResearchDirections; }
    public void setCurrentPublishedResearchDirections(String value) { currentPublishedResearchDirections = value; }
    public String getCurrentPublishedBiography() { return currentPublishedBiography; }
    public void setCurrentPublishedBiography(String value) { currentPublishedBiography = value; }
    public Long getRowVersion() { return rowVersion; }
    public void setRowVersion(Long value) { rowVersion = value; }
    public String getEtag() { return etag; }
    public void setEtag(String value) { etag = value; }
}
