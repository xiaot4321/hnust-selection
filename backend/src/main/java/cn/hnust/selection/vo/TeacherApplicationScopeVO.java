package cn.hnust.selection.vo;

import java.time.Instant;
import java.util.List;

/**
 * 当前导师在一个批次的招生范围响应。
 *
 * <p>未配置时版本号为 0，集合返回默认全选预览；保存后版本号递增。冻结标志和冻结时间由服务端的
 * 范围槽位/版本记录决定，前端不能通过提交字段改变。</p>
 */
public class TeacherApplicationScopeVO {
    private final Long batchId;
    private final Long teacherId;
    private final int versionNo;
    private final List<String> allowedDegreeTypes;
    private final List<Long> majorIds;
    private final String scopeSource;
    private final boolean defaultAllApplied;
    private final Instant configuredAt;
    private final Instant frozenAt;
    private final boolean frozen;

    public TeacherApplicationScopeVO(Long batchId, Long teacherId, int versionNo,
        List<String> allowedDegreeTypes, List<Long> majorIds, String scopeSource,
        boolean defaultAllApplied, Instant configuredAt, Instant frozenAt, boolean frozen) {
        this.batchId = batchId; this.teacherId = teacherId; this.versionNo = versionNo;
        this.allowedDegreeTypes = allowedDegreeTypes; this.majorIds = majorIds;
        this.scopeSource = scopeSource; this.defaultAllApplied = defaultAllApplied;
        this.configuredAt = configuredAt; this.frozenAt = frozenAt; this.frozen = frozen;
    }
    public Long getBatchId() { return batchId; }
    public Long getTeacherId() { return teacherId; }
    public int getVersionNo() { return versionNo; }
    public List<String> getAllowedDegreeTypes() { return allowedDegreeTypes; }
    public List<Long> getMajorIds() { return majorIds; }
    public String getScopeSource() { return scopeSource; }
    public boolean isDefaultAllApplied() { return defaultAllApplied; }
    public Instant getConfiguredAt() { return configuredAt; }
    public Instant getFrozenAt() { return frozenAt; }
    public boolean isFrozen() { return frozen; }
}
