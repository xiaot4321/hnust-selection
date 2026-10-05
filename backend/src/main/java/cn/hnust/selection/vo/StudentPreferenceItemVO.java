package cn.hnust.selection.vo;

import java.util.ArrayList;
import java.util.List;

public class StudentPreferenceItemVO {
    private Long teacherId;
    private Integer preferenceOrder;
    private String teacherName;
    private String employeeNo;
    private List<String> researchDirections = new ArrayList<String>();

    public Long getTeacherId() { return teacherId; }
    public void setTeacherId(Long teacherId) { this.teacherId = teacherId; }
    public Integer getPreferenceOrder() { return preferenceOrder; }
    public void setPreferenceOrder(Integer preferenceOrder) { this.preferenceOrder = preferenceOrder; }
    public String getTeacherName() { return teacherName; }
    public void setTeacherName(String teacherName) { this.teacherName = teacherName; }
    public String getEmployeeNo() { return employeeNo; }
    public void setEmployeeNo(String employeeNo) { this.employeeNo = employeeNo; }
    public List<String> getResearchDirections() { return researchDirections; }
    public void setResearchDirections(List<String> researchDirections) { this.researchDirections = researchDirections; }
}
