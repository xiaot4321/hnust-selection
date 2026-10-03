package cn.hnust.selection.controller;

import cn.hnust.selection.common.Result;
import cn.hnust.selection.enums.AdminAuthorizationStatusFilter;
import cn.hnust.selection.request.GrantAdminAuthorizationRequest;
import cn.hnust.selection.request.RevokeAdminAuthorizationRequest;
import cn.hnust.selection.response.AdminAccountAuthorizationResponse;
import cn.hnust.selection.response.AdminAuthorizationCommandResponse;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.AdminAccountAuthorizationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import javax.validation.constraints.Positive;
import java.util.List;

/**
 * 总管理员查询、授予和撤销其他管理员普通业务能力的 HTTP 控制器。
 *
 * <p>本类只做路由、HTTP/Bean Validation 和 Result 包装；不直接访问数据库、不判断目标账号角色、
 * 不决定授权范围，也不承载事务。上述业务规则统一交给 {@code AdminAccountAuthorizationService}，
 * 其具体实现位于 {@code service.impl}。</p>
 *
 * <p>SecurityConfig 先要求 ADMIN 角色及 ADMIN_ACCOUNT_MANAGER authority；Service 随后仍核验
 * 当前数据库中的有效身份和目标学院范围。前端隐藏页面不能替代这两层服务端校验。</p>
 */
@RestController
@RequestMapping("/api/admin/admin-accounts")
@Validated
public class AdminAccountAuthorizationController {
    private final AdminAccountAuthorizationService authorizationService;

    public AdminAccountAuthorizationController(AdminAccountAuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    /**
     * 查询指定 ADMIN 账号在一个学院范围中的授权记录。
     *
     * @param targetAccountId 目标 account 表主键
     * @param collegeId 查询学院，必填以便检查总管理员的数据范围
     * @param status ACTIVE、REVOKED 或 ALL；缺省时只返回有效授权
     * @param actor 当前请求经过安全过滤器刷新后的主体
     * @return 授权字段与历史状态，不包含密码、临时凭证或人员资料
     */
    @GetMapping("/{targetAccountId}/authorizations")
    public Result<List<AdminAccountAuthorizationResponse>> listAuthorizations(
        @PathVariable("targetAccountId") @Positive Long targetAccountId,
        @RequestParam("collegeId") @Positive Long collegeId,
        @RequestParam(defaultValue = "ACTIVE") AdminAuthorizationStatusFilter status,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(authorizationService.listAuthorizations(
            actor, targetAccountId, collegeId, status));
    }

    /**
     * 为目标管理员账号授予一个目录内的普通业务能力。
     *
     * @param targetAccountId 必须与操作者不同，且数据库中的角色确为 ADMIN
     * @param request 授权代码、学院/可选批次范围和授权依据
     * @param idempotencyKey UUID v4；相同键与请求摘要的网络重试只返回原授权回执
     * @param actor 当前总管理员主体；不能从请求体指定操作者
     * @return 授权行 ID 与 GRANTED 结果码
     */
    @PostMapping("/{targetAccountId}/authorizations")
    public Result<AdminAuthorizationCommandResponse> grant(
        @PathVariable("targetAccountId") @Positive Long targetAccountId,
        @Valid @RequestBody GrantAdminAuthorizationRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(authorizationService.grant(actor, targetAccountId, request, idempotencyKey));
    }

    /**
     * 撤销目标 ADMIN 账号的一条普通业务授权。
     *
     * @param targetAccountId 授权记录所属管理员账号
     * @param authorizationId account_authorization 主键
     * @param request 必填撤销原因
     * @param idempotencyKey UUID v4；网络重试返回同一条撤销回执
     * @param actor 当前总管理员主体
     * @return 授权行 ID 与 REVOKED 结果码
     */
    @PostMapping("/{targetAccountId}/authorizations/{authorizationId}/revoke")
    public Result<AdminAuthorizationCommandResponse> revoke(
        @PathVariable("targetAccountId") @Positive Long targetAccountId,
        @PathVariable("authorizationId") @Positive Long authorizationId,
        @Valid @RequestBody RevokeAdminAuthorizationRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(authorizationService.revoke(
            actor, targetAccountId, authorizationId, request, idempotencyKey));
    }
}
