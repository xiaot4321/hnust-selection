package cn.hnust.selection.controller;

import cn.hnust.selection.common.Result;
import cn.hnust.selection.request.CreateAdminExportRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.AdminExportService;
import cn.hnust.selection.vo.AdminExportJobVO;
import cn.hnust.selection.vo.SelectionBatchSummaryVO;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import javax.validation.constraints.Positive;
import java.util.List;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/admin")
@Validated
public class AdminExportController {
    private final AdminExportService service;
    public AdminExportController(AdminExportService service) { this.service = service; }

    @GetMapping("/exports/batches")
    public Result<List<SelectionBatchSummaryVO>> batches(
        @org.springframework.web.bind.annotation.RequestParam("collegeId") @Positive Long collegeId,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(service.listBatches(actor, collegeId));
    }

    @PostMapping("/selection-batches/{batchId}/exports")
    public ResponseEntity<Result<AdminExportJobVO>> create(@PathVariable("batchId") @Positive Long batchId,
        @Valid @RequestBody CreateAdminExportRequest request, @RequestHeader("Idempotency-Key") String idempotencyKey,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return ResponseEntity.accepted().body(Result.success(service.create(actor, batchId, request, idempotencyKey)));
    }

    @GetMapping("/exports/{exportId}")
    public Result<AdminExportJobVO> get(@PathVariable("exportId") @Positive Long exportId,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(service.get(actor, exportId));
    }

    @GetMapping("/exports/{exportId}/download")
    public ResponseEntity<byte[]> download(@PathVariable("exportId") @Positive Long exportId,
        @AuthenticationPrincipal AccountPrincipal actor) {
        AdminExportService.ExportFile file = service.download(actor, exportId);
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getFilename() + "\"")
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(file.getContent());
    }
}
