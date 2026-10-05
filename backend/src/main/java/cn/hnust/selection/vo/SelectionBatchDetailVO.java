package cn.hnust.selection.vo;

import java.util.List;

/**
 * 管理端批次详情响应。
 *
 * <p>批次基本信息和阶段计划分开组织，调用方可用批次 rowVersion 更新元数据或整组排期，
 * 不需要接触数据库实体或生命周期内部字段。</p>
 */
public class SelectionBatchDetailVO {
    private final SelectionBatchSummaryVO batch;
    private final List<BatchStageVO> stages;

    public SelectionBatchDetailVO(SelectionBatchSummaryVO batch, List<BatchStageVO> stages) {
        this.batch = batch; this.stages = stages;
    }
    public SelectionBatchSummaryVO getBatch() { return batch; }
    public List<BatchStageVO> getStages() { return stages; }
}
