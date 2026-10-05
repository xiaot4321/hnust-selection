package cn.hnust.selection.controller;

import cn.hnust.selection.common.Result;
import cn.hnust.selection.request.SubmitStudentPreferencesRequest;
import cn.hnust.selection.request.WithdrawStudentPreferencesRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.StudentPreferenceMutationService;
import cn.hnust.selection.vo.StudentPreferenceCommandVO;
import cn.hnust.selection.vo.StudentPreferenceWithdrawalVO;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import javax.validation.constraints.Positive;

@RestController
@RequestMapping("/api/students/me/batches/{batchId}")
@Validated
public class StudentPreferenceMutationController {
    private final StudentPreferenceMutationService mutationService;

    public StudentPreferenceMutationController(StudentPreferenceMutationService mutationService) {
        this.mutationService = mutationService;
    }

    @PostMapping("/preferences")
    public ResponseEntity<Result<StudentPreferenceCommandVO>> submit(
        @PathVariable("batchId") @Positive Long batchId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody SubmitStudentPreferencesRequest request,
        @AuthenticationPrincipal AccountPrincipal actor) {
        StudentPreferenceCommandVO result = mutationService.submit(actor, batchId, request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(Result.success(result));
    }

    @PostMapping("/preferences/withdraw")
    public Result<StudentPreferenceWithdrawalVO> withdraw(
        @PathVariable("batchId") @Positive Long batchId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody(required = false) WithdrawStudentPreferencesRequest request,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(mutationService.withdraw(actor, batchId, request, idempotencyKey));
    }
}
