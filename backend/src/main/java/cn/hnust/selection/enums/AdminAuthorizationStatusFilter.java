package cn.hnust.selection.enums;

/**
 * 管理员授权列表的查询状态筛选项。
 *
 * <p>该类型只控制列表查询，不是 account_authorization 表中的持久状态；
 * 持久记录通过 revoked_at 是否为空判定当前有效或已撤销。</p>
 */
public enum AdminAuthorizationStatusFilter {
    /** 只显示 revoked_at 为空的有效授权，也是 API 的默认值。 */
    ACTIVE,
    /** 只显示已经写入撤销时间的历史授权。 */
    REVOKED,
    /** 同时显示有效授权和已撤销授权。 */
    ALL
}
