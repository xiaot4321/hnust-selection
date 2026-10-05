package cn.hnust.selection.vo;

import java.util.List;

/** 批次管理/审计工作台的冻结分母、结果来源拆分、补选和名额汇总。 */
public class BatchStatisticsVO {
    private final Long batchId;
    private final Long frozenDenominator;
    private final long matchedCount;
    private final long currentUnmatchedCount;
    private final long normalPreferenceUnmatchedCount;
    private final long notSubmittedCount;
    private final long allRemainingPreferencesSkippedCount;
    private final long identityCorrectionUnmatchedCount;
    private final long relationCorrectionUnmatchedCount;
    private final long batchCancelledUnmatchedCount;
    private final long supplementAdmittedCount;
    private final Long supplementStillUnmatchedCount;
    private final long quotaLimit;
    private final long occupiedQuota;
    private final List<RoundStatistics> rounds;

    public BatchStatisticsVO(Long batchId, Long frozenDenominator, long matchedCount, long currentUnmatchedCount,
        long normalPreferenceUnmatchedCount, long notSubmittedCount, long allRemainingPreferencesSkippedCount,
        long identityCorrectionUnmatchedCount, long relationCorrectionUnmatchedCount,
        long batchCancelledUnmatchedCount, long supplementAdmittedCount, Long supplementStillUnmatchedCount,
        long quotaLimit, long occupiedQuota, List<RoundStatistics> rounds) {
        this.batchId = batchId;
        this.frozenDenominator = frozenDenominator;
        this.matchedCount = matchedCount;
        this.currentUnmatchedCount = currentUnmatchedCount;
        this.normalPreferenceUnmatchedCount = normalPreferenceUnmatchedCount;
        this.notSubmittedCount = notSubmittedCount;
        this.allRemainingPreferencesSkippedCount = allRemainingPreferencesSkippedCount;
        this.identityCorrectionUnmatchedCount = identityCorrectionUnmatchedCount;
        this.relationCorrectionUnmatchedCount = relationCorrectionUnmatchedCount;
        this.batchCancelledUnmatchedCount = batchCancelledUnmatchedCount;
        this.supplementAdmittedCount = supplementAdmittedCount;
        this.supplementStillUnmatchedCount = supplementStillUnmatchedCount;
        this.quotaLimit = quotaLimit;
        this.occupiedQuota = occupiedQuota;
        this.rounds = rounds;
    }

    public Long getBatchId() { return batchId; }
    /** Null until the eligibility roster was frozen at filling-window open. */
    public Long getFrozenDenominator() { return frozenDenominator; }
    public long getMatchedCount() { return matchedCount; }
    public long getCurrentUnmatchedCount() { return currentUnmatchedCount; }
    public long getNormalPreferenceUnmatchedCount() { return normalPreferenceUnmatchedCount; }
    public long getNotSubmittedCount() { return notSubmittedCount; }
    public long getAllRemainingPreferencesSkippedCount() { return allRemainingPreferencesSkippedCount; }
    public long getIdentityCorrectionUnmatchedCount() { return identityCorrectionUnmatchedCount; }
    public long getRelationCorrectionUnmatchedCount() { return relationCorrectionUnmatchedCount; }
    public long getBatchCancelledUnmatchedCount() { return batchCancelledUnmatchedCount; }
    public long getSupplementAdmittedCount() { return supplementAdmittedCount; }
    /** Null until an arranged supplement window closes. */
    public Long getSupplementStillUnmatchedCount() { return supplementStillUnmatchedCount; }
    public long getQuotaLimit() { return quotaLimit; }
    public long getOccupiedQuota() { return occupiedQuota; }
    public long getRemainingQuota() { return Math.max(0L, quotaLimit - occupiedQuota); }
    public List<RoundStatistics> getRounds() { return rounds; }

    public static class RoundStatistics {
        private final int roundNo;
        private final String stageStatus;
        private final long pendingApplicationCount;
        private final long admittedCount;
        private final long notAdmittedCount;
        private final long skippedStudentCount;
        private final long cancelledApplicationCount;

        public RoundStatistics(int roundNo, String stageStatus, long pendingApplicationCount, long admittedCount,
            long notAdmittedCount, long skippedStudentCount, long cancelledApplicationCount) {
            this.roundNo = roundNo;
            this.stageStatus = stageStatus;
            this.pendingApplicationCount = pendingApplicationCount;
            this.admittedCount = admittedCount;
            this.notAdmittedCount = notAdmittedCount;
            this.skippedStudentCount = skippedStudentCount;
            this.cancelledApplicationCount = cancelledApplicationCount;
        }

        public int getRoundNo() { return roundNo; }
        public String getStageStatus() { return stageStatus; }
        public long getPendingApplicationCount() { return pendingApplicationCount; }
        public long getAdmittedCount() { return admittedCount; }
        public long getNotAdmittedCount() { return notAdmittedCount; }
        public long getSkippedStudentCount() { return skippedStudentCount; }
        public long getCancelledApplicationCount() { return cancelledApplicationCount; }
    }
}
