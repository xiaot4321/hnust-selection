package cn.hnust.selection.controller;

import cn.hnust.selection.common.PageResult;
import cn.hnust.selection.common.Result;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.NoticeService;
import cn.hnust.selection.vo.StudentNoticeVO;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Positive;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/me/notices")
@Validated
public class NoticeController {
    private final NoticeService noticeService;

    public NoticeController(NoticeService noticeService) {
        this.noticeService = noticeService;
    }

    @GetMapping
    public Result<PageResult<StudentNoticeVO>> listOwnNotices(
        @RequestParam(defaultValue = "1") @Min(1) int pageNo,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(noticeService.listOwnNotices(actor, pageNo, pageSize));
    }

    @PostMapping("/{noticeId}/read")
    public Result<Map<String, Object>> markOwnNoticeRead(
        @PathVariable @Positive Long noticeId,
        @AuthenticationPrincipal AccountPrincipal actor) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("noticeId", noticeId);
        result.put("readAt", noticeService.markOwnNoticeRead(actor, noticeId));
        return Result.success(result);
    }
}
