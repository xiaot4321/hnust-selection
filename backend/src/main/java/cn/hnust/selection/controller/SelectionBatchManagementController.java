package cn.hnust.selection.controller;

import cn.hnust.selection.common.Result;
import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.request.BatchScheduleRequest;
import cn.hnust.selection.request.ExtendRoundRequest;
import cn.hnust.selection.request.ReopenRoundRequest;
import cn.hnust.selection.request.CreateSelectionBatchRequest;
import cn.hnust.selection.request.SetTeacherQuotaRequest;
import cn.hnust.selection.request.SetSupplementTeachersRequest;
import cn.hnust.selection.request.UpdateSelectionBatchRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.SelectionBatchManagementService;
import cn.hnust.selection.vo.AcademicYearOptionVO;
import cn.hnust.selection.vo.BatchTeacherQuotaVO;
import cn.hnust.selection.vo.BatchStatisticsVO;
import cn.hnust.selection.vo.BatchCollegeOptionVO;
import cn.hnust.selection.vo.CollegeOptionVO;
import cn.hnust.selection.vo.MajorVO;
import cn.hnust.selection.vo.SelectionBatchDetailVO;
import cn.hnust.selection.vo.SelectionBatchSummaryVO;
import cn.hnust.selection.vo.SupplementTeacherVO;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Min;
import javax.validation.constraints.Max;
import java.util.List;

/**
 * 管理端批次草稿、排期、发布和导师名额 HTTP 入口。
 *
 * <p>本层只解析路由参数、请求校验和 HTTP 条件头，并统一包装为 Result；权限、真实对象范围、事务和状态转换
 * 均交由应用服务处理。写入后返回 ETag，客户端更新资源时须原样通过 If-Match 提交。</p>
 */
@RestController
@RequestMapping("/api/admin/selection-batches")
@Validated
public class SelectionBatchManagementController {
    private final SelectionBatchManagementService service;

    public SelectionBatchManagementController(SelectionBatchManagementService service) { this.service = service; }

    /** 返回当前管理员可管理的学院目录及是否具有学院级新建权限。 */
    @GetMapping("/colleges")
    public Result<List<BatchCollegeOptionVO>> colleges(@AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(service.listAuthorizedColleges(actor));
    }

    /** 返回授权学院可用的学年选项；正数校验在进入服务前完成。 */
    @GetMapping("/academic-years")
    public Result<List<AcademicYearOptionVO>> academicYears(@RequestParam("collegeId") @Positive Long collegeId,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(service.listAcademicYears(actor, collegeId));
    }

    /** 按指定学院读取授权范围内的批次摘要。 */
    @GetMapping
    public Result<List<SelectionBatchSummaryVO>> list(@RequestParam("collegeId") @Positive Long collegeId,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(service.listBatches(actor, collegeId));
    }

    /** 创建批次草稿；幂等键来自请求头，成功响应 ETag 为新批次行版本。 */
    @PostMapping
    public ResponseEntity<Result<SelectionBatchDetailVO>> create(@Valid @RequestBody CreateSelectionBatchRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @AuthenticationPrincipal AccountPrincipal actor) {
        SelectionBatchDetailVO result = service.createBatch(actor, request, idempotencyKey);
        return ResponseEntity.ok().eTag("batch-" + result.getBatch().getRowVersion()).body(Result.success(result));
    }

    /** 读取批次和阶段详情，并将当前 rowVersion 暴露为强 ETag。 */
    @GetMapping("/{batchId}")
    public ResponseEntity<Result<SelectionBatchDetailVO>> get(@PathVariable("batchId") @Positive Long batchId,
        @AuthenticationPrincipal AccountPrincipal actor) {
        SelectionBatchDetailVO result = service.getBatch(actor, batchId);
        return ResponseEntity.ok().eTag("batch-" + result.getBatch().getRowVersion()).body(Result.success(result));
    }

    /** 更新草稿元数据；If-Match 缺失、弱标签或格式错误时不调用业务服务。 */
    @PatchMapping("/{batchId}")
    public ResponseEntity<Result<SelectionBatchDetailVO>> update(@PathVariable("batchId") @Positive Long batchId,
        @Valid @RequestBody UpdateSelectionBatchRequest request,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @AuthenticationPrincipal AccountPrincipal actor) {
        SelectionBatchDetailVO result = service.updateBatch(actor, batchId, request, parseVersion(ifMatch, "batch"));
        return ResponseEntity.ok().eTag("batch-" + result.getBatch().getRowVersion()).body(Result.success(result));
    }

    /** 完整替换阶段排期；服务端返回更新后的批次 ETag。 */
    @PutMapping("/{batchId}/schedule")
    public ResponseEntity<Result<SelectionBatchDetailVO>> schedule(@PathVariable("batchId") @Positive Long batchId,
        @Valid @RequestBody BatchScheduleRequest request,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @AuthenticationPrincipal AccountPrincipal actor) {
        SelectionBatchDetailVO result = service.saveSchedule(actor, batchId, request, parseVersion(ifMatch, "batch"));
        return ResponseEntity.ok().eTag("batch-" + result.getBatch().getRowVersion()).body(Result.success(result));
    }

