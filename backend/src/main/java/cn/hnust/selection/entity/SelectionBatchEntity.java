package cn.hnust.selection.entity;

import java.sql.Timestamp;

/**
 * 管理端批次资源在事务服务内使用的数据库投影。
 *
 * <p>该对象只承载从 {@code selection_batch} 读取的状态，不直接作为 API 响应返回。
 * 写操作通常通过仓储的 {@code FOR UPDATE} 查询取得此投影，再以行锁保护生命周期变更。</p>
 */
public class SelectionBatchEntity {
    /** 数据库主键，也是批次、阶段和名额关联的业务引用。 */
    private final Long id;
    /** 批次所属学院；授权判断使用数据库中的这个真实归属。 */
    private final Long collegeId;
    /** 批次归属学年；创建时对应学年行会被锁定以串行检查追加批次。 */
    private final Long academicYearId;
    /** 学院/学年范围内由业务方填写的批次编号。 */
    private final String batchCode;
    /** 面向用户展示的批次名称。 */
    private final String name;
    /** 生命周期状态，例如 DRAFT、SCHEDULED、ACTIVE；只能由服务端命令转换。 */
    private final String status;
    /** 发布前确定是否设置独立补选阶段；发布后不得临时追加补选。 */
    private final boolean supplementPlanned;
    /** 同一学院和学年创建追加批次时记录的原因。 */
    private final String appendReason;
    /** 创建草稿的管理员账号 ID，系统自动冻结默认范围时作为来源记录。 */
    private final Long createdBy;
    /** 发布的数据库 UTC 时间；未发布时为空。 */
    private final Timestamp publishedAt;
    /** 管理员启动批次的数据库 UTC 时间；未启动时为空。 */
    private final Timestamp startedAt;
    /** 学生名单冻结时间；该字段与名单插入处于同一数据库事务。 */
    private final Timestamp frozenRosterAt;
    /** 乐观并发版本；更新批次字段或阶段配置时与 If-Match 配合使用。 */
    private final long rowVersion;

    public SelectionBatchEntity(Long id, Long collegeId, Long academicYearId, String batchCode,
        String name, String status, boolean supplementPlanned, String appendReason, Long createdBy,
        Timestamp publishedAt, Timestamp startedAt, Timestamp frozenRosterAt, long rowVersion) {
        this.id = id; this.collegeId = collegeId; this.academicYearId = academicYearId;
        this.batchCode = batchCode; this.name = name; this.status = status;
        this.supplementPlanned = supplementPlanned; this.appendReason = appendReason;
        this.createdBy = createdBy; this.publishedAt = publishedAt; this.startedAt = startedAt;
        this.frozenRosterAt = frozenRosterAt; this.rowVersion = rowVersion;
    }
    public Long getId() { return id; }
    public Long getCollegeId() { return collegeId; }
    public Long getAcademicYearId() { return academicYearId; }
    public String getBatchCode() { return batchCode; }
    public String getName() { return name; }
    public String getStatus() { return status; }
    public boolean isSupplementPlanned() { return supplementPlanned; }
    public String getAppendReason() { return appendReason; }
    public Long getCreatedBy() { return createdBy; }
    public Timestamp getPublishedAt() { return publishedAt; }
    public Timestamp getStartedAt() { return startedAt; }
    public Timestamp getFrozenRosterAt() { return frozenRosterAt; }
    public long getRowVersion() { return rowVersion; }
}
