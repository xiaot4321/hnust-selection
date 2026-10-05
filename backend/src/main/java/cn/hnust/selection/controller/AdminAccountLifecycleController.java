package cn.hnust.selection.controller;

import cn.hnust.selection.common.Result;
import cn.hnust.selection.request.CreateAdminAccountRequest;
import cn.hnust.selection.vo.AdminAccountCredentialVO;
import cn.hnust.selection.vo.AdminAccountDirectoryItemVO;
import cn.hnust.selection.vo.PageVO;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.AdminAccountLifecycleService;
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
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Positive;

/** 总管理员创建普通管理员账号和重置临时凭证的 HTTP 入口。 */
@RestController
@RequestMapping("/api/admin/admin-accounts")
@Validated
public class AdminAccountLifecycleController {
    private final AdminAccountLifecycleService lifecycleService;

    public AdminAccountLifecycleController(AdminAccountLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    /** 查询管理员账号目录；列表字段不包含密码、临时凭证或人员资料。 */
    @GetMapping
    public Result<PageVO<AdminAccountDirectoryItemVO>> listAccounts(
        @RequestParam(value = "pageNo", defaultValue = "1") @Min(1) int pageNo,
        @RequestParam(value = "pageSize", defaultValue = "20") @Min(1) @Max(100) int pageSize,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(lifecycleService.listAccounts(actor, pageNo, pageSize));
    }

    /** 创建普通 ADMIN；新账号不自动获得任何业务能力。 */
    @PostMapping
    public Result<AdminAccountCredentialVO> create(
        @Valid @RequestBody CreateAdminAccountRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(lifecycleService.create(actor, request, idempotencyKey));
    }

    /** 为其他普通 ADMIN 重新签发一次性临时凭证；总管理员应急恢复不走此端点。 */
    @PostMapping("/{targetAccountId}/temporary-credential-reset")
    public Result<AdminAccountCredentialVO> resetTemporaryCredential(
        @PathVariable("targetAccountId") @Positive Long targetAccountId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(lifecycleService.resetTemporaryCredential(actor, targetAccountId, idempotencyKey));
    }
}