    /** 发布草稿；状态转换及运行槽位占用由事务服务原子完成。 */
    @PostMapping("/{batchId}/publish")
    public ResponseEntity<Result<SelectionBatchDetailVO>> publish(@PathVariable("batchId") @Positive Long batchId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @AuthenticationPrincipal AccountPrincipal actor) {
        SelectionBatchDetailVO result = service.publish(actor, batchId, idempotencyKey);
        return ResponseEntity.ok().eTag("batch-" + result.getBatch().getRowVersion()).body(Result.success(result));
    }

    /** 启动已发布批次；填报窗口已到时还会在服务事务内冻结名单和导师范围。 */
    @PostMapping("/{batchId}/start")
    public ResponseEntity<Result<SelectionBatchDetailVO>> start(@PathVariable("batchId") @Positive Long batchId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @AuthenticationPrincipal AccountPrincipal actor) {
        SelectionBatchDetailVO result = service.start(actor, batchId, idempotencyKey);
        return ResponseEntity.ok().eTag("batch-" + result.getBatch().getRowVersion()).body(Result.success(result));
    }

    /** 暂停活动批次；命令幂等，服务端留存阶段与操作者审计。 */
    @PostMapping("/{batchId}/pause")
    public ResponseEntity<Result<SelectionBatchDetailVO>> pause(@PathVariable("batchId") @Positive Long batchId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @AuthenticationPrincipal AccountPrincipal actor) {
        SelectionBatchDetailVO result = service.pause(actor, batchId, idempotencyKey);
        return ResponseEntity.ok().eTag("batch-" + result.getBatch().getRowVersion()).body(Result.success(result));
    }

    /** 恢复批次并按暂停时长平移未结阶段及补选窗口。 */
    @PostMapping("/{batchId}/resume")
    public ResponseEntity<Result<SelectionBatchDetailVO>> resume(@PathVariable("batchId") @Positive Long batchId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @AuthenticationPrincipal AccountPrincipal actor) {
        SelectionBatchDetailVO result = service.resume(actor, batchId, idempotencyKey);
        return ResponseEntity.ok().eTag("batch-" + result.getBatch().getRowVersion()).body(Result.success(result));
    }

    /** 取消尚未结束的批次，保留关系历史并结案待处理申请。 */
    @PostMapping("/{batchId}/cancel")
    public ResponseEntity<Result<SelectionBatchDetailVO>> cancel(@PathVariable("batchId") @Positive Long batchId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @AuthenticationPrincipal AccountPrincipal actor) {
        SelectionBatchDetailVO result = service.cancel(actor, batchId, idempotencyKey);
        return ResponseEntity.ok().eTag("batch-" + result.getBatch().getRowVersion()).body(Result.success(result));
    }

    /** 归档已经完成的批次，进入只读历史状态。 */
    @PostMapping("/{batchId}/archive")
    public ResponseEntity<Result<SelectionBatchDetailVO>> archive(@PathVariable("batchId") @Positive Long batchId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @AuthenticationPrincipal AccountPrincipal actor) {
        SelectionBatchDetailVO result = service.archive(actor, batchId, idempotencyKey);
        return ResponseEntity.ok().eTag("batch-" + result.getBatch().getRowVersion()).body(Result.success(result));
    }

    /** 解除归档回到 COMPLETED；仍需遵守管理员范围和完整审计。 */
    @PostMapping("/{batchId}/unarchive")
    public ResponseEntity<Result<SelectionBatchDetailVO>> unarchive(@PathVariable("batchId") @Positive Long batchId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @AuthenticationPrincipal AccountPrincipal actor) {
        SelectionBatchDetailVO result = service.unarchive(actor, batchId, idempotencyKey);
        return ResponseEntity.ok().eTag("batch-" + result.getBatch().getRowVersion()).body(Result.success(result));
    }

    /** 在轮次关闭前延长截止时刻，并按统一时间差顺延后续阶段。 */
    @PostMapping("/{batchId}/rounds/{roundNo}/extend")
    public ResponseEntity<Result<SelectionBatchDetailVO>> extendRound(@PathVariable("batchId") @Positive Long batchId,
        @PathVariable("roundNo") @Min(1) @Max(3) int roundNo, @Valid @RequestBody ExtendRoundRequest request,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @AuthenticationPrincipal AccountPrincipal actor) {
        SelectionBatchDetailVO result = service.extendRound(actor, batchId, roundNo, request,
            parseVersion(ifMatch, "batch"), idempotencyKey);
        return ResponseEntity.ok().eTag("batch-" + result.getBatch().getRowVersion()).body(Result.success(result));
    }

