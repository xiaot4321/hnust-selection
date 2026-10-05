package cn.hnust.selection.controller;

import cn.hnust.selection.common.PageResult;
import cn.hnust.selection.common.Result;
import cn.hnust.selection.request.DecideTeacherApplicationRequest;
import cn.hnust.selection.request.BulkDecideTeacherApplicationsRequest;
import cn.hnust.selection.request.SendTeacherApplicationNoticeRequest;
import cn.hnust.selection.request.UpdateTeacherProfileRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.TeacherWorkspaceService;
import cn.hnust.selection.vo.TeacherApplicationVO;
import cn.hnust.selection.vo.TeacherBatchSummaryVO;
import cn.hnust.selection.vo.TeacherDecisionVO;
import cn.hnust.selection.vo.TeacherNoticeVO;
import cn.hnust.selection.vo.TeacherProfileVO;
import cn.hnust.selection.vo.TeacherSupplementApplicationVO;
import cn.hnust.selection.vo.TeacherBulkDecisionAcceptedVO;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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

/** 导师本人工作台接口；路径不含可由客户端指定的 teacherId。 */
@RestController
@RequestMapping("/api/teachers/me")
@Validated
public class TeacherWorkspaceController {
    private final TeacherWorkspaceService service;
    public TeacherWorkspaceController(TeacherWorkspaceService service) { this.service = service; }

    @GetMapping("/profile")
    public ResponseEntity<Result<TeacherProfileVO>> profile(@AuthenticationPrincipal AccountPrincipal actor) {
        TeacherProfileVO profile = service.getProfile(actor);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, profile.getEtag()).body(Result.success(profile));
    }

    @PatchMapping("/profile")
    public ResponseEntity<Result<TeacherProfileVO>> updateProfile(
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @Valid @RequestBody UpdateTeacherProfileRequest request,
        @AuthenticationPrincipal AccountPrincipal actor) {
        TeacherProfileVO profile = service.updateProfile(actor, ifMatch, request);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, profile.getEtag()).body(Result.success(profile));
    }

    @GetMapping("/batches/{batchId}/rounds/{roundNo}/applications")
    public Result<PageResult<TeacherApplicationVO>> roundApplications(
        @PathVariable("batchId") @Positive Long batchId,
        @PathVariable("roundNo") @Min(1) @Max(3) int roundNo,
        @RequestParam(value = "pageNo", defaultValue = "1") @Min(1) int pageNo,
        @RequestParam(value = "pageSize", defaultValue = "20") @Min(1) @Max(100) int pageSize,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(service.roundApplications(actor, batchId, roundNo, pageNo, pageSize));
    }

    @PostMapping("/batches/{batchId}/round-applications/{applicationId}/decision")
    public Result<TeacherDecisionVO> decideRound(
        @PathVariable("batchId") @Positive Long batchId,
        @PathVariable("applicationId") @Positive Long applicationId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody DecideTeacherApplicationRequest request,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(service.decideRoundApplication(actor, batchId, applicationId, request, idempotencyKey));
    }

    @PostMapping("/batches/{batchId}/rounds/{roundNo}/decisions:batch")
    public ResponseEntity<Result<TeacherBulkDecisionAcceptedVO>> decideRoundBatch(
        @PathVariable("batchId") @Positive Long batchId,
        @PathVariable("roundNo") @Min(1) @Max(3) int roundNo,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody BulkDecideTeacherApplicationsRequest request,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Result.success(
            service.decideRoundApplicationsBulk(actor, batchId, roundNo, request, idempotencyKey)));
    }

    @GetMapping("/batches/{batchId}/supplement-applications")
    public Result<PageResult<TeacherSupplementApplicationVO>> supplementApplications(
        @PathVariable("batchId") @Positive Long batchId,
        @RequestParam(value = "pageNo", defaultValue = "1") @Min(1) int pageNo,
        @RequestParam(value = "pageSize", defaultValue = "20") @Min(1) @Max(100) int pageSize,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(service.supplementApplications(actor, batchId, pageNo, pageSize));
    }

    @PostMapping("/batches/{batchId}/supplement-applications/{applicationId}/decision")
    public Result<TeacherDecisionVO> decideSupplement(
        @PathVariable("batchId") @Positive Long batchId,
        @PathVariable("applicationId") @Positive Long applicationId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody DecideTeacherApplicationRequest request,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(service.decideSupplementApplication(actor, batchId, applicationId, request, idempotencyKey));
    }

    @PostMapping("/batches/{batchId}/supplement-decisions:batch")
    public ResponseEntity<Result<TeacherBulkDecisionAcceptedVO>> decideSupplementBatch(
        @PathVariable("batchId") @Positive Long batchId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody BulkDecideTeacherApplicationsRequest request,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Result.success(
            service.decideSupplementApplicationsBulk(actor, batchId, request, idempotencyKey)));
    }

    @GetMapping("/batches/{batchId}/summary")
    public Result<TeacherBatchSummaryVO> summary(@PathVariable("batchId") @Positive Long batchId,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(service.summary(actor, batchId));
    }

    @PostMapping("/batches/{batchId}/application-notices")
    public Result<TeacherNoticeVO> sendNotice(@PathVariable("batchId") @Positive Long batchId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody SendTeacherApplicationNoticeRequest request,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(service.sendNotice(actor, batchId, request, idempotencyKey));
    }
}
