package cn.hnust.selection.controller;

import cn.hnust.selection.common.Result;
import cn.hnust.selection.request.SubmitSupplementApplicationRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.StudentSupplementMutationService;
import cn.hnust.selection.vo.SupplementApplicationVO;
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
@RequestMapping("/api/students/me/batches/{batchId}/supplement-applications")
@Validated
public class StudentSupplementMutationController {
    private final StudentSupplementMutationService mutationService;

    public StudentSupplementMutationController(StudentSupplementMutationService mutationService) {
        this.mutationService = mutationService;
    }

    @PostMapping
    public ResponseEntity<Result<SupplementApplicationVO>> submit(
        @PathVariable("batchId") @Positive Long batchId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody SubmitSupplementApplicationRequest request,
        @AuthenticationPrincipal AccountPrincipal actor) {
        SupplementApplicationVO result = mutationService.submit(actor, batchId, request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(Result.success(result));
    }
}
