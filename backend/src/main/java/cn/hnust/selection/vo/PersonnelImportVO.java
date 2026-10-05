package cn.hnust.selection.vo;

import java.util.List;

/**
 * 名单导入任务的响应摘要。
 *
 * <p>{@code status} 汇总整个批次，{@code acceptedCount} 与 {@code rejectedCount} 汇总逐行结果。
 * {@code rows} 在首次导入 POST 响应中包含每行处理状态和新账号一次性凭证；单独查询任务或重放
 * 幂等请求时，历史凭证明文字段均为空，不能再次取回。</p>
 */
public class PersonnelImportVO {
    private final Long importId;
    private final String personType;
    private final String status;
    private final int acceptedCount;
    private final int rejectedCount;
    private final List<PersonnelImportRowVO> rows;
    public PersonnelImportVO(Long importId, String personType, String status,
                                   int acceptedCount, int rejectedCount,
                                   List<PersonnelImportRowVO> rows) {
        this.importId = importId;
        this.personType = personType;
        this.status = status;
        this.acceptedCount = acceptedCount;
        this.rejectedCount = rejectedCount;
        this.rows = rows;
    }
    public Long getImportId() {
        return importId;
    }
    public String getPersonType() {
        return personType;
    }
    public String getStatus() {
        return status;
    }
    public int getAcceptedCount() {
        return acceptedCount;
    }
    public int getRejectedCount() {
        return rejectedCount;
    }
    public List<PersonnelImportRowVO> getRows() {
        return rows;
    }
}
