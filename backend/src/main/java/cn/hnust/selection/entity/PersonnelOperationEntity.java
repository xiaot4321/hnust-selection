package cn.hnust.selection.entity;

/**
 * 人员管理命令幂等记录的查询投影。
 *
 * <p>{@code fingerprint} 是请求内容摘要，用来拒绝把同一幂等键用于不同参数；{@code resultCode}
 * 标记命令是否完成。对象不包含原始请求中的临时凭证明文。</p>
 */
public class PersonnelOperationEntity {
    private final Long id;
    private final String fingerprint;
    private final String resultCode;

    public PersonnelOperationEntity(Long id, String fingerprint, String resultCode) {
        this.id = id;
        this.fingerprint = fingerprint;
        this.resultCode = resultCode;
    }

    public Long getId() {
        return id;
    }
    public String getFingerprint() {
        return fingerprint;
    }
    public String getResultCode() {
        return resultCode;
    }
}
