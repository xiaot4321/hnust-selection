package cn.hnust.selection.controller;

import cn.hnust.selection.common.Result;
import cn.hnust.selection.request.StudentIdentityConfirmationRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.StudentIdentityConfirmationService;
import cn.hnust.selection.vo.StudentIdentityConfirmationVO;
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
@RequestMapping("/api/students/me/batches/{batchId}/identity-confirmation")
@Validated
public class StudentIdentityConfirmationController {
    private final StudentIdentityConfirmationService confirmationService;

    public StudentIdentityConfirmationController(StudentIdentityConfirmationService confirmationService) {
        this.confirmationService = confirmationService;
    }

    @PostMapping
    public ResponseEntity<Result<StudentIdentityConfirmationVO>> confirm(
        @PathVariable("batchId") @Positive Long batchId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody StudentIdentityConfirmationRequest request,
        @AuthenticationPrincipal AccountPrincipal actor) {
        StudentIdentityConfirmationVO result = confirmationService.confirm(actor, batchId, request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(Result.success(result));
    }
}


