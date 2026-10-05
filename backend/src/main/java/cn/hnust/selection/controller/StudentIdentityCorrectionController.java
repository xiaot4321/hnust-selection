package cn.hnust.selection.controller;

import cn.hnust.selection.common.PageResult;
import cn.hnust.selection.common.Result;
import cn.hnust.selection.request.CreateStudentIdentityCorrectionRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.StudentIdentityCorrectionService;
import cn.hnust.selection.vo.StudentIdentityCorrectionCommandVO;
import cn.hnust.selection.vo.StudentIdentityCorrectionVO;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

@RestController
@RequestMapping("/api/students/me/identity-correction-requests")
@Validated
public class StudentIdentityCorrectionController {
    private final StudentIdentityCorrectionService correctionService;

    public StudentIdentityCorrectionController(StudentIdentityCorrectionService correctionService) {
        this.correctionService = correctionService;
    }

    @GetMapping
    public Result<PageResult<StudentIdentityCorrectionVO>> listOwnRequests(
        @RequestParam(defaultValue = "1") @Min(1) int pageNo,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(correctionService.listOwnRequests(actor, pageNo, pageSize));
    }

    @PostMapping
    public ResponseEntity<Result<StudentIdentityCorrectionCommandVO>> createRequest(
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody CreateStudentIdentityCorrectionRequest request,
        @AuthenticationPrincipal AccountPrincipal actor) {
        StudentIdentityCorrectionCommandVO created = correctionService.createRequest(actor, request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(Result.success(created));
    }
}


