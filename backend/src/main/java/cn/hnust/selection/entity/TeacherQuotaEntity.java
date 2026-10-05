package cn.hnust.selection.entity;

/**
 * 导师名额账户在事务服务内使用的数据库投影。
 *
 * <p>名额按批次和导师分别保存；{@code quotaLimit} 是上限，{@code occupiedCount} 是已锁定关系数，
 * 不能把剩余数保存为独立事实。调整时仓储锁定该行并以 rowVersion 条件更新。</p>
 */
public class TeacherQuotaEntity {
    /** 名额账户行主键。 */
    private final Long id;
    /** 名额生效的批次。 */
    private final Long batchId;
    /** 配额所属导师。 */
    private final Long teacherId;
    /** 设置名额时记录的导师资格依据快照，便于审计资格来源。 */
    private final String eligibilityBasis;
    /** 管理员设置的名额上限。 */
    private final Integer quotaLimit;
    /** 已建立且占用名额的关系数；更新上限不得低于此值。 */
    private final int occupiedCount;
    /** 名额行的并发版本号。 */
    private final long rowVersion;

    public TeacherQuotaEntity(Long id, Long batchId, Long teacherId, String eligibilityBasis,
        Integer quotaLimit, int occupiedCount, long rowVersion) {
        this.id = id; this.batchId = batchId; this.teacherId = teacherId;
        this.eligibilityBasis = eligibilityBasis; this.quotaLimit = quotaLimit;
        this.occupiedCount = occupiedCount; this.rowVersion = rowVersion;
    }
    public Long getId() { return id; }
    public Long getBatchId() { return batchId; }
    public Long getTeacherId() { return teacherId; }
    public String getEligibilityBasis() { return eligibilityBasis; }
    public Integer getQuotaLimit() { return quotaLimit; }
    public int getOccupiedCount() { return occupiedCount; }
    public long getRowVersion() { return rowVersion; }
}
