package cn.hnust.selection.service;

import cn.hnust.selection.request.UpdateStudentProfileRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.vo.StudentProfileVO;
import org.springframework.web.multipart.MultipartFile;

public interface StudentProfileService {
    StudentProfileVO getProfile(AccountPrincipal actor);
    StudentProfileVO updateProfile(AccountPrincipal actor, String ifMatch, UpdateStudentProfileRequest request);
    StudentProfileVO.StudentResumeVO uploadResume(AccountPrincipal actor, String idempotencyKey, MultipartFile file);
}
