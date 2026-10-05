package cn.hnust.selection.entity;

import java.sql.Timestamp;

/**
 * 导师本批次可报范围版本在事务服务内使用的数据库投影。
 *
 * <p>范围采用只追加版本的方式保存，冻结指针指向最终用于学生志愿和后续轮次校验的版本；
 * 避免直接覆盖旧专业集合而丢失审计历史。</p>
 */
public class TeacherScopeVersionEntity {
    /** 范围版本行主键，专业快照行以此 ID 关联。 */
    private final Long id;
    /** 范围适用的批次。 */
    private final Long batchId;
    /** 配置范围的导师。 */
    private final Long teacherId;
    /** 同一导师、同一批次内递增的版本序号；0 表示尚未落库配置。 */
    private final int versionNo;
    /** 学位类型位掩码：1 学硕、2 专硕、3 两者均允许。 */
    private final int allowedDegreeMask;
    /** 创建该范围版本的账号；系统默认版本使用批次创建账号作为来源关联。 */
    private final Long configuredBy;
    /** 范围版本创建时间，使用数据库 UTC。 */
    private final Timestamp configuredAt;
    /** 该版本被冻结的时间；冻结后不可再编辑。 */
    private final Timestamp frozenAt;
    /** 版本来源，例如导师手动配置或系统默认全选。 */
    private final String scopeSource;
    /** 标识该版本是否按“不完整配置默认全选”规则生成。 */
    private final boolean defaultAllApplied;

    public TeacherScopeVersionEntity(Long id, Long batchId, Long teacherId, int versionNo,
        int allowedDegreeMask, Long configuredBy, Timestamp configuredAt, Timestamp frozenAt,
        String scopeSource, boolean defaultAllApplied) {
        this.id = id; this.batchId = batchId; this.teacherId = teacherId; this.versionNo = versionNo;
        this.allowedDegreeMask = allowedDegreeMask; this.configuredBy = configuredBy;
        this.configuredAt = configuredAt; this.frozenAt = frozenAt; this.scopeSource = scopeSource;
        this.defaultAllApplied = defaultAllApplied;
    }
    public Long getId() { return id; }
    public Long getBatchId() { return batchId; }
    public Long getTeacherId() { return teacherId; }
    public int getVersionNo() { return versionNo; }
    public int getAllowedDegreeMask() { return allowedDegreeMask; }
    public Long getConfiguredBy() { return configuredBy; }
    public Timestamp getConfiguredAt() { return configuredAt; }
    public Timestamp getFrozenAt() { return frozenAt; }
    public String getScopeSource() { return scopeSource; }
    public boolean isDefaultAllApplied() { return defaultAllApplied; }
}
