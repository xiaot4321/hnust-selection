package cn.hnust.selection.controller;

import cn.hnust.selection.common.PageResult;
import cn.hnust.selection.common.Result;
import cn.hnust.selection.request.AdjustMatchingRelationRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.AdminRelationAdjustmentService;
import cn.hnust.selection.vo.AdminMatchingRelationVO;
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
@RequestMapping("/api/admin/matching-relations")
@Validated
public class AdminRelationAdjustmentController {
    private final AdminRelationAdjustmentService service;
    public AdminRelationAdjustmentController(AdminRelationAdjustmentService service) { this.service = service; }

    @GetMapping
    public Result<PageResult<AdminMatchingRelationVO>> list(
        @RequestParam("batchId") @Positive Long batchId,
        @RequestParam(value = "pageNo", defaultValue = "1") @Min(1) int pageNo,
        @RequestParam(value = "pageSize", defaultValue = "20") @Min(1) @Max(100) int pageSize,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(service.list(actor, batchId, pageNo, pageSize));
    }

    @PostMapping("/{relationId}/adjustments")
    public ResponseEntity<Result<AdminMatchingRelationVO>> adjust(
        @PathVariable("relationId") @Positive Long relationId,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody AdjustMatchingRelationRequest request,
        @AuthenticationPrincipal AccountPrincipal actor) {
        AdminMatchingRelationVO result = service.adjust(actor, relationId, request, ifMatch, idempotencyKey);
        return ResponseEntity.ok().eTag(result.getEtag()).body(Result.success(result));
    }
}
