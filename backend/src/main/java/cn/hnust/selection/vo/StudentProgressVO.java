package cn.hnust.selection.vo;

import java.util.ArrayList;
import java.util.List;

public class StudentProgressVO {
    private String batchStatus;
    private StudentBatchSummaryVO.StudentStageSummary currentStage;
    private String preferenceStatus;
    private String matchStatus;
    private String matchReason;
    private List<StudentRoundProgressVO> rounds = new ArrayList<StudentRoundProgressVO>();
    private CurrentRelation currentRelation;

    public String getBatchStatus() { return batchStatus; }
    public void setBatchStatus(String batchStatus) { this.batchStatus = batchStatus; }
    public StudentBatchSummaryVO.StudentStageSummary getCurrentStage() { return currentStage; }
    public void setCurrentStage(StudentBatchSummaryVO.StudentStageSummary currentStage) { this.currentStage = currentStage; }
    public String getPreferenceStatus() { return preferenceStatus; }
    public void setPreferenceStatus(String preferenceStatus) { this.preferenceStatus = preferenceStatus; }
    public String getMatchStatus() { return matchStatus; }
    public void setMatchStatus(String matchStatus) { this.matchStatus = matchStatus; }
    public String getMatchReason() { return matchReason; }
    public void setMatchReason(String matchReason) { this.matchReason = matchReason; }
    public List<StudentRoundProgressVO> getRounds() { return rounds; }
    public void setRounds(List<StudentRoundProgressVO> rounds) { this.rounds = rounds; }
    public CurrentRelation getCurrentRelation() { return currentRelation; }
    public void setCurrentRelation(CurrentRelation currentRelation) { this.currentRelation = currentRelation; }

    public static class StudentRoundProgressVO {
        private Integer roundNo;
        private Integer preferenceOrder;
        private Long teacherId;
        private String teacherName;
        private String state;
        private boolean resultPublished;
        private String processedAt;
        public Integer getRoundNo() { return roundNo; }
        public void setRoundNo(Integer roundNo) { this.roundNo = roundNo; }
        public Integer getPreferenceOrder() { return preferenceOrder; }
        public void setPreferenceOrder(Integer preferenceOrder) { this.preferenceOrder = preferenceOrder; }
        public Long getTeacherId() { return teacherId; }
        public void setTeacherId(Long teacherId) { this.teacherId = teacherId; }
        public String getTeacherName() { return teacherName; }
        public void setTeacherName(String teacherName) { this.teacherName = teacherName; }
        public String getState() { return state; }
        public void setState(String state) { this.state = state; }
        public boolean isResultPublished() { return resultPublished; }
        public void setResultPublished(boolean resultPublished) { this.resultPublished = resultPublished; }
        public String getProcessedAt() { return processedAt; }
        public void setProcessedAt(String processedAt) { this.processedAt = processedAt; }
    }

    public static class CurrentRelation {
        private Long teacherId;
        private String teacherName;
        private String source;
        private String lockedAt;
        public Long getTeacherId() { return teacherId; }
        public void setTeacherId(Long teacherId) { this.teacherId = teacherId; }
        public String getTeacherName() { return teacherName; }
        public void setTeacherName(String teacherName) { this.teacherName = teacherName; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public String getLockedAt() { return lockedAt; }
        public void setLockedAt(String lockedAt) { this.lockedAt = lockedAt; }
    }
}
