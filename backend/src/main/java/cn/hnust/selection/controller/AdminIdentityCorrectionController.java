package cn.hnust.selection.controller;

import cn.hnust.selection.common.PageResult;
import cn.hnust.selection.common.Result;
import cn.hnust.selection.request.ReviewIdentityCorrectionRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.StudentIdentityCorrectionService;
import cn.hnust.selection.vo.AdminIdentityCorrectionVO;
import cn.hnust.selection.vo.StudentIdentityCorrectionCommandVO;
import org.springframework.http.ResponseEntity;
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

@RestController
@RequestMapping("/api/admin/identity-correction-requests")
@Validated
public class AdminIdentityCorrectionController {
    private final StudentIdentityCorrectionService service;

    public AdminIdentityCorrectionController(StudentIdentityCorrectionService service) { this.service = service; }

    @GetMapping
    public Result<PageResult<AdminIdentityCorrectionVO>> list(
        @RequestParam("collegeId") @Positive Long collegeId,
        @RequestParam(value = "status", defaultValue = "PENDING") String status,
        @RequestParam(value = "pageNo", defaultValue = "1") @Min(1) int pageNo,
        @RequestParam(value = "pageSize", defaultValue = "20") @Min(1) @Max(100) int pageSize,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(service.listAdminRequests(actor, collegeId, status, pageNo, pageSize));
    }

    @PostMapping("/{requestId}/decision")
    public ResponseEntity<Result<StudentIdentityCorrectionCommandVO>> decide(
        @PathVariable("requestId") @Positive Long requestId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody ReviewIdentityCorrectionRequest request,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return ResponseEntity.ok(Result.success(service.decideAdminRequest(actor, requestId, request, idempotencyKey)));
    }
}
