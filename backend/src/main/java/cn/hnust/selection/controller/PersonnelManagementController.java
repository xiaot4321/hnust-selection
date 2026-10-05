package cn.hnust.selection.controller;

import cn.hnust.selection.common.Result;
import cn.hnust.selection.request.CreateMajorRequest;
import cn.hnust.selection.request.CreateStudentRequest;
import cn.hnust.selection.request.CreateTeacherRequest;
import cn.hnust.selection.request.SetAnnualEligibilityRequest;
import cn.hnust.selection.request.UpdateMajorRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.PersonnelImportService;
import cn.hnust.selection.service.PersonnelManagementService;
import cn.hnust.selection.vo.AcademicYearOptionVO;
import cn.hnust.selection.vo.AnnualEligibilityVO;
import cn.hnust.selection.vo.CollegeOptionVO;
import cn.hnust.selection.vo.MajorVO;
import cn.hnust.selection.vo.PageVO;
import cn.hnust.selection.vo.PersonnelCreatedVO;
import cn.hnust.selection.vo.PersonnelImportVO;
import cn.hnust.selection.vo.PersonnelImportRowVO;
import cn.hnust.selection.vo.PersonnelPersonVO;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Positive;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 管理端人员模块的 HTTP 入口。
 *
 * <p>控制器只绑定请求、校验基础格式并选择响应类型；人员、专业和年度资格用例由
 * {@link PersonnelManagementService} 实现，名单导入由 {@link PersonnelImportService} 实现。</p>
 */
@RestController
@RequestMapping("/api/admin")
@Validated
public class PersonnelManagementController {
    private final PersonnelManagementService personnelService;
    private final PersonnelImportService personnelImportService;

    public PersonnelManagementController(PersonnelManagementService personnelService,
                                         PersonnelImportService personnelImportService) {
        this.personnelService = personnelService;
        this.personnelImportService = personnelImportService;
    }

    @GetMapping("/personnel/colleges")
    public Result<List<CollegeOptionVO>> colleges(@AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(personnelService.listAuthorizedColleges(actor));
    }

    @GetMapping("/personnel/academic-years")
    public Result<List<AcademicYearOptionVO>> academicYears(
        @RequestParam("collegeId") @Positive Long collegeId, @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(personnelService.listAcademicYears(actor, collegeId));
    }

    @GetMapping("/majors")
    public Result<List<MajorVO>> majors(@RequestParam("collegeId") @Positive Long collegeId,
        @RequestParam(value = "activeOnly", defaultValue = "false") boolean activeOnly,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(personnelService.listMajors(actor, collegeId, activeOnly));
    }

    @PostMapping("/majors")
    public Result<MajorVO> createMajor(@Valid @RequestBody CreateMajorRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(personnelService.createMajor(actor, request, idempotencyKey));
    }

    @PatchMapping("/majors/{majorId}")
    public Result<MajorVO> updateMajor(@PathVariable("majorId") @Positive Long majorId,
        @Valid @RequestBody UpdateMajorRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(personnelService.updateMajor(actor, majorId, request, idempotencyKey));
    }

    @GetMapping("/students")
    public Result<PageVO<PersonnelPersonVO>> students(@RequestParam("collegeId") @Positive Long collegeId,
        @RequestParam(value = "identifier", required = false) String identifier,
        @RequestParam(value = "pageNo", defaultValue = "1") @Min(1) int pageNo,
        @RequestParam(value = "pageSize", defaultValue = "20") @Min(1) @Max(100) int pageSize,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(personnelService.listStudents(actor, collegeId, identifier, pageNo, pageSize));
    }

    @GetMapping("/teachers")
    public Result<PageVO<PersonnelPersonVO>> teachers(@RequestParam("collegeId") @Positive Long collegeId,
        @RequestParam(value = "identifier", required = false) String identifier,
        @RequestParam(value = "pageNo", defaultValue = "1") @Min(1) int pageNo,
        @RequestParam(value = "pageSize", defaultValue = "20") @Min(1) @Max(100) int pageSize,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(personnelService.listTeachers(actor, collegeId, identifier, pageNo, pageSize));
    }

    @PostMapping("/students")
    public Result<PersonnelCreatedVO> createStudent(@Valid @RequestBody CreateStudentRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(personnelService.createStudent(actor, request, idempotencyKey));
    }

    @PostMapping("/teachers")
    public Result<PersonnelCreatedVO> createTeacher(@Valid @RequestBody CreateTeacherRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(personnelService.createTeacher(actor, request, idempotencyKey));
    }

    @GetMapping("/annual-eligibilities")
    public Result<List<AnnualEligibilityVO>> annualEligibility(
        @RequestParam("collegeId") @Positive Long collegeId,
        @RequestParam(value = "academicYearId", required = false) @Positive Long academicYearId,
        @RequestParam(value = "personType", required = false) String personType,
        @RequestParam(value = "identifier", required = false) String identifier,
        @RequestParam(value = "history", defaultValue = "false") boolean history,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(personnelService.listAnnualEligibility(actor, collegeId, academicYearId,
            personType, identifier, history));
    }

    @PostMapping("/annual-eligibilities")
    public Result<AnnualEligibilityVO> setAnnualEligibility(@Valid @RequestBody SetAnnualEligibilityRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(personnelService.setAnnualEligibility(actor, request, idempotencyKey));
    }

    /** 固定模板下载为 UTF-8 CSV；Excel 用户可用 Excel 打开后另存为 XLSX。 */
    @GetMapping(value = "/personnel-imports/template", produces = "text/csv; charset=UTF-8")
    public ResponseEntity<byte[]> importTemplate(@RequestParam("collegeId") @Positive Long collegeId,
        @RequestParam("personType") String personType, @AuthenticationPrincipal AccountPrincipal actor) {
        String template = personnelImportService.csvTemplate(actor, collegeId, personType);
        byte[] bytes = ("\uFEFF" + template).getBytes(StandardCharsets.UTF_8);
        String filename = "STUDENT".equals(personType)
            ? "student-personnel-template.csv" : "teacher-personnel-template.csv";
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .body(bytes);
    }

    @PostMapping(value = "/personnel-imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<PersonnelImportVO> importPersonnel(@RequestParam("collegeId") @Positive Long collegeId,
        @RequestParam("academicYearId") @Positive Long academicYearId,
        @RequestParam("personType") String personType,
        @RequestParam("file") MultipartFile file,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(personnelImportService.importPersonnel(actor, collegeId, academicYearId,
            personType, file, idempotencyKey));
    }

    @GetMapping("/personnel-imports/{importId}")
    public Result<PersonnelImportVO> getImport(@PathVariable("importId") @Positive Long importId,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(personnelImportService.getImport(actor, importId));
    }

    @GetMapping("/personnel-imports/{importId}/rows")
    public Result<List<PersonnelImportRowVO>> getImportRows(@PathVariable("importId") @Positive Long importId,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(personnelImportService.getImportRows(actor, importId));
    }
}
