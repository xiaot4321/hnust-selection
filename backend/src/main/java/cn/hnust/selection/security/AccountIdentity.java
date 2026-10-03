package cn.hnust.selection.security;

import java.io.Serializable;

/**
 * 已登录账号关联的本人身份概要，用于构造当前用户响应和提供后端本人范围标识。
 * 学生的 identifier 对应学号，导师对应工号；管理员没有 student/teacher 身份行，因此 identity 为空。
 * 此对象只承载展示和识别本人所需字段，不承载联系方式、简介、简历或其他业务资料。
 */
public class AccountIdentity implements Serializable {
    private static final long serialVersionUID = 1L;

    private final Long id;
    private final String displayName;
    private final String identifier;
    private final Long collegeId;

    public AccountIdentity(Long id, String displayName, String identifier, Long collegeId) {
        this.id = id;
        this.displayName = displayName;
        this.identifier = identifier;
        this.collegeId = collegeId;
    }

    public Long getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getIdentifier() { return identifier; }
    public Long getCollegeId() { return collegeId; }
}
