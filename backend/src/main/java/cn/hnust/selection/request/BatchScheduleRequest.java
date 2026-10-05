package cn.hnust.selection.request;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;
import java.util.List;

/**
 * 完整替换本批次启用阶段的排期。
 *
 * <p>请求必须一次提交填报、三轮和（若批次计划补选）补选的全部阶段，避免只更新部分阶段造成顺序校验缺失。
 * 字段接受带时区偏移的 ISO-8601/RFC 3339 时间；服务层转换为 UTC 瞬时后校验窗口先后关系。</p>
 */
public class BatchScheduleRequest {
    /** 必需阶段的完整排期集合，元素内部继续执行 Bean Validation。 */
    @NotEmpty @Valid private List<BatchStageScheduleRequest> stages;
    /** 本次排期变更原因；与每项阶段的修订历史和审计事件关联。 */
    @NotBlank @Size(max = 4000) private String reason;

    public List<BatchStageScheduleRequest> getStages() { return stages; }
    public void setStages(List<BatchStageScheduleRequest> stages) { this.stages = stages; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
