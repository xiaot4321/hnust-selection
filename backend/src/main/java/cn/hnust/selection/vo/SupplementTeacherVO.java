package cn.hnust.selection.vo;

/** 管理员查看的补选导师许可和对应名额摘要。 */
public class SupplementTeacherVO {
    private final Long teacherId;
    private final String employeeNo;
    private final String fullName;
    private final boolean permitted;
    private final Integer permissionVersion;
    private final Integer quotaLimit;
    private final int occupiedCount;
    private final int remainingCount;
    public SupplementTeacherVO(Long teacherId, String employeeNo, String fullName, boolean permitted,
        Integer permissionVersion, Integer quotaLimit, int occupiedCount, int remainingCount) {
        this.teacherId = teacherId; this.employeeNo = employeeNo; this.fullName = fullName;
        this.permitted = permitted; this.permissionVersion = permissionVersion; this.quotaLimit = quotaLimit;
        this.occupiedCount = occupiedCount; this.remainingCount = remainingCount;
    }
    public Long getTeacherId() { return teacherId; }
    public String getEmployeeNo() { return employeeNo; }
    public String getFullName() { return fullName; }
    public boolean isPermitted() { return permitted; }
    public Integer getPermissionVersion() { return permissionVersion; }
    public Integer getQuotaLimit() { return quotaLimit; }
    public int getOccupiedCount() { return occupiedCount; }
    public int getRemainingCount() { return remainingCount; }
}
