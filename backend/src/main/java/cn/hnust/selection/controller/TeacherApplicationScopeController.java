package cn.hnust.selection.controller;

import cn.hnust.selection.common.Result;
import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.request.SetTeacherApplicationScopeRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.SelectionBatchManagementService;
import cn.hnust.selection.vo.TeacherApplicationScopeVO;
import cn.hnust.selection.vo.TeacherScopeBatchOptionVO;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import javax.validation.constraints.Positive;
import java.util.List;

/**
 * 导师本人维护本批次可报范围的 HTTP 入口。
 *
 * <p>路径没有 teacherId 参数：服务层必须从当前认证主体解析导师身份，从接口结构上避免替他人修改范围。
 * 更新使用强 If-Match 版本标签；具体冻结判断和范围目录校验都由服务层完成。</p>
 */
@RestController
@RequestMapping("/api/teachers/me")
@Validated
public class TeacherApplicationScopeController {
    private final SelectionBatchManagementService service;

    public TeacherApplicationScopeController(SelectionBatchManagementService service) { this.service = service; }

    /** 返回当前导师名额关联的批次选项，供本人范围页面选择目标批次。 */
    @GetMapping("/application-scopes/batches")
    public Result<List<TeacherScopeBatchOptionVO>> batches(@AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(service.listTeacherScopeBatches(actor));
    }

    /** 读取本人范围版本；未配置时由服务层返回默认全选预览。 */
    @GetMapping("/batches/{batchId}/application-scope")
    public ResponseEntity<Result<TeacherApplicationScopeVO>> get(
        @PathVariable("batchId") @Positive Long batchId,
        @AuthenticationPrincipal AccountPrincipal actor) {
        TeacherApplicationScopeVO result = service.getTeacherScope(actor, batchId);
        return ResponseEntity.ok().eTag("scope-" + result.getVersionNo()).body(Result.success(result));
    }

    /** 完整替换本人招生范围；成功后 ETag 表示新版本号。 */
    @PutMapping("/batches/{batchId}/application-scope")
    public ResponseEntity<Result<TeacherApplicationScopeVO>> update(
        @PathVariable("batchId") @Positive Long batchId, @Valid @RequestBody SetTeacherApplicationScopeRequest request,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @AuthenticationPrincipal AccountPrincipal actor) {
        int expectedVersion = parseVersion(ifMatch);
        TeacherApplicationScopeVO result = service.setTeacherScope(actor, batchId, request, expectedVersion);
        return ResponseEntity.ok().eTag("scope-" + result.getVersionNo()).body(Result.success(result));
    }

    /** 解析 {@code "scope-N"} 强 ETag；0 表示此前尚无持久化范围版本。 */
    private static int parseVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.trim().isEmpty() || "*".equals(ifMatch.trim())) {
            throw new ApiException("PRECONDITION_REQUIRED", "修改前请读取范围版本并发送 If-Match", HttpStatus.PRECONDITION_REQUIRED);
        }
        String value = ifMatch.trim();
        if (value.startsWith("W/")) throw new ApiException("INVALID_ARGUMENT", "If-Match 必须是强版本标签", HttpStatus.BAD_REQUEST);
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        if (!value.startsWith("scope-")) throw new ApiException("INVALID_ARGUMENT", "If-Match 范围版本格式不正确", HttpStatus.BAD_REQUEST);
        try { return Integer.parseInt(value.substring(6)); }
        catch (NumberFormatException ex) { throw new ApiException("INVALID_ARGUMENT", "If-Match 范围版本格式不正确", HttpStatus.BAD_REQUEST); }
    }
}
