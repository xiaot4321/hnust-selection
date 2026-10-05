package cn.hnust.selection.vo;

/** 学生本人可见的批次摘要与按钮状态提示。 */
public class StudentBatchSummaryVO {
    private Long batchId;
    private String batchName;
    private Long academicYearId;
    private String academicYearName;
    private String batchStatus;
    private boolean supplementPlanned;
    private StudentStageSummary currentStage;
    private String preferenceStatus;
    private String matchStatus;
    private String matchReason;
    private Integer identityClassificationVersion;
    private Integer confirmedClassificationVersion;
    private StudentBatchActions actions;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getBatchName() { return batchName; }
    public void setBatchName(String batchName) { this.batchName = batchName; }
    public Long getAcademicYearId() { return academicYearId; }
    public void setAcademicYearId(Long academicYearId) { this.academicYearId = academicYearId; }
    public String getAcademicYearName() { return academicYearName; }
    public void setAcademicYearName(String academicYearName) { this.academicYearName = academicYearName; }
    public String getBatchStatus() { return batchStatus; }
    public void setBatchStatus(String batchStatus) { this.batchStatus = batchStatus; }
    public boolean isSupplementPlanned() { return supplementPlanned; }
    public void setSupplementPlanned(boolean supplementPlanned) { this.supplementPlanned = supplementPlanned; }
    public StudentStageSummary getCurrentStage() { return currentStage; }
    public void setCurrentStage(StudentStageSummary currentStage) { this.currentStage = currentStage; }
    public String getPreferenceStatus() { return preferenceStatus; }
    public void setPreferenceStatus(String preferenceStatus) { this.preferenceStatus = preferenceStatus; }
    public String getMatchStatus() { return matchStatus; }
    public void setMatchStatus(String matchStatus) { this.matchStatus = matchStatus; }
    public String getMatchReason() { return matchReason; }
    public void setMatchReason(String matchReason) { this.matchReason = matchReason; }
    public Integer getIdentityClassificationVersion() { return identityClassificationVersion; }
    public void setIdentityClassificationVersion(Integer identityClassificationVersion) { this.identityClassificationVersion = identityClassificationVersion; }
    public Integer getConfirmedClassificationVersion() { return confirmedClassificationVersion; }
    public void setConfirmedClassificationVersion(Integer confirmedClassificationVersion) { this.confirmedClassificationVersion = confirmedClassificationVersion; }
    public StudentBatchActions getActions() { return actions; }
    public void setActions(StudentBatchActions actions) { this.actions = actions; }

    public static class StudentStageSummary {
        private String stageCode;
        private String stageStatus;
        private String startAt;
        private String endAt;
        public StudentStageSummary() { }
        public StudentStageSummary(String stageCode, String stageStatus, String startAt, String endAt) {
            this.stageCode = stageCode;
            this.stageStatus = stageStatus;
            this.startAt = startAt;
            this.endAt = endAt;
        }
        public String getStageCode() { return stageCode; }
        public void setStageCode(String stageCode) { this.stageCode = stageCode; }
        public String getStageStatus() { return stageStatus; }
        public void setStageStatus(String stageStatus) { this.stageStatus = stageStatus; }
        public String getStartAt() { return startAt; }
        public void setStartAt(String startAt) { this.startAt = startAt; }
        public String getEndAt() { return endAt; }
        public void setEndAt(String endAt) { this.endAt = endAt; }
    }

    public static class StudentBatchActions {
        private boolean canConfirmIdentity;
        private boolean canSubmitPreferences;
        private boolean canWithdrawPreferences;
        private boolean canApplySupplement;
        public StudentBatchActions() { }
        public StudentBatchActions(boolean canConfirmIdentity, boolean canSubmitPreferences,
                                   boolean canWithdrawPreferences, boolean canApplySupplement) {
            this.canConfirmIdentity = canConfirmIdentity;
            this.canSubmitPreferences = canSubmitPreferences;
            this.canWithdrawPreferences = canWithdrawPreferences;
            this.canApplySupplement = canApplySupplement;
        }
        public boolean isCanConfirmIdentity() { return canConfirmIdentity; }
        public void setCanConfirmIdentity(boolean canConfirmIdentity) { this.canConfirmIdentity = canConfirmIdentity; }
        public boolean isCanSubmitPreferences() { return canSubmitPreferences; }
        public void setCanSubmitPreferences(boolean canSubmitPreferences) { this.canSubmitPreferences = canSubmitPreferences; }
        public boolean isCanWithdrawPreferences() { return canWithdrawPreferences; }
        public void setCanWithdrawPreferences(boolean canWithdrawPreferences) { this.canWithdrawPreferences = canWithdrawPreferences; }
        public boolean isCanApplySupplement() { return canApplySupplement; }
        public void setCanApplySupplement(boolean canApplySupplement) { this.canApplySupplement = canApplySupplement; }
    }
}
