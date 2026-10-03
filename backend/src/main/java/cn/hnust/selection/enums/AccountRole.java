package cn.hnust.selection.enums;

/**
 * 系统业务角色。角色只回答“账号属于哪类用户”，不包含管理员的学院/批次数据范围；
 * 管理员具体能办理哪些事情，还要结合 account_authorization 中的能力授权判断。
 */
public enum AccountRole {
    STUDENT,
    TEACHER,
    ADMIN
}
