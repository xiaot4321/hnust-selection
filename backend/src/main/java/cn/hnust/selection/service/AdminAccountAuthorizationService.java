package cn.hnust.selection.service;

import cn.hnust.selection.enums.AdminAuthorizationStatusFilter;
import cn.hnust.selection.request.GrantAdminAuthorizationRequest;
import cn.hnust.selection.request.RevokeAdminAuthorizationRequest;
import cn.hnust.selection.response.AdminAccountAuthorizationResponse;
import cn.hnust.selection.response.AdminAuthorizationCommandResponse;
import cn.hnust.selection.security.AccountPrincipal;

import java.util.List;

/**
 * 管理员账号业务能力授权管理的服务接口。
 *
 * <p>Controller 只完成 HTTP 参数接收、Bean Validation 和响应包装；权限范围、目标角色、
 * 能力目录、幂等、事务及审计由此接口的 service.impl 实现负责。</p>
 */
public interface AdminAccountAuthorizationService {
    /** 查询一个 ADMIN 账号在指定学院范围内的有效授权或审计历史。 */
    List<AdminAccountAuthorizationResponse> listAuthorizations(
        AccountPrincipal actor, Long targetAccountId, Long collegeId, AdminAuthorizationStatusFilter status);

    /** 为其他 ADMIN 账号授予一个服务端已登记的普通业务能力。 */
    AdminAuthorizationCommandResponse grant(
        AccountPrincipal actor, Long targetAccountId, GrantAdminAuthorizationRequest request, String idempotencyKey);

    /** 撤销目标 ADMIN 账号的一条普通业务能力授权，并保留授权行历史。 */
    AdminAuthorizationCommandResponse revoke(
        AccountPrincipal actor, Long targetAccountId, Long authorizationId,
        RevokeAdminAuthorizationRequest request, String idempotencyKey);
}