    /** 重开已关闭轮次；要求批次暂停、显式新截止时间并保留自动结案恢复范围。 */
    @PostMapping("/{batchId}/rounds/{roundNo}/reopen")
    public ResponseEntity<Result<SelectionBatchDetailVO>> reopenRound(@PathVariable("batchId") @Positive Long batchId,
        @PathVariable("roundNo") @Min(1) @Max(3) int roundNo, @Valid @RequestBody ReopenRoundRequest request,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @AuthenticationPrincipal AccountPrincipal actor) {
        SelectionBatchDetailVO result = service.reopenRound(actor, batchId, roundNo, request,
            parseVersion(ifMatch, "batch"), idempotencyKey);
        return ResponseEntity.ok().eTag("batch-" + result.getBatch().getRowVersion()).body(Result.success(result));
    }

    /** 查询批次当前补选导师名单以及名额摘要。 */
    @GetMapping("/{batchId}/supplement-teachers")
    public ResponseEntity<Result<List<SupplementTeacherVO>>> supplementTeachers(
        @PathVariable("batchId") @Positive Long batchId, @AuthenticationPrincipal AccountPrincipal actor) {
        List<SupplementTeacherVO> rows = service.listSupplementTeachers(actor, batchId);
        SelectionBatchDetailVO batch = service.getBatch(actor, batchId);
        return ResponseEntity.ok().eTag("batch-" + batch.getBatch().getRowVersion()).body(Result.success(rows));
    }

    /** 完整替换补选导师名单；已提交申请保留原导师处理权。 */
    @PutMapping("/{batchId}/supplement-teachers")
    public ResponseEntity<Result<List<SupplementTeacherVO>>> setSupplementTeachers(
        @PathVariable("batchId") @Positive Long batchId, @Valid @RequestBody SetSupplementTeachersRequest request,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @AuthenticationPrincipal AccountPrincipal actor) {
        List<SupplementTeacherVO> rows = service.setSupplementTeachers(actor, batchId, request,
            parseVersion(ifMatch, "batch"), idempotencyKey);
        SelectionBatchDetailVO batch = service.getBatch(actor, batchId);
        return ResponseEntity.ok().eTag("batch-" + batch.getBatch().getRowVersion()).body(Result.success(rows));
    }

    @GetMapping("/{batchId}/majors")
    public Result<List<MajorVO>> majors(@PathVariable("batchId") @Positive Long batchId,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(service.listBatchMajors(actor, batchId));
    }

    @GetMapping("/{batchId}/teachers")
    public Result<List<BatchTeacherQuotaVO>> teachers(@PathVariable("batchId") @Positive Long batchId,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(service.listTeacherQuotas(actor, batchId));
    }

    /** 读取批次冻结分母、匹配来源、轮次结果、补选和名额统计。 */
    @GetMapping("/{batchId}/statistics")
    public Result<BatchStatisticsVO> statistics(@PathVariable("batchId") @Positive Long batchId,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(service.statistics(actor, batchId));
    }

    /** 设置导师名额上限；同时要求强 quota ETag 与命令幂等键。 */
    @PutMapping("/{batchId}/teachers/{teacherId}/quota")
    public ResponseEntity<Result<BatchTeacherQuotaVO>> quota(@PathVariable("batchId") @Positive Long batchId,
        @PathVariable("teacherId") @Positive Long teacherId, @Valid @RequestBody SetTeacherQuotaRequest request,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @AuthenticationPrincipal AccountPrincipal actor) {
        BatchTeacherQuotaVO result = service.setTeacherQuota(actor, batchId, teacherId, request,
            parseVersion(ifMatch, "quota"), idempotencyKey);
        return ResponseEntity.ok().eTag("quota-" + result.getRowVersion()).body(Result.success(result));
    }

    /**
     * 解析本控制器支持的强版本标签，例如 {@code "batch-3"} 或 {@code "quota-2"}。
     *
     * <p>拒绝通配符和弱标签，因为业务服务需要精确版本进行乐观并发控制。</p>
     */
    private static long parseVersion(String ifMatch, String prefix) {
        if (ifMatch == null || ifMatch.trim().isEmpty() || "*".equals(ifMatch.trim())) {
            throw new ApiException("PRECONDITION_REQUIRED", "修改前请读取资源版本并发送 If-Match", HttpStatus.PRECONDITION_REQUIRED);
        }
        String value = ifMatch.trim();
        if (value.startsWith("W/")) throw new ApiException("INVALID_ARGUMENT", "If-Match 必须是强版本标签", HttpStatus.BAD_REQUEST);
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        String expectedPrefix = prefix + "-";
        if (!value.startsWith(expectedPrefix)) throw new ApiException("INVALID_ARGUMENT", "If-Match 资源版本格式不正确", HttpStatus.BAD_REQUEST);
        try { return Long.parseLong(value.substring(expectedPrefix.length())); }
        catch (NumberFormatException ex) { throw new ApiException("INVALID_ARGUMENT", "If-Match 资源版本格式不正确", HttpStatus.BAD_REQUEST); }
    }
}
