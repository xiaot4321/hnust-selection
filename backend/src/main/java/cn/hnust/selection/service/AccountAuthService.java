package cn.hnust.selection.service;

import cn.hnust.selection.security.AccountPrincipal;

/**
 * 账号认证与密码凭证生命周期的服务契约。
 * Controller、认证 Provider 和请求过滤器依赖此接口，不直接依赖具体实现；
 * 数据库访问、密码编码和改密事务由 service.impl.AccountAuthServiceImpl 负责。
 */
public interface AccountAuthService {
    /**
     * 使用登录标识和正式密码或有效临时凭证验证账号。
     * 成功结果必须以数据库中的角色、身份关联及当前有效授权构造，不能采用客户端提交的角色信息。
     *
     * @param loginIdentifier 学号、工号或管理员登录标识
     * @param credential 正式密码或一次性临时凭证
     * @return 不包含凭证明文或密码哈希的认证主体
     */
    AccountPrincipal authenticate(String loginIdentifier, String credential);

    /**
     * 按账号主键重新读取当前身份、账号状态、版本号和授权，用于刷新 Session 中的主体。
     * 若账号已不存在返回 null，由请求过滤器销毁对应会话。
     *
     * @param accountId Session 主体记录的账号主键
     * @param temporaryCredentialLogin 是否由临时凭证建立本次会话
     */
    AccountPrincipal refreshPrincipal(Long accountId, boolean temporaryCredentialLogin);

    /**
     * 校验当前凭证并更新正式密码，返回从更新后数据库记录重新构造的主体。
     * 实现需在同一事务内完成临时凭证消费和密码更新，不能让其中一步单独提交。
     */
    AccountPrincipal changePassword(AccountPrincipal principal, String currentCredential, String newPassword);
}
