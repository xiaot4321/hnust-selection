package cn.hnust.selection.controller;

import cn.hnust.selection.common.PageResult;
import cn.hnust.selection.common.Result;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.StudentSelectionQueryService;
import cn.hnust.selection.vo.PreferenceSubmissionVO;
import cn.hnust.selection.vo.StudentPreferencesVO;
import cn.hnust.selection.vo.SupplementApplicationVO;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Positive;

@RestController
@RequestMapping("/api/students/me/batches/{batchId}")
@Validated
public class StudentSelectionQueryController {
    private final StudentSelectionQueryService queryService;

    public StudentSelectionQueryController(StudentSelectionQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/preferences")
    public Result<StudentPreferencesVO> getCurrentPreferences(
        @PathVariable("batchId") @Positive Long batchId,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(queryService.getCurrentPreferences(actor, batchId));
    }

    @GetMapping("/preference-submissions")
    public Result<PageResult<PreferenceSubmissionVO>> listPreferenceSubmissions(
        @PathVariable("batchId") @Positive Long batchId,
        @RequestParam(defaultValue = "1") @Min(1) int pageNo,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(queryService.listPreferenceSubmissions(actor, batchId, pageNo, pageSize));
    }

    @GetMapping("/supplement-applications")
    public Result<PageResult<SupplementApplicationVO>> listSupplementApplications(
        @PathVariable("batchId") @Positive Long batchId,
        @RequestParam(defaultValue = "1") @Min(1) int pageNo,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(queryService.listSupplementApplications(actor, batchId, pageNo, pageSize));
    }
}
