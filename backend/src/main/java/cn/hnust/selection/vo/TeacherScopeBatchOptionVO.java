package cn.hnust.selection.vo;

import java.time.Instant;

/**
 * 导师本人可维护范围的批次选项。
 *
 * <p>只从当前导师自己的名额账户生成，不包含其他导师信息。学院 ID 供前端读取该学院的公开专业目录；
 * 已冻结批次仍列出以便导师查看历史配置，但更新接口会由服务端拒绝。</p>
 */
public class TeacherScopeBatchOptionVO {
    private final Long batchId;
    private final Long collegeId;
    private final String collegeName;
    private final String academicYearCode;
    private final String batchCode;
    private final String name;
    private final String status;
    private final Instant fillingStartAt;
    private final boolean scopeFrozen;

    public TeacherScopeBatchOptionVO(Long batchId, Long collegeId, String collegeName, String academicYearCode,
        String batchCode, String name, String status, Instant fillingStartAt, boolean scopeFrozen) {
        this.batchId = batchId; this.collegeId = collegeId; this.collegeName = collegeName; this.academicYearCode = academicYearCode;
        this.batchCode = batchCode; this.name = name; this.status = status;
        this.fillingStartAt = fillingStartAt; this.scopeFrozen = scopeFrozen;
    }
    public Long getBatchId() { return batchId; }
    public Long getCollegeId() { return collegeId; }
    public String getCollegeName() { return collegeName; }
    public String getAcademicYearCode() { return academicYearCode; }
    public String getBatchCode() { return batchCode; }
    public String getName() { return name; }
    public String getStatus() { return status; }
    public Instant getFillingStartAt() { return fillingStartAt; }
    public boolean isScopeFrozen() { return scopeFrozen; }
}
