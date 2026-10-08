package cn.hnust.selection.vo;

public class TeacherDirectoryDetailVO extends TeacherDirectoryItemVO {
    private String biography;
    private String educationExperience;
    private String workExperience;
    private String courses;
    private String researchAndAchievements;
    public String getBiography() { return biography; }
    public void setBiography(String biography) { this.biography = biography; }
    public String getEducationExperience() { return educationExperience; }
    public void setEducationExperience(String value) { educationExperience = value; }
    public String getWorkExperience() { return workExperience; }
    public void setWorkExperience(String value) { workExperience = value; }
    public String getCourses() { return courses; }
    public void setCourses(String value) { courses = value; }
    public String getResearchAndAchievements() { return researchAndAchievements; }
    public void setResearchAndAchievements(String value) { researchAndAchievements = value; }
}
