package cn.hnust.selection.controller;

import cn.hnust.selection.common.PageResult;
import cn.hnust.selection.common.Result;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.StudentBatchService;
import cn.hnust.selection.vo.StudentBatchSummaryVO;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

@RestController
@RequestMapping("/api/students/me/batches")
@Validated
public class StudentBatchController {
    private final StudentBatchService studentBatchService;

    public StudentBatchController(StudentBatchService studentBatchService) {
        this.studentBatchService = studentBatchService;
    }

    @GetMapping
    public Result<PageResult<StudentBatchSummaryVO>> listOwnBatches(
        @RequestParam(defaultValue = "1") @Min(1) int pageNo,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(studentBatchService.listOwnBatches(actor, pageNo, pageSize));
    }
}
