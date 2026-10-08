package cn.hnust.selection.vo;

import java.time.LocalDateTime;
import java.util.List;

/** Public academic information copied from the university faculty portal. */
public class TeacherOfficialProfileVO {
    private String photoUrl;
    private String professionalTitle;
    private String educationLevel;
    private String department;
    private String teachingLevel;
    private List<String> researchDirections;
    private String biography;
    private String educationExperience;
    private String workExperience;
    private String courses;
    private String researchAndAchievements;
    private String profileUrl;
    private String sourceName;
    private LocalDateTime cachedAt;

    public String getPhotoUrl() { return photoUrl; }
    public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }
    public String getProfessionalTitle() { return professionalTitle; }
    public void setProfessionalTitle(String professionalTitle) { this.professionalTitle = professionalTitle; }
    public String getEducationLevel() { return educationLevel; }
    public void setEducationLevel(String educationLevel) { this.educationLevel = educationLevel; }
    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }
    public String getTeachingLevel() { return teachingLevel; }
    public void setTeachingLevel(String teachingLevel) { this.teachingLevel = teachingLevel; }
    public List<String> getResearchDirections() { return researchDirections; }
    public void setResearchDirections(List<String> researchDirections) { this.researchDirections = researchDirections; }
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
    public String getProfileUrl() { return profileUrl; }
    public void setProfileUrl(String profileUrl) { this.profileUrl = profileUrl; }
    public String getSourceName() { return sourceName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }
    public LocalDateTime getCachedAt() { return cachedAt; }
    public void setCachedAt(LocalDateTime cachedAt) { this.cachedAt = cachedAt; }
}
