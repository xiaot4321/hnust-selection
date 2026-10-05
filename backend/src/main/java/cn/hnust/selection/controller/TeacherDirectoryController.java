package cn.hnust.selection.controller;

import cn.hnust.selection.common.PageResult;
import cn.hnust.selection.common.Result;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.TeacherDirectoryService;
import cn.hnust.selection.vo.TeacherDirectoryDetailVO;
import cn.hnust.selection.vo.TeacherDirectoryItemVO;
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
@RequestMapping("/api/teachers")
@Validated
public class TeacherDirectoryController {
    private final TeacherDirectoryService directoryService;

    public TeacherDirectoryController(TeacherDirectoryService directoryService) {
        this.directoryService = directoryService;
    }

    @GetMapping
    public Result<PageResult<TeacherDirectoryItemVO>> list(
        @RequestParam("batchId") @Positive Long batchId,
        @RequestParam(value = "keyword", required = false) String keyword,
        @RequestParam(value = "researchDirection", required = false) String researchDirection,
        @RequestParam(value = "canApply", required = false) Boolean canApply,
        @RequestParam(value = "majorId", required = false) @Positive Long majorId,
        @RequestParam(value = "degreeType", required = false) String degreeType,
        @RequestParam(value = "pageNo", defaultValue = "1") @Min(1) int pageNo,
        @RequestParam(value = "pageSize", defaultValue = "20") @Min(1) @Max(100) int pageSize,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(directoryService.list(actor, batchId, keyword, researchDirection,
            canApply, majorId, degreeType, pageNo, pageSize));
    }

    @GetMapping("/{teacherId}")
    public Result<TeacherDirectoryDetailVO> get(
        @PathVariable("teacherId") @Positive Long teacherId,
        @RequestParam("batchId") @Positive Long batchId,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(directoryService.get(actor, batchId, teacherId));
    }
}
