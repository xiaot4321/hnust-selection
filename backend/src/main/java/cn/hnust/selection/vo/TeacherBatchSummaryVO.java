package cn.hnust.selection.vo;

import java.util.List;

/** 导师本人批次名额和锁定关系汇总。 */
public class TeacherBatchSummaryVO {
    private Long batchId;
    private String batchName;
    private String batchStatus;
    private String currentStage;
    private Integer quotaLimit;
    private Integer occupiedCount;
    private Integer remainingCount;
    private List<MatchedStudent> matchedStudents;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getBatchName() { return batchName; }
    public void setBatchName(String batchName) { this.batchName = batchName; }
    public String getBatchStatus() { return batchStatus; }
    public void setBatchStatus(String batchStatus) { this.batchStatus = batchStatus; }
    public String getCurrentStage() { return currentStage; }
    public void setCurrentStage(String currentStage) { this.currentStage = currentStage; }
    public Integer getQuotaLimit() { return quotaLimit; }
    public void setQuotaLimit(Integer quotaLimit) { this.quotaLimit = quotaLimit; }
    public Integer getOccupiedCount() { return occupiedCount; }
    public void setOccupiedCount(Integer occupiedCount) { this.occupiedCount = occupiedCount; }
    public Integer getRemainingCount() { return remainingCount; }
    public void setRemainingCount(Integer remainingCount) { this.remainingCount = remainingCount; }
    public List<MatchedStudent> getMatchedStudents() { return matchedStudents; }
    public void setMatchedStudents(List<MatchedStudent> matchedStudents) { this.matchedStudents = matchedStudents; }

    public static class MatchedStudent {
        private Long relationId;
        private String studentNo;
        private String fullName;
        private String majorName;
        private String degreeType;
        private String source;
        private String lockedAt;
        public Long getRelationId() { return relationId; }
        public void setRelationId(Long relationId) { this.relationId = relationId; }
        public String getStudentNo() { return studentNo; }
        public void setStudentNo(String studentNo) { this.studentNo = studentNo; }
        public String getFullName() { return fullName; }
        public void setFullName(String fullName) { this.fullName = fullName; }
        public String getMajorName() { return majorName; }
        public void setMajorName(String majorName) { this.majorName = majorName; }
        public String getDegreeType() { return degreeType; }
        public void setDegreeType(String degreeType) { this.degreeType = degreeType; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public String getLockedAt() { return lockedAt; }
        public void setLockedAt(String lockedAt) { this.lockedAt = lockedAt; }
    }
}
