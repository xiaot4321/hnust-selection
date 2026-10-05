package cn.hnust.selection.vo;

/**
 * 批次名额配置行。
 *
 * <p>每行代表一个当前符合目录条件的导师。{@code quotaLimit == null} 表示尚未建名额账户，版本为 0；
 * {@code remainingCount} 是便于展示的派生值，权威余额仍由名额表的上限和占用数共同确定。</p>
 */
public class BatchTeacherQuotaVO {
    private final Long teacherId;
    private final String employeeNo;
    private final String fullName;
    private final String profileStatus;
    private final String eligibilityBasis;
    private final Integer quotaLimit;
    private final int occupiedCount;
    private final int remainingCount;
    private final long rowVersion;
    private final boolean scopeConfigured;
    private final boolean scopeFrozen;

    public BatchTeacherQuotaVO(Long teacherId, String employeeNo, String fullName, String profileStatus,
        String eligibilityBasis, Integer quotaLimit, int occupiedCount, int remainingCount, long rowVersion,
        boolean scopeConfigured, boolean scopeFrozen) {
        this.teacherId = teacherId; this.employeeNo = employeeNo; this.fullName = fullName;
        this.profileStatus = profileStatus; this.eligibilityBasis = eligibilityBasis;
        this.quotaLimit = quotaLimit; this.occupiedCount = occupiedCount; this.remainingCount = remainingCount;
        this.rowVersion = rowVersion; this.scopeConfigured = scopeConfigured; this.scopeFrozen = scopeFrozen;
    }
    public Long getTeacherId() { return teacherId; }
    public String getEmployeeNo() { return employeeNo; }
    public String getFullName() { return fullName; }
    public String getProfileStatus() { return profileStatus; }
    public String getEligibilityBasis() { return eligibilityBasis; }
    public Integer getQuotaLimit() { return quotaLimit; }
    public int getOccupiedCount() { return occupiedCount; }
    public int getRemainingCount() { return remainingCount; }
    public long getRowVersion() { return rowVersion; }
    public boolean isScopeConfigured() { return scopeConfigured; }
    public boolean isScopeFrozen() { return scopeFrozen; }
}
