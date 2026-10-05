package cn.hnust.selection.vo;

import java.time.Instant;

/**
 * 批次管理目录中的摘要响应。
 *
 * <p>列表接口通过调用者授权范围限制行集；该 VO 仅包含管理页面所需的批次、学院、学年、状态和时间，
 * 不暴露内部审计字段或实体关系。</p>
 */
public class SelectionBatchSummaryVO {
    private final Long id;
    private final Long collegeId;
    private final String collegeName;
    private final Long academicYearId;
    private final String yearCode;
    private final String batchCode;
    private final String name;
    private final String status;
    private final boolean supplementPlanned;
    private final String appendReason;
    private final Instant publishedAt;
    private final Instant startedAt;
    private final long rowVersion;

    public SelectionBatchSummaryVO(Long id, Long collegeId, String collegeName, Long academicYearId,
        String yearCode, String batchCode, String name, String status, boolean supplementPlanned,
        String appendReason, Instant publishedAt, Instant startedAt, long rowVersion) {
        this.id = id; this.collegeId = collegeId; this.collegeName = collegeName;
        this.academicYearId = academicYearId; this.yearCode = yearCode; this.batchCode = batchCode;
        this.name = name; this.status = status; this.supplementPlanned = supplementPlanned;
        this.appendReason = appendReason; this.publishedAt = publishedAt; this.startedAt = startedAt;
        this.rowVersion = rowVersion;
    }
    public Long getId() { return id; }
    public Long getCollegeId() { return collegeId; }
    public String getCollegeName() { return collegeName; }
    public Long getAcademicYearId() { return academicYearId; }
    public String getYearCode() { return yearCode; }
    public String getBatchCode() { return batchCode; }
    public String getName() { return name; }
    public String getStatus() { return status; }
    public boolean isSupplementPlanned() { return supplementPlanned; }
    public String getAppendReason() { return appendReason; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getStartedAt() { return startedAt; }
    public long getRowVersion() { return rowVersion; }
}
