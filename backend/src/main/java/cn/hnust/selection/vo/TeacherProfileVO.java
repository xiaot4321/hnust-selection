package cn.hnust.selection.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;

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
    private String publishedAvatarUrl;
    private String submittedAvatarUrl;
    private Long publishedAvatarFileId;
    private Long submittedAvatarFileId;
    private String publishedAvatarStorageKey;
    private String submittedAvatarStorageKey;
    private Integer versionNo;
    private String etag;
    private Long profileRowVersion;

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
    public String getPublishedAvatarUrl() { return publishedAvatarUrl; }
    public void setPublishedAvatarUrl(String value) { this.publishedAvatarUrl = value; }
    public String getSubmittedAvatarUrl() { return submittedAvatarUrl; }
    public void setSubmittedAvatarUrl(String value) { this.submittedAvatarUrl = value; }
    @JsonIgnore public Long getPublishedAvatarFileId() { return publishedAvatarFileId; }
    public void setPublishedAvatarFileId(Long value) { this.publishedAvatarFileId = value; }
    @JsonIgnore public Long getSubmittedAvatarFileId() { return submittedAvatarFileId; }
    public void setSubmittedAvatarFileId(Long value) { this.submittedAvatarFileId = value; }
    @JsonIgnore public String getPublishedAvatarStorageKey() { return publishedAvatarStorageKey; }
    public void setPublishedAvatarStorageKey(String value) { this.publishedAvatarStorageKey = value; }
    @JsonIgnore public String getSubmittedAvatarStorageKey() { return submittedAvatarStorageKey; }
    public void setSubmittedAvatarStorageKey(String value) { this.submittedAvatarStorageKey = value; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }
    @JsonIgnore public Long getProfileRowVersion() { return profileRowVersion; }
    public void setProfileRowVersion(Long profileRowVersion) { this.profileRowVersion = profileRowVersion; }
}
