package cn.hnust.selection.vo;

import java.time.Instant;

/**
 * 一项批次阶段及其计划/有效时间。
 *
 * <p>计划时间用于发布前配置；发布后复制为有效时间并供服务端 UTC 窗口判定使用。
 * 阶段状态由服务端驱动，客户端展示它但不能直接修改。</p>
 */
public class BatchStageVO {
    private final Long id;
    private final String stageCode;
    private final int stageOrder;
    private final String status;
    private final String closeReason;
    private final Instant plannedStartAt;
    private final Instant plannedEndAt;
    private final Instant effectiveStartAt;
    private final Instant effectiveEndAt;

    public BatchStageVO(Long id, String stageCode, int stageOrder, String status,
        String closeReason, Instant plannedStartAt, Instant plannedEndAt, Instant effectiveStartAt, Instant effectiveEndAt) {
        this.id = id; this.stageCode = stageCode; this.stageOrder = stageOrder; this.status = status;
        this.closeReason = closeReason;
        this.plannedStartAt = plannedStartAt; this.plannedEndAt = plannedEndAt;
        this.effectiveStartAt = effectiveStartAt; this.effectiveEndAt = effectiveEndAt;
    }
    public Long getId() { return id; }
    public String getStageCode() { return stageCode; }
    public int getStageOrder() { return stageOrder; }
    public String getStatus() { return status; }
    public String getCloseReason() { return closeReason; }
    public Instant getPlannedStartAt() { return plannedStartAt; }
    public Instant getPlannedEndAt() { return plannedEndAt; }
    public Instant getEffectiveStartAt() { return effectiveStartAt; }
    public Instant getEffectiveEndAt() { return effectiveEndAt; }
}
