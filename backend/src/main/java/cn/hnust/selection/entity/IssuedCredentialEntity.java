package cn.hnust.selection.entity;

/** 临时凭证写入后的数据库回执字段；不包含凭证明文，无到期时间时 expiresAt 为 null。 */
public class IssuedCredentialEntity {
    private final Long id;
    private final String expiresAt;

    public IssuedCredentialEntity(Long id, String expiresAt) {
        this.id = id;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }
    public String getExpiresAt() {
        return expiresAt;
    }
}
