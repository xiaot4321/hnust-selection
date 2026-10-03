package cn.hnust.selection.security;

import java.io.Serializable;

/**
 * 当前仍有效的一条管理员能力授权摘要，作为服务端认证主体的一部分使用。
 *
 * <p>一条记录把能力代码与学院、可选批次绑定在一起，授权检查必须使用同一行上的组合范围。
 * {@code batchId == null} 表示该授权不限定学院内的批次；非空时只授权指定批次。</p>
 *
 * <p>授权依据、授权人、撤销历史等审计字段不属于鉴权快照，也不会随主体或 /auth/me 返回。</p>
 */
public class AccountAuthorization implements Serializable {
    private static final long serialVersionUID = 1L;

    private final Long collegeId;
    private final Long batchId;
    private final String capabilityCode;

    public AccountAuthorization(Long collegeId, Long batchId, String capabilityCode) {
        this.collegeId = collegeId;
        this.batchId = batchId;
        this.capabilityCode = capabilityCode;
    }

    public Long getCollegeId() { return collegeId; }
    public Long getBatchId() { return batchId; }
    public String getCapabilityCode() { return capabilityCode; }
}
