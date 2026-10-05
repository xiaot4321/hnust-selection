package cn.hnust.selection.request;

import java.util.List;

/**
 * 导师完整替换本人在本批次的允许学位类型和专业集合。
 *
 * <p>这是整体替换请求而非增量补丁。任一集合为空或缺省时，服务层按已确认规则将两类学位和本学院启用专业
 * 作为默认范围；非空时会校验重复项、枚举值及专业归属。</p>
 */
public class SetTeacherApplicationScopeRequest {
    /** 可选值为 ACADEMIC_MASTER、PROFESSIONAL_MASTER；空集合触发默认全选规则。 */
    private List<String> allowedDegreeTypes;
    /** 本批次允许报考的专业主键集合；空集合触发默认全选规则。 */
    private List<Long> majorIds;

    public List<String> getAllowedDegreeTypes() { return allowedDegreeTypes; }
    public void setAllowedDegreeTypes(List<String> allowedDegreeTypes) { this.allowedDegreeTypes = allowedDegreeTypes; }
    public List<Long> getMajorIds() { return majorIds; }
    public void setMajorIds(List<Long> majorIds) { this.majorIds = majorIds; }
}
