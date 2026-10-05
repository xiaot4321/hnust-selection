package cn.hnust.selection.controller;

import cn.hnust.selection.common.Result;
import cn.hnust.selection.request.UpdateStudentProfileRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.StudentProfileService;
import cn.hnust.selection.service.impl.StudentProfileServiceImpl;
import cn.hnust.selection.vo.StudentProfileVO;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;

@RestController
@RequestMapping("/api/students/me")
@Validated
public class StudentProfileController {
    private final StudentProfileService profileService;

    public StudentProfileController(StudentProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    public ResponseEntity<Result<StudentProfileVO>> getProfile(@AuthenticationPrincipal AccountPrincipal actor) {
        StudentProfileVO profile = profileService.getProfile(actor);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, profile.getProfileEtag())
            .body(Result.success(profile));
    }

    @PatchMapping("/profile")
    public ResponseEntity<Result<StudentProfileVO>> updateProfile(
        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
        @Valid @RequestBody UpdateStudentProfileRequest request,
        @AuthenticationPrincipal AccountPrincipal actor) {
        StudentProfileVO profile = profileService.updateProfile(actor, ifMatch, request);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, profile.getProfileEtag())
            .body(Result.success(profile));
    }

    @PutMapping(value = "/resume", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Result<StudentProfileVO.StudentResumeVO>> uploadResume(
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestPart("file") MultipartFile file,
        @AuthenticationPrincipal AccountPrincipal actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Result.success(
            profileService.uploadResume(actor, idempotencyKey, file)));
    }
}
