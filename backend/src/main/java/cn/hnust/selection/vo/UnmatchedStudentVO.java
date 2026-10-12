package cn.hnust.selection.vo;

/** 当前批次尚未匹配学生的最小名单字段。 */
public class UnmatchedStudentVO {
    private final String studentNo, fullName, collegeName, majorName;
    public UnmatchedStudentVO(String studentNo, String fullName, String collegeName, String majorName) {
        this.studentNo = studentNo; this.fullName = fullName;
        this.collegeName = collegeName; this.majorName = majorName;
    }
    public String getStudentNo() { return studentNo; }
    public String getFullName() { return fullName; }
    public String getCollegeName() { return collegeName; }
    public String getMajorName() { return majorName; }
}
