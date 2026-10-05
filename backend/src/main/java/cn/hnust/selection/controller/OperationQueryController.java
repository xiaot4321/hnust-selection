package cn.hnust.selection.controller;

import cn.hnust.selection.common.Result;
import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.TeacherWorkspaceService;
import cn.hnust.selection.vo.TeacherBulkOperationVO;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.constraints.Positive;

/** 查询当前导师本人发起的批量决定操作；不暴露其他操作者的业务明细。 */
@RestController
@RequestMapping("/api/operations")
@Validated
public class OperationQueryController {
    private final TeacherWorkspaceService service;
    public OperationQueryController(TeacherWorkspaceService service) { this.service = service; }

    @GetMapping("/{operationId}")
    public Result<TeacherBulkOperationVO> get(@PathVariable("operationId") @Positive Long operationId,
        @AuthenticationPrincipal AccountPrincipal actor) {
        if (actor == null || actor.getRole() == null || !"TEACHER".equals(actor.getRole().name())) {
            throw new ApiException("FORBIDDEN", "当前账号不能读取该操作", HttpStatus.FORBIDDEN);
        }
        return Result.success(service.getOperation(actor, operationId));
    }
}
