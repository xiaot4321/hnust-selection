package cn.hnust.selection.vo;

/** 导师本人资料页视图；不包含账号凭证或未公开的内部字段。 */
public class TeacherProfileVO {
    private Long teacherId;
    private String employeeNo;
    private String fullName;
    private String publishedResearchDirections;
    private String publishedBiography;
    private String publishedEducationExperience;
    private String publishedWorkExperience;
    private String publishedCourses;
    private String publishedResearchAndAchievements;
    private String reviewStatus;
    private String reviewComment;
    private String submittedResearchDirections;
    private String submittedBiography;
    private String submittedEducationExperience;
    private String submittedWorkExperience;
    private String submittedCourses;
    private String submittedResearchAndAchievements;
    private Integer versionNo;
    private String etag;

    public Long getTeacherId() { return teacherId; }
    public void setTeacherId(Long teacherId) { this.teacherId = teacherId; }
    public String getEmployeeNo() { return employeeNo; }
    public void setEmployeeNo(String employeeNo) { this.employeeNo = employeeNo; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getPublishedResearchDirections() { return publishedResearchDirections; }
    public void setPublishedResearchDirections(String value) { this.publishedResearchDirections = value; }
    public String getPublishedBiography() { return publishedBiography; }
    public void setPublishedBiography(String value) { this.publishedBiography = value; }
    public String getPublishedEducationExperience() { return publishedEducationExperience; }
    public void setPublishedEducationExperience(String value) { this.publishedEducationExperience = value; }
    public String getPublishedWorkExperience() { return publishedWorkExperience; }
    public void setPublishedWorkExperience(String value) { this.publishedWorkExperience = value; }
    public String getPublishedCourses() { return publishedCourses; }
    public void setPublishedCourses(String value) { this.publishedCourses = value; }
    public String getPublishedResearchAndAchievements() { return publishedResearchAndAchievements; }
    public void setPublishedResearchAndAchievements(String value) { this.publishedResearchAndAchievements = value; }
    public String getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(String reviewStatus) { this.reviewStatus = reviewStatus; }
    public String getReviewComment() { return reviewComment; }
    public void setReviewComment(String reviewComment) { this.reviewComment = reviewComment; }
    public String getSubmittedResearchDirections() { return submittedResearchDirections; }
    public void setSubmittedResearchDirections(String value) { this.submittedResearchDirections = value; }
    public String getSubmittedBiography() { return submittedBiography; }
    public void setSubmittedBiography(String value) { this.submittedBiography = value; }
    public String getSubmittedEducationExperience() { return submittedEducationExperience; }
    public void setSubmittedEducationExperience(String value) { this.submittedEducationExperience = value; }
    public String getSubmittedWorkExperience() { return submittedWorkExperience; }
    public void setSubmittedWorkExperience(String value) { this.submittedWorkExperience = value; }
    public String getSubmittedCourses() { return submittedCourses; }
    public void setSubmittedCourses(String value) { this.submittedCourses = value; }
    public String getSubmittedResearchAndAchievements() { return submittedResearchAndAchievements; }
    public void setSubmittedResearchAndAchievements(String value) { this.submittedResearchAndAchievements = value; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }
}
