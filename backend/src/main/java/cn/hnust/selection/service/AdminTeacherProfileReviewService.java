package cn.hnust.selection.service;

import cn.hnust.selection.common.PageResult;
import cn.hnust.selection.request.ReviewTeacherProfileRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.vo.AdminTeacherProfileVersionVO;

public interface AdminTeacherProfileReviewService {
    PageResult<AdminTeacherProfileVersionVO> listPending(AccountPrincipal actor, Long collegeId, int pageNo, int pageSize);
    AdminTeacherProfileVersionVO get(AccountPrincipal actor, Long versionId);
    AdminTeacherProfileVersionVO review(AccountPrincipal actor, Long versionId, String ifMatch,
        ReviewTeacherProfileRequest request, String idempotencyKey);
}
