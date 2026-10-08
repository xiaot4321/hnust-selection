package cn.hnust.selection.request;

import javax.validation.constraints.Size;
import javax.validation.constraints.NotNull;

/** 导师公开资料的完整编辑值；不接受姓名、工号、审核状态或发布状态。 */
public class UpdateTeacherProfileRequest {
    @NotNull
    @Size(max = 2000)
    private String researchDirections;
    @NotNull
    @Size(max = 10000)
    private String biography;
    @NotNull
    @Size(max = 10000)
    private String educationExperience;
    @NotNull
    @Size(max = 10000)
    private String workExperience;
    @NotNull
    @Size(max = 10000)
    private String courses;
    @NotNull
    @Size(max = 10000)
    private String researchAndAchievements;
    public String getResearchDirections() { return researchDirections; }
    public void setResearchDirections(String researchDirections) { this.researchDirections = researchDirections; }
    public String getBiography() { return biography; }
    public void setBiography(String biography) { this.biography = biography; }
    public String getEducationExperience() { return educationExperience; }
    public void setEducationExperience(String educationExperience) { this.educationExperience = educationExperience; }
    public String getWorkExperience() { return workExperience; }
    public void setWorkExperience(String workExperience) { this.workExperience = workExperience; }
    public String getCourses() { return courses; }
    public void setCourses(String courses) { this.courses = courses; }
    public String getResearchAndAchievements() { return researchAndAchievements; }
    public void setResearchAndAchievements(String researchAndAchievements) { this.researchAndAchievements = researchAndAchievements; }
}
