package cn.hnust.selection.controller;

import cn.hnust.selection.common.PageResult;
import cn.hnust.selection.common.Result;
import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.request.ReviewTeacherProfileRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.AdminTeacherProfileReviewService;
import cn.hnust.selection.vo.AdminTeacherProfileVersionVO;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
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
@RequestMapping("/api/admin/teacher-profile-versions")
@Validated
public class AdminTeacherProfileReviewController {
    private final AdminTeacherProfileReviewService service;
    public AdminTeacherProfileReviewController(AdminTeacherProfileReviewService service) { this.service = service; }

    @GetMapping
    public Result<PageResult<AdminTeacherProfileVersionVO>> list(
        @RequestParam("collegeId") @Positive Long collegeId,
        @RequestParam(value = "status", defaultValue = "PENDING_REVIEW") String status,
        @RequestParam(value = "pageNo", defaultValue = "1") @Min(1) int pageNo,
        @RequestParam(value = "pageSize", defaultValue = "20") @Min(1) @Max(100) int pageSize,
        @AuthenticationPrincipal AccountPrincipal actor) {
        if (!"PENDING_REVIEW".equals(status)) {
            throw new ApiException("INVALID_ARGUMENT", "导师资料审核列表仅支持 PENDING_REVIEW 状态", HttpStatus.BAD_REQUEST);
        }
        return Result.success(service.listPending(actor, collegeId, pageNo, pageSize));
    }

    @GetMapping("/{versionId}")
    public ResponseEntity<Result<AdminTeacherProfileVersionVO>> get(
        @PathVariable("versionId") @Positive Long versionId, @AuthenticationPrincipal AccountPrincipal actor) {
        AdminTeacherProfileVersionVO view = service.get(actor, versionId);
        return ResponseEntity.ok().eTag(view.getEtag()).body(Result.success(view));
    }

    @PostMapping("/{versionId}/review")
    public ResponseEntity<Result<AdminTeacherProfileVersionVO>> review(
        @PathVariable("versionId") @Positive Long versionId,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody ReviewTeacherProfileRequest request,
        @AuthenticationPrincipal AccountPrincipal actor) {
        AdminTeacherProfileVersionVO result = service.review(actor, versionId, ifMatch, request, idempotencyKey);
        return ResponseEntity.ok().eTag(result.getEtag()).body(Result.success(result));
    }
}
