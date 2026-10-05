package cn.hnust.selection.controller;

import cn.hnust.selection.common.PageResult;
import cn.hnust.selection.common.Result;
import cn.hnust.selection.service.MajorDirectoryService;
import cn.hnust.selection.vo.MajorOptionVO;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Positive;

/** 面向已登录师生的启用专业目录查询；管理字段和停用项不通过此公开查询返回。 */
@RestController
@RequestMapping("/api/majors")
@Validated
public class MajorDirectoryController {
    private final MajorDirectoryService majorDirectoryService;

    public MajorDirectoryController(MajorDirectoryService majorDirectoryService) {
        this.majorDirectoryService = majorDirectoryService;
    }

    @GetMapping
    public Result<PageResult<MajorOptionVO>> listMajors(
        @RequestParam("collegeId") @Positive Long collegeId,
        @RequestParam(value = "active", defaultValue = "true") boolean active,
        @RequestParam(value = "pageNo", defaultValue = "1") @Min(1) int pageNo,
        @RequestParam(value = "pageSize", defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return Result.success(majorDirectoryService.listMajors(collegeId, active, pageNo, pageSize));
    }
}
