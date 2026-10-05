package cn.hnust.selection.controller;

import cn.hnust.selection.common.Result;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.StudentProgressService;
import cn.hnust.selection.vo.StudentProgressVO;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.constraints.Positive;

@RestController
@RequestMapping("/api/students/me/batches/{batchId}/progress")
@Validated
public class StudentProgressController {
    private final StudentProgressService progressService;

    public StudentProgressController(StudentProgressService progressService) {
        this.progressService = progressService;
    }

    @GetMapping
    public Result<StudentProgressVO> getOwnProgress(
        @PathVariable("batchId") @Positive Long batchId,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(progressService.getOwnProgress(actor, batchId));
    }
}
