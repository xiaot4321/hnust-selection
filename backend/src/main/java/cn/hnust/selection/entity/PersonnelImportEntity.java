package cn.hnust.selection.entity;

/**
 * 名单导入任务的持久化概要投影。
 *
 * <p>概要按导入批次记录学院、学生或导师类型、最终任务状态，以及成功和拒绝行数；逐行错误详情
 * 由 {@link PersonnelImportRowEntity} 单独映射，一次性凭证明文不属于数据库导入记录。</p>
 */
public class PersonnelImportEntity {
    private final Long id;
    private final Long collegeId;
    private final String personType;
    private final String status;
    private final int accepted;
    private final int rejected;

    public PersonnelImportEntity(Long id, Long collegeId, String personType, String status,
                                 int accepted, int rejected) {
        this.id = id;
        this.collegeId = collegeId;
        this.personType = personType;
        this.status = status;
        this.accepted = accepted;
        this.rejected = rejected;
    }

    public Long getId() {
        return id;
    }
    public Long getCollegeId() {
        return collegeId;
    }
    public String getPersonType() {
        return personType;
    }
    public String getStatus() {
        return status;
    }
    public int getAccepted() {
        return accepted;
    }
    public int getRejected() {
        return rejected;
    }
}
