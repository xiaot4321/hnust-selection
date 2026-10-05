package cn.hnust.selection.request;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/** 一项阶段排期；时间字符串必须包含时区，不能依赖服务器默认时区解释。 */
public class BatchStageScheduleRequest {
    /** 阶段业务代码：FILLING、ROUND_1、ROUND_2、ROUND_3 或可选 SUPPLEMENT。 */
    @NotBlank @Size(max = 24) private String stageCode;
    /** 左闭右开窗口的开始时刻；服务端会转为 UTC 并要求早于结束时刻。 */
    @NotBlank private String plannedStartAt;
    /** 左闭右开窗口的结束时刻；到达该时刻后，阶段操作应被拒绝。 */
    @NotBlank private String plannedEndAt;

    public String getStageCode() { return stageCode; }
    public void setStageCode(String stageCode) { this.stageCode = stageCode; }
    public String getPlannedStartAt() { return plannedStartAt; }
    public void setPlannedStartAt(String plannedStartAt) { this.plannedStartAt = plannedStartAt; }
    public String getPlannedEndAt() { return plannedEndAt; }
    public void setPlannedEndAt(String plannedEndAt) { this.plannedEndAt = plannedEndAt; }
}
